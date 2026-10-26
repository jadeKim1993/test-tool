#!/usr/bin/env bash
#
# 테스트 도구 오프라인 원클릭 설치 — 인터넷·Docker 가 없는 리눅스 x64 서버용.
#
# 이 설치본 안에 Java(JRE)·Node.js(npm 포함)·애플리케이션이 모두 들어 있다. 서버에서 아무것도
# 내려받지 않는다.
#
#   sudo ./install.sh                        설치. 이미 설치돼 있으면 업그레이드(설정·데이터 유지)
#   sudo ./install.sh --web-port 8000        화면 포트 지정(기본 80)
#   sudo ./install.sh --prefix /data/tester  설치 경로 지정(기본 /opt/tester)
#   sudo ./install.sh --uninstall            제거(설정·데이터는 남김)
#   sudo ./install.sh --uninstall --purge    설정·데이터까지 모두 제거
#   sudo ./install.sh --check                설치 여부만 확인(설치하지 않음)
#
# 기타 옵션: --api-port 8080, --user tester, --no-path(java/node/npm 을 PATH 에 넣지 않음)
#
# 설치되는 것
#   <prefix>/jre   Java 21 (Temurin)        <prefix>/node  Node.js + npm
#   <prefix>/app   백엔드 jar · 화면 서버     <prefix>/web   화면 파일
#   <prefix>/conf  설정(tester.conf)         <prefix>/data  데이터베이스
#   systemd 서비스 tester-api(백엔드), tester-web(화면) — 재부팅 시 자동 시작
#   /usr/local/bin/testerctl                 상태·재시작·로그 명령

set -euo pipefail

BUNDLE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILES="$BUNDLE_DIR/files"

PREFIX="/opt/tester"
WEB_PORT=""
API_PORT=""
RUN_USER="tester"
ADD_PATH=1
MODE="install"
PURGE=0
PREFIX_GIVEN=0

STEP=0
TOTAL_STEPS=8
step() { STEP=$((STEP + 1)); echo; echo "[$STEP/$TOTAL_STEPS] $*"; }
info() { echo "      $*"; }
die() { echo; echo "설치 실패: $*" >&2; exit 1; }

usage() { sed -n '3,23p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
	case "$1" in
		--prefix) PREFIX="${2:?--prefix 값이 필요합니다}"; PREFIX_GIVEN=1; shift 2 ;;
		--web-port) WEB_PORT="${2:?--web-port 값이 필요합니다}"; shift 2 ;;
		--api-port) API_PORT="${2:?--api-port 값이 필요합니다}"; shift 2 ;;
		--user) RUN_USER="${2:?--user 값이 필요합니다}"; shift 2 ;;
		--no-path) ADD_PATH=0; shift ;;
		--uninstall) MODE="uninstall"; shift ;;
		--check) MODE="check"; shift ;;
		--purge) PURGE=1; shift ;;
		-h|--help) usage 0 ;;
		*) echo "알 수 없는 옵션: $1" >&2; usage 1 ;;
	esac
done

PREFIX="${PREFIX%/}"
UNIT_DIR="/etc/systemd/system"
PROFILE_SCRIPT="/etc/profile.d/tester-runtime.sh"
CTL_LINK="/usr/local/bin/testerctl"

[ "$(id -u)" -eq 0 ] || die "root 권한이 필요합니다. sudo ./install.sh 로 실행하세요."
[ "$(uname -s)" = "Linux" ] || die "리눅스 전용 설치본입니다."

