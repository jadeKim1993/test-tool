#!/bin/bash
#
# 빌드 VM 안에서 cloud-init(runcmd)이 한 번 실행한다. 이미지에는 남지 않는다.
#
#   1) (선택) 추가 패키지 설치 — 기본은 qemu-guest-agent
#   2) 오프라인 설치본의 install.sh 로 테스트 도구 설치 — 서버에 설치하는 것과 같은 경로
#   3) 이미지용 설정 — JVM 메모리 비율, 시간대, 커널 파라미터, 인스턴스 초기화 서비스, 이미지 정보
#   4) 서비스 재기동 검증
#   5) seal.sh 예약 — cloud-init 이 끝나면 일반화하고 VM 을 끈다
#
# 진행 상황은 시리얼 콘솔(/dev/console)에 "[tester-image]" 로 남긴다. 빌더(build-image.sh)가 그것을 보여 주고,
# 마지막 줄 "TESTER-IMAGE-RESULT: OK|FAIL" 로 성공 여부를 판단한다.

set -Eeuo pipefail

SEED="${1:?시드 ISO 마운트 경로가 필요합니다}"
PAYLOAD="$SEED/payload"
# shellcheck disable=SC1091
. "$PAYLOAD/build.env"

LOG=/var/log/tester-image-build.log
exec > >(tee -a "$LOG" > /dev/console) 2>&1

say() { echo "[tester-image] $*"; }
# 빌드 VM 에는 로그인할 수 없다. 실패하면 원인을 볼 수 있게 서비스 저널을 콘솔에 남긴다.
dump_journal() {
	say "── 최근 서비스 로그 ──"
	journalctl -u tester-instance-init -u tester-api -u tester-web -n 40 --no-pager -o short-monotonic 2> /dev/null \
		| sed 's/^/[tester-image]   /' || true
}
fail() {
	trap - ERR
	say "실패: $*"
	dump_journal
	echo "TESTER-IMAGE-RESULT: FAIL $*"
	sync
	sleep 2
	systemctl poweroff --no-block || poweroff -f
	exit 1
}
trap 'fail "provision.sh ${LINENO}행 — $BASH_COMMAND"' ERR

PREFIX=/opt/tester
CONF="$PREFIX/conf/tester.conf"
GUEST="$PAYLOAD/guest"

# KEY=VALUE 를 설정 파일에 쓴다(있으면 그 줄을 바꾸고 없으면 덧붙인다). 값은 그대로 쓴다 — 따옴표 포함.
set_conf() {
	local tmp
	tmp="$(mktemp "$CONF.XXXXXX")"
	KEY="$1" VALUE="$2" awk '
		index($0, ENVIRON["KEY"] "=") == 1 { if (!done) print ENVIRON["KEY"] "=" ENVIRON["VALUE"]; done = 1; next }
		{ print }
		END { if (!done) print ENVIRON["KEY"] "=" ENVIRON["VALUE"] }' "$CONF" > "$tmp"
	chown --reference="$CONF" "$tmp"
	chmod --reference="$CONF" "$tmp"
	mv "$tmp" "$CONF"
}

# shellcheck disable=SC1091
say "프로비저닝 시작 — $IMAGE_NAME ($(. /etc/os-release && echo "$PRETTY_NAME"), $(uname -m), $(nproc) vCPU, $(free -m | awk '/^Mem:/ {print $2}')MB)"

# ---------------------------------------------------------------- 1. 추가 패키지
if [ -n "$PACKAGES" ]; then
	say "패키지 설치: $PACKAGES"
	sources=/etc/apt/sources.list.d/ubuntu.sources
	if [ -n "$APT_MIRROR" ]; then
		cp "$sources" /run/ubuntu.sources.orig
		sed -i "s|^URIs: .*|URIs: $APT_MIRROR|" "$sources"
		say "  apt 미러: $APT_MIRROR (빌드 중에만 쓰고 원래대로 돌려 놓는다)"
	fi
	# 첫 부팅 때 apt-daily 가 잠금을 잡고 있을 수 있다 — 기다린다.
	apt-get -q -o DPkg::Lock::Timeout=600 update \
		|| fail "apt-get update 실패 — 빌드 VM 이 apt 저장소에 닿지 않습니다(폐쇄망이면 --offline 또는 --apt-mirror)"
	# shellcheck disable=SC2086
	DEBIAN_FRONTEND=noninteractive apt-get -y -q -o DPkg::Lock::Timeout=600 install --no-install-recommends $PACKAGES \
		| sed -u 's/^/[tester-image]   /'
	if [ -n "$APT_MIRROR" ]; then
		mv /run/ubuntu.sources.orig "$sources"
	fi
fi

# ---------------------------------------------------------------- 2. 테스트 도구 설치
say "테스트 도구 설치: $BUNDLE_FILE"
work=/var/tmp/tester-bundle
rm -rf "$work"
mkdir -p "$work"
tar -xzf "$PAYLOAD/$BUNDLE_FILE" -C "$work" --strip-components=1 --no-same-owner
# 빌드 VM 이 에뮬레이션(TCG)이면 백엔드 기동이 수 분 걸린다 — 기다리는 시간을 늘려 준다.
TESTER_START_TIMEOUT="$START_TIMEOUT" "$work/install.sh" 2>&1 | sed -u 's/^/[tester-image]   /'
rm -rf "$work"

# ---------------------------------------------------------------- 3. 이미지용 설정
say "이미지 설정"