has_systemd() { [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; }

# ---------------------------------------------------------------- 기존 설치 찾기
# systemd 서비스로 등록돼 있지 않아도(systemd 가 없는 서버, 손으로 띄운 경우, 등록만 지워진 경우)
# 설치를 알아볼 수 있도록 여러 흔적을 차례로 본다. 찾은 근거는 FOUND_BY 에 남겨 그대로 보여 준다.
#   1) systemd 서비스 파일의 WorkingDirectory
#   2) 실행 중인 프로세스 — /proc/<pid>/cmdline 에 <prefix>/app/tester-api.jar 또는 web-server.mjs
#   3) /usr/local/bin/testerctl 링크가 가리키는 위치
#   4) 환경변수 TESTER_HOME (sudo 는 환경을 지우므로 /etc/profile.d 스크립트도 읽는다)
#   5) <prefix>/conf/tester.conf
FOUND_PREFIX=""
FOUND_BY=""
FOUND_PIDS=""

found() {
	local prefix="${1%/}" reason="$2"
	[ -n "$prefix" ] || return 0
	if [ -z "$FOUND_PREFIX" ]; then
		FOUND_PREFIX="$prefix"
	elif [ "$FOUND_PREFIX" != "$prefix" ]; then
		reason="$reason — 다른 경로 $prefix 를 가리킴(무시)"
	fi
	FOUND_BY="${FOUND_BY}${FOUND_BY:+$'\n'}      · $reason"
}

# 이 도구의 프로세스를 "pid prefix" 로 출력한다. ps 가 없는 최소 설치 서버도 있어서 /proc 를 직접 읽는다.
# runuser/bash -c 같은 감싸는 프로세스와 기동 확인용 --healthcheck 는 뺀다.
scan_processes() {
	local dir cmd path
	for dir in /proc/[0-9]*; do
		cmd="$(tr '\0' ' ' < "$dir/cmdline" 2>/dev/null)" || continue
		case "$cmd" in *"/app/tester-api.jar"*|*"/app/web-server.mjs"*) ;; *) continue ;; esac
		case "$cmd" in *runuser*|*"bash -c"*|*--healthcheck*) continue ;; esac
		path="$(printf '%s\n' "$cmd" | grep -oE '[^ ]*/app/(tester-api\.jar|web-server\.mjs)' | head -1)"
		[ -n "$path" ] && echo "${dir#/proc/} ${path%/app/*}"
	done
}

detect_install() {
	local unit dir pid prefix home
	unit="$UNIT_DIR/tester-api.service"
	if [ -f "$unit" ]; then
		dir="$(sed -n 's/^WorkingDirectory=//p' "$unit" | head -1)"
		found "$dir" "systemd 서비스 tester-api ($unit)"
	fi
	while read -r pid prefix; do
		[ -n "$pid" ] || continue
		FOUND_PIDS="$FOUND_PIDS $pid"
		found "$prefix" "실행 중인 프로세스 PID $pid ($prefix/app)"
	done < <(scan_processes)
	if [ -L "$CTL_LINK" ]; then
		dir="$(readlink -f "$CTL_LINK")"
		found "$(dirname "$(dirname "$dir")")" "testerctl 명령 ($CTL_LINK → $dir)"
	fi
	home="${TESTER_HOME:-}"
	[ -n "$home" ] && found "$home" "환경변수 TESTER_HOME=$home"
	if [ -f "$PROFILE_SCRIPT" ]; then
		home="$(sed -n 's/^export TESTER_HOME="\(.*\)"$/\1/p' "$PROFILE_SCRIPT" | head -1)"
		found "$home" "환경변수 TESTER_HOME ($PROFILE_SCRIPT)"
	fi
	[ -f "$PREFIX/conf/tester.conf" ] && found "$PREFIX" "설정 파일 $PREFIX/conf/tester.conf"
	FOUND_PIDS="${FOUND_PIDS# }"
	return 0
}

# 서비스·testerctl 로 내렸는데도 남은 프로세스(PID 파일이 지워졌거나 손으로 띄운 것)를 정리한다.
stop_leftovers() {
	local prefix="$1" pid owner pids=""
	while read -r pid owner; do
		[ "$owner" = "$prefix" ] && pids="$pids $pid"
	done < <(scan_processes)
	[ -n "$pids" ] || return 0
	info "관리되지 않는 실행 중 프로세스 정지:$pids"
	# shellcheck disable=SC2086
	kill $pids 2>/dev/null || true
	for _ in $(seq 1 30); do
		local alive=0
		for pid in $pids; do kill -0 "$pid" 2>/dev/null && alive=1; done
		[ "$alive" -eq 0 ] && return 0
		sleep 1
	done
	# shellcheck disable=SC2086
	kill -9 $pids 2>/dev/null || true
}

detect_install

# ---------------------------------------------------------------- 확인만
if [ "$MODE" = "check" ]; then
	if [ -z "$FOUND_PREFIX" ]; then
		echo "테스트 도구가 설치돼 있지 않습니다."
		exit 3
	fi
	echo "테스트 도구 설치됨: $FOUND_PREFIX (버전 $(cat "$FOUND_PREFIX/VERSION" 2>/dev/null || echo '알 수 없음'))"
	echo "  근거:"
	echo "$FOUND_BY"
	if [ -n "$FOUND_PIDS" ]; then
		echo "  실행 중: PID $FOUND_PIDS"
	else
		echo "  실행 중인 프로세스 없음"
	fi
	if has_systemd && [ -f "$UNIT_DIR/tester-api.service" ]; then
		echo "  서비스: tester-api $(systemctl is-active tester-api 2>/dev/null || true), tester-web $(systemctl is-active tester-web 2>/dev/null || true)"
	fi
	exit 0
fi

# 이미 다른 경로에 설치돼 있으면 그 경로를 그대로 업그레이드한다. 같은 서버에 두 벌을 두면
# 서비스 이름·포트·testerctl 이 겹쳐 서로를 망가뜨린다.
if [ -n "$FOUND_PREFIX" ] && [ "$FOUND_PREFIX" != "$PREFIX" ]; then
	if [ "$PREFIX_GIVEN" -eq 1 ] && [ "$MODE" = "install" ]; then
		echo "이미 $FOUND_PREFIX 에 설치돼 있습니다:" >&2
		echo "$FOUND_BY" >&2
		die "같은 서버에 두 벌은 설치할 수 없습니다. 업그레이드는 --prefix 없이(또는 --prefix $FOUND_PREFIX), 옮기려면 먼저 --uninstall 하세요."
	fi
	PREFIX="$FOUND_PREFIX"
fi
CONF="$PREFIX/conf/tester.conf"

# ---------------------------------------------------------------- 제거
if [ "$MODE" = "uninstall" ]; then
	if [ -z "$FOUND_PREFIX" ]; then
		echo "설치된 테스트 도구를 찾지 못했습니다(systemd·프로세스·testerctl·TESTER_HOME·$PREFIX 확인)."
		exit 0
	fi
	echo "테스트 도구를 제거합니다 ($PREFIX)"
	echo "$FOUND_BY"
	if has_systemd; then
		systemctl disable --now tester-web tester-api 2>/dev/null || true
		rm -f "$UNIT_DIR/tester-web.service" "$UNIT_DIR/tester-api.service"
		systemctl daemon-reload
	fi
	[ -x "$PREFIX/bin/testerctl" ] && "$PREFIX/bin/testerctl" stop >/dev/null 2>&1 || true
	stop_leftovers "$PREFIX"
	rm -f "$CTL_LINK" "$PROFILE_SCRIPT"
	if [ "$PURGE" -eq 1 ]; then
		rm -rf "$PREFIX"
		if id "$RUN_USER" >/dev/null 2>&1; then userdel "$RUN_USER" 2>/dev/null || true; fi
		echo "모두 제거했습니다(설정·데이터 포함)."
	else
		rm -rf "$PREFIX/jre" "$PREFIX/node" "$PREFIX/app" "$PREFIX/web" "$PREFIX/bin" "$PREFIX/run" "$PREFIX/VERSION"
		echo "제거했습니다. 설정($PREFIX/conf)과 데이터($PREFIX/data)는 남겼습니다."
		echo "완전히 지우려면: sudo ./install.sh --uninstall --purge"
	fi
	exit 0
fi

# ---------------------------------------------------------------- 설치
[ -f "$BUNDLE_DIR/bundle.info" ] || die "bundle.info 가 없습니다. 압축을 푼 디렉터리에서 실행하세요."
# shellcheck disable=SC1091
. "$BUNDLE_DIR/bundle.info"