# JVM 힙을 고정값(-Xmx2g) 대신 VM 메모리 비율로 — 플레이버를 바꿔도(리사이즈) 맞춰진다.
set_conf JAVA_OPTS '"-Xms256m -XX:MaxRAMPercentage=50"'
cat >> "$CONF" <<'EOF'

# ── OpenStack 이미지 ─────────────────────────────────────────────────────────────
# 인스턴스마다 바꿀 값은 /etc/tester/override.conf 에 적는다(예: cloud-init user-data 의 write_files).
# 부팅할 때마다 tester-instance-init 이 그 값을 이 파일에 덮어쓴다(/etc/tester/override.conf.example 참고).
# TESTER_JWT_SECRET 은 이미지에 들어 있지 않다 — 인스턴스 첫 부팅 때 새로 만든다.
EOF
say "  JAVA_OPTS = -Xms256m -XX:MaxRAMPercentage=50"

if [ -n "$TZ_NAME" ]; then
	[ -f "/usr/share/zoneinfo/$TZ_NAME" ] || fail "알 수 없는 시간대: $TZ_NAME"
	ln -sf "/usr/share/zoneinfo/$TZ_NAME" /etc/localtime
	echo "$TZ_NAME" > /etc/timezone
	say "  시간대 = $TZ_NAME (인스턴스에서 바꾸려면 user-data 의 timezone:)"
fi

install -m 0644 "$GUEST/90-tester.conf" /etc/sysctl.d/90-tester.conf
sysctl -q --system
say "  커널 파라미터 = /etc/sysctl.d/90-tester.conf"

install -m 0755 "$GUEST/tester-instance-init" /usr/local/sbin/tester-instance-init
install -m 0644 "$GUEST/tester-instance-init.service" /etc/systemd/system/tester-instance-init.service
# install.sh 가 업그레이드 때 서비스 파일을 다시 쓰더라도 드롭인은 남는다.
for unit in tester-api tester-web; do
	install -d "/etc/systemd/system/$unit.service.d"
	install -m 0644 "$GUEST/10-instance-init.conf" "/etc/systemd/system/$unit.service.d/10-instance-init.conf"
done
install -d -m 0755 /etc/tester
install -m 0644 "$GUEST/override.conf.example" /etc/tester/override.conf.example
systemctl daemon-reload
systemctl enable -q tester-instance-init.service
say "  인스턴스 초기화 서비스 = tester-instance-init (서명키·override.conf)"

cat > /etc/tester-image-release <<EOF
IMAGE_NAME=$IMAGE_NAME
APP_VERSION=$APP_VERSION
ARCH=$IMAGE_ARCH
BASE_IMAGE=ubuntu-24.04-server-cloudimg-$IMAGE_ARCH
BASE_SERIAL=$BASE_SERIAL
BASE_SHA256=$BASE_SHA256
BUILD_TIME=$BUILD_TIME
GIT_COMMIT=$GIT_COMMIT
PACKAGES="$PACKAGES"
EOF
say "  이미지 정보 = /etc/tester-image-release"

# ---------------------------------------------------------------- 4. 재기동 검증
say "재기동 검증 (tester-instance-init → tester-api → tester-web)"
systemctl restart tester-instance-init.service
systemctl restart tester-api tester-web
# testerctl status 는 확인할 때마다 JVM 을 하나 더 띄우고 2초 안에 응답을 요구한다 — 실제 서버에서는 충분하지만
# 에뮬레이션(TCG) 빌드 VM 에서는 응답이 2초를 넘기 쉬워 판정 기준으로 쓰지 않는다. 넉넉한 제한 시간의 HTTP 로 본다.
http_get() {
	if command -v curl > /dev/null 2>&1; then
		curl -fsS --max-time 30 "$1" 2> /dev/null
	else
		"$PREFIX/node/bin/node" -e "fetch(process.argv[1],{signal:AbortSignal.timeout(30000)}).then(async r=>{if(!r.ok)process.exit(1);process.stdout.write(await r.text())}).catch(()=>process.exit(1))" "$1"
	fi
}
api_port="$(sed -n 's/^TESTER_API_PORT=//p' "$CONF" | tail -1)"
web_port="$(sed -n 's/^TESTER_WEB_PORT=//p' "$CONF" | tail -1)"
ok=0
deadline=$((SECONDS + START_TIMEOUT))
while [ "$SECONDS" -lt "$deadline" ]; do
	if http_get "http://127.0.0.1:${api_port:-8080}/actuator/health" | grep -q '"status":"UP"' \
		&& [ "$(http_get "http://127.0.0.1:${web_port:-80}/health")" = ok ] \
		&& http_get "http://127.0.0.1:${web_port:-80}/actuator/health" | grep -q '"status":"UP"'; then
		ok=1
		break
	fi
	sleep 3
done
[ "$ok" -eq 1 ] || fail "재기동 후 ${START_TIMEOUT}초 안에 응답하지 않습니다"
say "  백엔드 /actuator/health UP · 화면 /health ok · 화면 → 백엔드 프록시 OK"
{ testerctl status 2>&1 || true; } | sed 's/^/[tester-image]   /'

# ---------------------------------------------------------------- 5. 일반화 예약
# cloud-init 이 이 runcmd 를 끝내야 상태를 지울 수 있다. 별도 유닛으로 띄워 cloud-init 이 끝나기를 기다리게 한다.
install -m 0700 "$GUEST/seal.sh" /run/tester-seal.sh
systemd-run --quiet --unit=tester-seal --no-block --collect /bin/bash /run/tester-seal.sh
say "프로비저닝 완료 — cloud-init 이 끝나면 일반화(seal)하고 VM 을 끕니다"