echo "테스트 도구 $BUNDLE_VERSION 설치 (Java $BUNDLE_JRE · Node $BUNDLE_NODE · $BUNDLE_ARCH)"
echo "설치 경로: $PREFIX"

step "서버 확인"
case "$(uname -m)" in
	x86_64|amd64) HOST_ARCH="x64" ;;
	aarch64|arm64) HOST_ARCH="aarch64" ;;
	*) HOST_ARCH="$(uname -m)" ;;
esac
[ "$HOST_ARCH" = "$BUNDLE_ARCH" ] || die "이 설치본은 $BUNDLE_ARCH 용인데 서버는 $HOST_ARCH 입니다."
for cmd in tar gzip; do
	command -v "$cmd" >/dev/null 2>&1 || die "$cmd 명령이 없습니다."
done
# Node 24 공식 빌드는 glibc 2.28 이상이 필요하다(RHEL/Rocky 8+, Ubuntu 20.04+). CentOS 7 은 안 된다.
GLIBC="$(getconf GNU_LIBC_VERSION 2>/dev/null | awk '{print $2}')"
if [ -n "$GLIBC" ]; then
	if [ "$(printf '%s\n2.28\n' "$GLIBC" | sort -V | head -1)" != "2.28" ]; then
		die "glibc $GLIBC 입니다. Node.js 실행에 glibc 2.28 이상이 필요합니다(RHEL/Rocky 8+, Ubuntu 20.04+)."
	fi
	info "glibc $GLIBC — OK"
fi
if has_systemd; then
	info "systemd — 서비스로 등록합니다(재부팅 시 자동 시작)"
else
	info "systemd 없음 — testerctl 이 직접 프로세스를 띄웁니다(재부팅 시 자동 시작 안 됨)"
fi

UPGRADE=0
if [ -n "$FOUND_PREFIX" ]; then
	info "기존 설치 발견 ($PREFIX):"
	echo "$FOUND_BY"
	if [ -f "$CONF" ]; then
		UPGRADE=1
		info "업그레이드합니다(설정·데이터 유지)"
	else
		info "설정 파일이 없어 새로 만듭니다(데이터는 유지)"
	fi
else
	info "새로 설치합니다"
fi

step "실행 계정 준비 ($RUN_USER)"
if id "$RUN_USER" >/dev/null 2>&1; then
	info "이미 있음"
else
	NOLOGIN="$(command -v nologin 2>/dev/null || echo /sbin/nologin)"
	useradd --system --no-create-home --home-dir "$PREFIX" --shell "$NOLOGIN" "$RUN_USER"
	info "계정을 만들었습니다"
fi

step "기존 실행 정지"
if has_systemd; then
	systemctl stop tester-web tester-api 2>/dev/null || true
fi
[ -x "$PREFIX/bin/testerctl" ] && "$PREFIX/bin/testerctl" stop >/dev/null 2>&1 || true
stop_leftovers "$PREFIX"
info "완료"

# 압축을 임시 위치에 푼 뒤 바꿔치기한다 — 도중에 실패해도 기존 런타임이 반쯤 지워진 채 남지 않는다.
install_archive() {
	local archive="$1" target="$2" staging="$2.new"
	rm -rf "$staging"
	mkdir -p "$staging"
	tar -xzf "$archive" -C "$staging" --strip-components=1 --no-same-owner
	rm -rf "$target"
	mv "$staging" "$target"
}

step "Java·Node.js 설치"
mkdir -p "$PREFIX"
install_archive "$FILES/jre.tar.gz" "$PREFIX/jre"
info "Java  → $PREFIX/jre  ($("$PREFIX/jre/bin/java" -version 2>&1 | head -1))"
install_archive "$FILES/node.tar.gz" "$PREFIX/node"
info "Node  → $PREFIX/node ($("$PREFIX/node/bin/node" --version), npm $("$PREFIX/node/bin/node" "$PREFIX/node/lib/node_modules/npm/bin/npm-cli.js" --version 2>/dev/null || echo '?'))"

step "애플리케이션 설치"
rm -rf "$PREFIX/app.new" "$PREFIX/web.new"
mkdir -p "$PREFIX/app.new" "$PREFIX/bin"
cp "$FILES/tester-api.jar" "$FILES/web-server.mjs" "$PREFIX/app.new/"
cp -R "$FILES/web" "$PREFIX/web.new"
rm -rf "$PREFIX/app" "$PREFIX/web"
mv "$PREFIX/app.new" "$PREFIX/app"
mv "$PREFIX/web.new" "$PREFIX/web"
install -m 0755 "$FILES/testerctl" "$PREFIX/bin/testerctl"
ln -sf "$PREFIX/bin/testerctl" "$CTL_LINK"
echo "$BUNDLE_VERSION" > "$PREFIX/VERSION"
info "백엔드 → $PREFIX/app/tester-api.jar"
info "화면   → $PREFIX/web (화면 서버 $PREFIX/app/web-server.mjs)"

step "설정"
mkdir -p "$PREFIX/conf" "$PREFIX/data" "$PREFIX/run" "$PREFIX/logs"
set_conf() {
	if grep -q "^$1=" "$CONF"; then
		sed -i "s|^$1=.*|$1=$2|" "$CONF"
	else
		echo "$1=$2" >> "$CONF"
	fi
}
if [ "$UPGRADE" -eq 0 ]; then
	if command -v openssl >/dev/null 2>&1; then
		SECRET="$(openssl rand -base64 48 | tr -d '\n')"
	else
		SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
	fi
	cat > "$CONF" <<EOF
# 테스트 도구 설정. 바꾼 뒤에는: sudo testerctl restart

# 화면 포트(브라우저가 접속하는 포트). 화면 서버가 /api 를 백엔드로 넘긴다.
TESTER_WEB_PORT=${WEB_PORT:-80}
TESTER_WEB_BIND=0.0.0.0

# 백엔드 포트. 화면 서버 뒤에서만 쓰이므로 기본은 이 서버 안(127.0.0.1)에서만 열린다.
# API 를 다른 장비에서 직접 부르려면 0.0.0.0 으로 바꾼다.
TESTER_API_PORT=${API_PORT:-8080}
TESTER_API_BIND=127.0.0.1

# 로그인 토큰 서명키. 설치할 때 자동으로 만들었다. 바꾸면 모든 사용자가 다시 로그인해야 한다.
TESTER_JWT_SECRET=$SECRET

# 백엔드 JVM 옵션. 10만 EPS 이상 고부하라면 -Xmx 를 늘린다.
JAVA_OPTS="-Xms256m -Xmx2g"

# 데이터베이스(H2 파일, $PREFIX/data). 개발용 기본값의 AUTO_SERVER 는 호스트명을 조회해서
# 호스트명이 DNS·/etc/hosts 에 없는 서버에서는 기동이 실패한다. 이 설치에서는 쓰지 않는다.
TESTER_DB_URL="jdbc:h2:file:./data/tester;DB_CLOSE_DELAY=-1"
EOF
	info "새로 만들었습니다: $CONF (JWT 서명키 자동 생성)"
else
	[ -n "$WEB_PORT" ] && set_conf TESTER_WEB_PORT "$WEB_PORT"
	[ -n "$API_PORT" ] && set_conf TESTER_API_PORT "$API_PORT"
	grep -q '^TESTER_DB_URL=' "$CONF" || set_conf TESTER_DB_URL '"jdbc:h2:file:./data/tester;DB_CLOSE_DELAY=-1"'
	info "기존 설정 유지: $CONF"
fi
chown root:"$RUN_USER" "$CONF"
chmod 0640 "$CONF"
chown -R "$RUN_USER" "$PREFIX/data" "$PREFIX/run" "$PREFIX/logs"

# shellcheck disable=SC1090
. "$CONF"
for port in "$TESTER_WEB_PORT" "$TESTER_API_PORT"; do
	if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
		die "포트 $port 를 이미 다른 프로그램이 쓰고 있습니다. --web-port / --api-port 로 바꾸세요."
	fi
done

if [ "$ADD_PATH" -eq 1 ]; then
	# 기존에 설치된 java/node 가 있으면 그것을 우선한다(뒤에 붙인다). 이 도구는 자기 경로를 직접 쓴다.
	cat > "$PROFILE_SCRIPT" <<EOF
# 테스트 도구가 설치한 Java·Node.js (install.sh 가 만듦, --uninstall 로 지워짐)
export TESTER_HOME="$PREFIX"
[ -z "\${JAVA_HOME:-}" ] && export JAVA_HOME="$PREFIX/jre"
case ":\$PATH:" in *":$PREFIX/jre/bin:"*) ;; *) export PATH="\$PATH:$PREFIX/jre/bin:$PREFIX/node/bin" ;; esac
EOF
	info "java·node·npm 을 PATH 에 추가했습니다($PROFILE_SCRIPT — 새 로그인부터 적용)"
fi

step "서비스 시작"
if has_systemd; then
	cat > "$UNIT_DIR/tester-api.service" <<EOF
[Unit]
Description=테스트 도구 백엔드
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
WorkingDirectory=$PREFIX
EnvironmentFile=$CONF
ExecStart=$PREFIX/jre/bin/java \$JAVA_OPTS -jar $PREFIX/app/tester-api.jar --server.port=\${TESTER_API_PORT} --server.address=\${TESTER_API_BIND}
Restart=on-failure
RestartSec=5
# 고부하 TCP 전송은 커넥션/파일을 많이 연다.
LimitNOFILE=65536
# SIGTERM 으로 정상 종료하면 JVM 은 143 으로 끝난다.
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF
	cat > "$UNIT_DIR/tester-web.service" <<EOF
[Unit]
Description=테스트 도구 화면
After=network-online.target tester-api.service
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
WorkingDirectory=$PREFIX
EnvironmentFile=$CONF
Environment=TESTER_WEB_ROOT=$PREFIX/web
ExecStart=$PREFIX/node/bin/node $PREFIX/app/web-server.mjs
Restart=on-failure
RestartSec=3
# root 가 아니어도 80 같은 1024 미만 포트를 열 수 있게 한다.
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
EOF
	systemctl daemon-reload
	systemctl enable tester-api tester-web >/dev/null 2>&1
	systemctl restart tester-api tester-web
else
	"$PREFIX/bin/testerctl" start >/dev/null
fi

echo -n "      백엔드 기동 대기 "
for i in $(seq 1 180); do
	if "$PREFIX/jre/bin/java" -jar "$PREFIX/app/tester-api.jar" --healthcheck \
			"http://127.0.0.1:$TESTER_API_PORT/actuator/health" >/dev/null 2>&1; then
		echo " OK"
		break
	fi
	[ "$i" -eq 180 ] && { echo; die "백엔드가 180초 안에 뜨지 않았습니다. testerctl logs api 로 확인하세요."; }
	echo -n "."
	sleep 1
done
if ! "$PREFIX/node/bin/node" -e "fetch('http://127.0.0.1:$TESTER_WEB_PORT/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"; then
	sleep 2
	"$PREFIX/node/bin/node" -e "fetch('http://127.0.0.1:$TESTER_WEB_PORT/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))" \
		|| die "화면 서버가 응답하지 않습니다. testerctl logs web 으로 확인하세요."
fi
info "화면 서버 OK"

step "완료"
IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
PORT_SUFFIX=""
[ "$TESTER_WEB_PORT" != "80" ] && PORT_SUFFIX=":$TESTER_WEB_PORT"
echo
echo "  접속 주소   http://${IP:-<서버IP>}$PORT_SUFFIX"
echo "  상태 확인   testerctl status"
echo "  로그        testerctl logs        (testerctl logs api / web)"
echo "  재시작      sudo testerctl restart"
echo "  설정        $CONF"
echo
echo "  ※ 비밀번호 없이 아이디만으로 로그인하는 도구입니다. 방화벽으로 접근 대역을 제한하세요."
