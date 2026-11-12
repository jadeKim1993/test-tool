#!/usr/bin/env bash
#
# 이미지 검증 하네스 — 빌더 컨테이너 안에서 실행된다(호스트에서는 deploy/openstack/test.sh).
#
#   run.sh --image /out/<이름>.qcow2 [--report-dir DIR] [--cpus N] [--memory MB] [--keep-work]
#   (--cpus 기본 min(4, 코어 수), --memory 기본 2048 = 이미지 min_ram)
#
# 만든 qcow2 를 OpenStack Nova 가 띄우는 것과 같은 모양으로 띄운다:
#   - 인스턴스마다 이미지 위 오버레이 디스크(12G — 첫 부팅 때 루트가 늘어나는지 본다)
#   - config drive(라벨 config-2, openstack/latest/meta_data.json · network_data.json · user_data)
#   - Nova 와 같은 SMBIOS(product "OpenStack Nova"), virtio 디스크·NIC, qemu-guest-agent 채널
#
# 서버 두 대를 차례로 띄운다(동시에 띄우지 않는다 — 작은 Docker VM 에서도 메모리가 한 대 분량이면 된다).
#   A — user-data 없음(가장 흔한 사용). 기본 동작 · 이미지 위생 · 실제 전송(E2E) · 재부팅 후 유지를 본다
#   B — user-data 로 /etc/tester/override.conf 를 넣음(화면 포트 8088, JVM 옵션). 인스턴스별 설정을 본다
# A 의 서명키·machine-id·SSH 호스트 키·로그인 토큰을 기록해 두었다가 B 와 비교한다(서버마다 달라야 한다).
#
# 결과: <report-dir>/report.txt, junit.xml, console-a.log, console-b.log. 실패가 하나라도 있으면 exit 1.

set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
# shellcheck source=../lib/common.sh
. "$ROOT/lib/common.sh"

IMAGE=""
REPORT_DIR=""
CPUS=""
MEMORY=2048
KEEP_WORK=0
WORK="${WORK_DIR:-/work/harness}"

usage() { sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while [ $# -gt 0 ]; do
	case "$1" in
		--image) IMAGE="${2:?}"; shift 2 ;;
		--report-dir) REPORT_DIR="${2:?}"; shift 2 ;;
		--cpus) CPUS="${2:?}"; shift 2 ;;
		--memory) MEMORY="${2:?}"; shift 2 ;;
		--keep-work) KEEP_WORK=1; shift ;;
		-h | --help) usage 0 ;;
		*) echo "알 수 없는 옵션: $1" >&2; usage 1 ;;
	esac
done

[ -n "$IMAGE" ] || die "--image 가 필요합니다"
[ -f "$IMAGE" ] || die "이미지가 없습니다: $IMAGE"
MANIFEST="${IMAGE%.qcow2}.json"
[ -f "$MANIFEST" ] || die "매니페스트가 없습니다: $MANIFEST (build-image.sh 가 이미지와 함께 만든다)"

NAME="$(jq -r .name "$MANIFEST")"
ARCH="$(jq -r .arch "$MANIFEST")"
APP_VERSION="$(jq -r .app.version "$MANIFEST")"
IMAGE_TZ="$(jq -r .build.timezone "$MANIFEST")"
GUEST_AGENT="$(jq -r '.glance.properties.hw_qemu_guest_agent // "no"' "$MANIFEST")"
REPORT_DIR="${REPORT_DIR:-$(dirname "$IMAGE")/harness/$NAME}"

qemu_setup "$ARCH" 256
CPUS="${CPUS:-$(( $(nproc) < 4 ? $(nproc) : 4 ))}"
if [ "$QEMU_ACCEL" = kvm ]; then
	BOOT_TIMEOUT=600; APP_TIMEOUT=300; JOB_TIMEOUT=120
else
	BOOT_TIMEOUT=2400; APP_TIMEOUT=1800; JOB_TIMEOUT=600
fi

rm -rf "$WORK"
mkdir -p "$WORK" "$REPORT_DIR"
RESULTS="$WORK/results.tsv"
: > "$RESULTS"
STATE="$WORK/state"
mkdir -p "$STATE"
START_TS=$(date +%s)

declare -A QPID SSH_PORT WEB_PORT GUEST_WEB_PORT UUID HOSTNAME_OF MAC QGA
INSTANCES=(a b)
SSH_PORT=([a]=10022 [b]=10023)
WEB_PORT=([a]=10080 [b]=10081)
GUEST_WEB_PORT=([a]=80 [b]=8088)
MAC=([a]=52:54:00:7e:57:0a [b]=52:54:00:7e:57:0b)
BUILD_VM_MAC=52:54:00:12:34:56   # 빌드 VM(qemu 기본 MAC). 이미지에 이 MAC 이 남아 있으면 안 된다

cleanup() {
	local inst
	for inst in "${INSTANCES[@]}"; do
		if [ -n "${QPID[$inst]:-}" ] && kill -0 "${QPID[$inst]}" 2> /dev/null; then
			kill "${QPID[$inst]}" 2> /dev/null || true
		fi
	done
	for inst in "${INSTANCES[@]}"; do
		[ -f "$WORK/console-$inst.log" ] && cp "$WORK/console-$inst.log" "$REPORT_DIR/" 2> /dev/null || true
	done
	give_back "$REPORT_DIR"
	[ "$KEEP_WORK" -eq 1 ] || rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ================================================================ 결과 기록
PASS=0
FAIL=0
SKIP=0
LAST_RC=0

# check ID "설명" 함수 [인자…] — 함수가 0 을 돌려주면 통과. 함수 출력의 첫 줄은 근거로 보여 주고,
# 실패하면 출력 전체(마지막 20줄)를 보여 준다. 함수는 현재 셸에서 돌아 전역 상태를 남길 수 있다.
check() {
	local id="$1" desc="$2" out="$WORK/check.out" rc=0 t0=$SECONDS detail
	shift 2
	"$@" > "$out" 2>&1 || rc=$?
	LAST_RC=$rc
	detail="$(grep -v '^\s*$' "$out" | head -1 | cut -c1-160 || true)"
	if [ "$rc" -eq 0 ]; then
		PASS=$((PASS + 1))
		printf '   ✔ %-4s %s%s\n' "$id" "$desc" "${detail:+  — $detail}"
		printf '%s\tPASS\t%s\t%s\t%s\n' "$id" "$desc" "$((SECONDS - t0))" "$detail" >> "$RESULTS"
	else
		FAIL=$((FAIL + 1))
		printf '   ✘ %-4s %s\n' "$id" "$desc"
		tail -n 20 "$out" | sed 's/^/        │ /'
		printf '%s\tFAIL\t%s\t%s\t%s\n' "$id" "$desc" "$((SECONDS - t0))" "$(tail -n 20 "$out" | tr '\t\n' '  ' | cut -c1-2000)" >> "$RESULTS"
	fi
}
skip() {
	SKIP=$((SKIP + 1))
	printf '   - %-4s %s  (건너뜀: %s)\n' "$1" "$2" "$3"
	printf '%s\tSKIP\t%s\t0\t%s\n' "$1" "$2" "$3" >> "$RESULTS"
}

# ================================================================ 게스트 접속
KEY="$WORK/id_ed25519"
SSH_OPTS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR
	-o ConnectTimeout=10 -o BatchMode=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
gssh() {
	local inst="$1"
	shift
	ssh -i "$KEY" -p "${SSH_PORT[$inst]}" "${SSH_OPTS[@]}" ubuntu@127.0.0.1 "$@"
}
# 게스트 화면 서버(포트 포워딩) — 브라우저가 붙는 경로 그대로. /api 는 화면 서버가 백엔드로 넘긴다.
web() {
	local inst="$1" path="$2"
	shift 2
	curl -sS --max-time 60 "$@" "http://127.0.0.1:${WEB_PORT[$inst]}$path"
}
api() {
	local inst="$1" method="$2" path="$3" token="$4"
	shift 4
	web "$inst" "$path" -X "$method" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "$@"
}
guest_secret_hash() { gssh "$1" "sudo sed -n 's/^TESTER_JWT_SECRET=//p' /opt/tester/conf/tester.conf | sha256sum | cut -c1-16"; }

# ================================================================ 인스턴스 띄우기
config_drive() {
	local inst="$1" dir="$WORK/cd-$1"
	mkdir -p "$dir/openstack/latest"
	local pub
	pub="$(cat "$KEY.pub")"
	jq -n --arg uuid "${UUID[$inst]}" --arg host "${HOSTNAME_OF[$inst]}" --arg key "$pub" '{
		uuid: $uuid, name: $host, hostname: $host, availability_zone: "nova", launch_index: 0,
		project_id: "harness", devices: [],
		public_keys: { harness: $key },
		keys: [ { name: "harness", type: "ssh", data: $key } ]
	}' > "$dir/openstack/latest/meta_data.json"
	jq -n --arg mac "${MAC[$inst]}" '{
		links: [ { id: "tap0", type: "phy", ethernet_mac_address: $mac, mtu: 1500 } ],
		networks: [ { id: "network0", link: "tap0", type: "ipv4_dhcp", network_id: "harness-net" } ],
		services: []
	}' > "$dir/openstack/latest/network_data.json"
	echo '{}' > "$dir/openstack/latest/vendor_data.json"
	echo '{}' > "$dir/openstack/latest/vendor_data2.json"
	if [ "$inst" = b ]; then
		cat > "$dir/openstack/latest/user_data" << 'EOF'
#cloud-config
# 하네스 B: 인스턴스별 설정(override.conf)이 적용되는지 본다.
write_files:
  - path: /etc/tester/override.conf
    content: |
      TESTER_WEB_PORT=8088
      JAVA_OPTS="-Xms256m -XX:MaxRAMPercentage=40"
EOF
	fi
	make_iso "$dir" config-2 "$WORK/config-drive-$inst.iso"
}

launch() {
	local inst="$1"
	UUID[$inst]="$(cat /proc/sys/kernel/random/uuid)"
	HOSTNAME_OF[$inst]="tester-harness-$inst"
	QGA[$inst]="$WORK/qga-$inst.sock"
	qemu-img create -q -f qcow2 -F qcow2 -b "$IMAGE" "$WORK/disk-$inst.qcow2" 12G
	config_drive "$inst"
	"${QEMU_CMD[@]}" -smp "$CPUS" -m "$MEMORY" -uuid "${UUID[$inst]}" \
		-smbios "type=1,manufacturer=OpenStack Foundation,product=OpenStack Nova,serial=${UUID[$inst]}" \
		-drive "file=$WORK/disk-$inst.qcow2,if=virtio,format=qcow2,cache=unsafe,discard=unmap" \
		-drive "file=$WORK/config-drive-$inst.iso,if=virtio,format=raw,readonly=on" \
		-netdev "user,id=n0,hostfwd=tcp:127.0.0.1:${SSH_PORT[$inst]}-:22,hostfwd=tcp:127.0.0.1:${WEB_PORT[$inst]}-:${GUEST_WEB_PORT[$inst]}" \
		-device "virtio-net-pci,netdev=n0,mac=${MAC[$inst]},romfile=" \
		-device virtio-serial-pci \
		-chardev "socket,id=qga0,path=${QGA[$inst]},server=on,wait=off" \
		-device virtserialport,chardev=qga0,name=org.qemu.guest_agent.0 \
		-serial "file:$WORK/console-$inst.log" &
	QPID[$inst]=$!
	info "서버 $inst: uuid ${UUID[$inst]}, ssh :${SSH_PORT[$inst]}, 화면 :${WEB_PORT[$inst]} → ${GUEST_WEB_PORT[$inst]}"
}

wait_ssh() {
	local inst="$1" deadline=$((SECONDS + BOOT_TIMEOUT))
	while [ $SECONDS -lt $deadline ]; do
		kill -0 "${QPID[$inst]}" 2> /dev/null || { console_tail "$WORK/console-$inst.log" 30; echo "qemu 가 끝났습니다"; return 1; }
		if gssh "$inst" true 2> /dev/null; then
			echo "SSH 접속 (ubuntu, config drive 의 키) — 부팅 $((SECONDS - BOOT_T0))초"
			return 0
		fi
		sleep 5
	done
	console_tail "$WORK/console-$inst.log" 30
	echo "${BOOT_TIMEOUT}초 안에 SSH 가 열리지 않았습니다"
	return 1
}

# 화면 서버(/health)와 백엔드(/actuator/health)가 모두 응답할 때까지
wait_app() {
	local inst="$1" deadline=$((SECONDS + APP_TIMEOUT)) t0=$SECONDS
	while [ $SECONDS -lt $deadline ]; do
		if [ "$(web "$inst" /health 2> /dev/null)" = ok ] \
			&& web "$inst" /actuator/health 2> /dev/null | grep -q '"status":"UP"'; then
			echo "화면·백엔드 응답 ($((SECONDS - t0))초 대기)"
			return 0
		fi
		sleep 5
	done
	gssh "$inst" 'testerctl status; sudo journalctl -u tester-instance-init -u tester-api -u tester-web --no-pager -n 30' || true
	echo "${APP_TIMEOUT}초 안에 응답하지 않았습니다"
	return 1
}

# ================================================================ 점검 항목
c_qemu_img_check() { qemu-img check "$IMAGE" | tail -1; }
c_sha256() {
	local want got
	want="$(jq -r .sha256 "$MANIFEST")"
	got="$(sha256sum "$IMAGE" | awk '{print $1}')"
	[ "$want" = "$got" ] && echo "${got:0:16}… (매니페스트 · .sha256 일치)" || { echo "매니페스트 $want / 실제 $got"; return 1; }
	[ ! -f "$IMAGE.sha256" ] || [ "$(awk '{print $1}' "$IMAGE.sha256")" = "$got" ] || { echo ".sha256 파일 불일치"; return 1; }
}
c_format() {
	local fmt vsize want
	fmt="$(qemu-img info --output=json "$IMAGE" | jq -r .format)"
	vsize="$(qemu-img info --output=json "$IMAGE" | jq '."virtual-size"')"
	want="$(jq .virtual_size_bytes "$MANIFEST")"
	echo "format=$fmt, 가상 $((vsize / 1073741824))GiB, 파일 $(human_size "$(stat -c %s "$IMAGE")")"
	[ "$fmt" = qcow2 ] && [ "$vsize" = "$want" ]
}

c_cloud_init() {
	local out rc=0
	out="$(gssh "$1" 'sudo cloud-init status --wait --long' 2>&1)" || rc=$?
	echo "$out" | grep -E '^(status|extended_status|detail|errors|recoverable_errors):' | tr '\n' ' '
	echo
	[ "$rc" -eq 0 ] || { echo "$out"; return 1; }
}
c_datasource() {
	local inst="$1" iid ds
	iid="$(gssh "$inst" 'cat /var/lib/cloud/data/instance-id')"
	ds="$(gssh "$inst" "grep -o 'DataSourceConfigDrive[^\"]*' /run/cloud-init/result.json" || true)"
	echo "${ds:-?} · instance-id $iid"
	[ "$iid" = "${UUID[$inst]}" ] && [ -n "$ds" ]
}
c_hostname() {
	local h
	h="$(gssh "$1" hostname)"
	echo "$h"
	[ "$h" = "${HOSTNAME_OF[$1]}" ]
}
c_growpart() {
	local size
	size="$(gssh "$1" "df -B1 --output=size / | tail -1 | tr -d ' '")"
	echo "루트 $(human_size "$size") (디스크 12G)"
	[ "$size" -gt $((10 * 1073741824)) ]
}
c_system_running() {
	local state
	state="$(gssh "$1" 'systemctl is-system-running --wait' 2>&1 || true)"
	echo "systemd: $state"
	[ "$state" = running ] || { gssh "$1" 'systemctl --failed --no-legend --plain'; return 1; }
}
c_services() {
	local out
	out="$(gssh "$1" 'for u in tester-instance-init tester-api tester-web; do echo "$u=$(systemctl is-enabled $u)/$(systemctl is-active $u)"; done')"
	echo "$out" | tr '\n' ' '
	echo
	! echo "$out" | grep -v '=enabled/active$' | grep -q .
}
c_web_health() {
	local body
	body="$(web "$1" /health)"
	echo "GET :${GUEST_WEB_PORT[$1]}/health → $body"
	[ "$body" = ok ]
}
c_api_health() {
	local body
	body="$(web "$1" /actuator/health)"
	echo "GET /actuator/health (화면 서버 경유) → $body"
	echo "$body" | grep -q '"status":"UP"'
}
c_backend_local_only() {
	local out
	out="$(gssh "$1" "ss -ltnH '( sport = :8080 or sport = :${GUEST_WEB_PORT[$1]} )' | awk '{print \$4}' | sort -u | tr '\n' ' '")"
	echo "열린 주소: $out"
	# Java 는 127.0.0.1 을 듀얼 스택 소켓에 묶어 [::ffff:127.0.0.1]:8080 으로 보인다 — 여전히 루프백 전용이다.
	echo "$out" | grep -qE '(^| |\[::ffff:)127\.0\.0\.1\]?:8080' && ! echo "$out" | grep -qE '(0\.0\.0\.0|\*|\[::\]):8080' \
		&& echo "$out" | grep -qE "(0\.0\.0\.0|\*):${GUEST_WEB_PORT[$1]}"
}
c_secret_generated() {
	local inst="$1" len idf log
	len="$(gssh "$inst" "sudo sed -n 's/^TESTER_JWT_SECRET=//p' /opt/tester/conf/tester.conf | tr -d '\n' | wc -c")"
	idf="$(gssh "$inst" 'sudo cat /opt/tester/conf/instance-id')"
	log="$(gssh "$inst" 'sudo journalctl -b -u tester-instance-init --no-pager -o cat' | grep -o '새 인스턴스.*' || true)"
	echo "서명키 ${len}자 · ${log:-로그 없음}"
	[ "$len" -ge 32 ] && [ "$idf" = "${UUID[$inst]}" ] && [ -n "$log" ]
}
c_hygiene() {
	local inst="$1" bad=""
	gssh "$inst" 'test ! -e /var/log/tester-image-build.log' || bad="$bad 빌드로그"
	gssh "$inst" 'test ! -e /var/lib/cloud/instances/tester-image-build' || bad="$bad 빌드-cloud-init-상태"
	# netplan 은 root 만 읽을 수 있다. grep 의 "못 찾음"(1)과 "읽기 실패"(2)를 구분해야 한다.
	gssh "$inst" "sudo grep -rqi '$BUILD_VM_MAC' /etc/netplan/; [ \$? -eq 1 ]" || bad="$bad 빌드VM-MAC-netplan"
	gssh "$inst" "sudo grep -rqi '${MAC[$inst]}' /etc/netplan/" || bad="$bad 인스턴스-MAC-netplan-없음"
	gssh "$inst" "grep -qxE '[0-9a-f]{32}' /etc/machine-id" || bad="$bad machine-id"
	gssh "$inst" 'sudo test ! -e /root/.bash_history' || bad="$bad root-history"
	gssh "$inst" 'test "$(getent passwd | awk -F: "\$3 >= 1000 && \$3 < 65534 {print \$1}" | tr "\n" " ")" = "ubuntu "' || bad="$bad 계정"
	[ -z "$bad" ] && echo "빌드 흔적 없음 (빌드 로그·cloud-init 상태·netplan 은 인스턴스 MAC·machine-id·history·계정)" || { echo "남은 흔적:$bad"; return 1; }
}
c_release_file() {
	local v
	v="$(gssh "$1" "sed -n 's/^APP_VERSION=//p' /etc/tester-image-release")"
	echo "/etc/tester-image-release APP_VERSION=$v"
	[ "$v" = "$APP_VERSION" ]
}
c_timezone() {
	local tz
	tz="$(gssh "$1" 'timedatectl show -p Timezone --value')"
	echo "$tz"
	[ "$tz" = "${IMAGE_TZ:-Etc/UTC}" ]
}
c_sysctl() {
	local v
	v="$(gssh "$1" 'sysctl -n net.core.wmem_max net.core.rmem_max | tr "\n" " "')"
	echo "wmem_max/rmem_max = $v"
	[ "$v" = "16777216 16777216 " ]
}
c_jvm_opts() {
	local inst="$1" want="$2" cmd
	cmd="$(gssh "$inst" 'pid=$(systemctl show -p MainPID --value tester-api); tr "\0" " " < /proc/$pid/cmdline')"
	echo "$cmd" | grep -oE -- '-X[^ ]+' | tr '\n' ' '
	echo
	echo "$cmd" | grep -q -- "$want"
}
c_epoll() {
	local inst="$1" token transport
	token="$(cat "$STATE/token-$inst")"
	transport="$(api "$inst" GET /api/meta/options "$token" | jq -r .transport)"
	echo "전송 방식: $transport"
	echo "$transport" | grep -q epoll
}
login() {
	local inst="$1" user="$2" token
	token="$(web "$inst" /api/auth/signup -X POST -H 'Content-Type: application/json' -d "{\"username\":\"$user\"}" | jq -r '.accessToken // empty')"
	[ -n "$token" ] || token="$(web "$inst" /api/auth/login -X POST -H 'Content-Type: application/json' -d "{\"username\":\"$user\"}" | jq -r '.accessToken // empty')"
	[ -n "$token" ] || return 1
	echo "$token" > "$STATE/token-$inst"
	echo "가입·로그인 ($user)"
}
# 게스트 안 TCP 수신기로 1000건을 보내고, 테스트 도구가 센 건수와 실제 도착한 건수를 맞춰 본다.
c_e2e_send() {
	local inst="$1" token cat tgt job run status sent got deadline
	token="$(cat "$STATE/token-$inst")"
	gssh "$inst" 'cat > /tmp/harness-sink.py' < "$HERE/sink.py"
	gssh "$inst" 'sudo systemd-run --quiet --unit=harness-sink --collect python3 /tmp/harness-sink.py 9000'
	cat="$(api "$inst" GET /api/categories "$token" | jq '[.[] | select(.enabledTemplateCount > 0)][0].id')"
	[ "$cat" != null ] || { echo "카테고리 없음(시드 실패?)"; return 1; }
	tgt="$(api "$inst" POST /api/targets "$token" -d '{"protocol":"TCP","host":"127.0.0.1","port":9000,"framing":"NEWLINE"}' | jq .id)"
	job="$(api "$inst" POST /api/jobs "$token" -d "{\"categoryId\":$cat,\"targetId\":$tgt,\"eps\":200,\"totalLimit\":1000}" | jq .id)"
	[ "$tgt" != null ] && [ "$job" != null ] || { echo "대상/작업 생성 실패 (target=$tgt job=$job)"; return 1; }
	echo "$job" > "$STATE/job-$inst"
	api "$inst" POST "/api/jobs/$job/start" "$token" -d '{}' > /dev/null
	deadline=$((SECONDS + JOB_TIMEOUT))
	status=""
	while [ $SECONDS -lt $deadline ]; do
		run="$(api "$inst" GET "/api/jobs/$job/runs?limit=1" "$token" | jq -c '.[0] // {}')"
		status="$(jq -r '.status // empty' <<< "$run")"
		case "$status" in COMPLETED | FAILED | STOPPED) break ;; esac
		sleep 3
	done
	sent="$(jq -r '.sentCount // 0' <<< "$run")"
	got=0
	for _ in $(seq 1 20); do
		got="$(gssh "$inst" 'cat /run/harness-sink.count 2>/dev/null || echo 0')"
		[ "$got" -ge "$sent" ] && break
		sleep 2
	done
	local dropped errors
	dropped="$(jq -r '.droppedCount // 0' <<< "$run")"
	errors="$(jq -r '.errorCount // 0' <<< "$run")"
	echo "TCP 127.0.0.1:9000 · EPS 200 · 제한 1000건 → ${status:-시간초과}, 보냄 $sent = 도착 $got, 버림 $dropped, 오류 $errors (평균 $(jq -r '.avgEps // "?"' <<< "$run") EPS)"
	# 건수 제한은 배치 단위로 확인해 몇 건 넘을 수 있다(앱 설계 — LoadGeneratorLifecycleTest 도 범위로 검사한다).
	# 여기서 보는 것은 "보낸 만큼 도착했는가"다. 넘는 정도는 앱 테스트와 같은 기준(제한의 2배 이내)으로만 본다.
	[ "$status" = COMPLETED ] && [ "$sent" -ge 1000 ] && [ "$sent" -le 2000 ] && [ "$got" = "$sent" ] \
		&& [ "$dropped" = 0 ] && [ "$errors" = 0 ] || { echo "$run"; return 1; }
}
c_guest_agent() {
	python3 - "${QGA[$1]}" << 'EOF'
import json, socket, sys
s = socket.socket(socket.AF_UNIX)
s.settimeout(30)
s.connect(sys.argv[1])
s.sendall(b'{"execute":"guest-sync","arguments":{"id":4242}}\n')
buf = b""
while b"4242" not in buf:
    buf += s.recv(4096)
s.sendall(b'{"execute":"guest-info"}\n')
buf = b""
while b"\n" not in buf:
    buf += s.recv(65536)
print("qemu-guest-agent", json.loads(buf.split(b"\n")[0])["return"]["version"], "응답")
EOF
}
c_override_conf() {
	local out
	out="$(gssh "$1" "sudo grep -E '^(TESTER_WEB_PORT|JAVA_OPTS)=' /opt/tester/conf/tester.conf | tr '\n' ' '")"
	echo "$out"
	echo "$out" | grep -q 'TESTER_WEB_PORT=8088' && echo "$out" | grep -q 'MaxRAMPercentage=40'
}
# A 쪽 값은 A 를 끄기 전에 기록해 둔 것(record_identity)을 쓴다.
record_identity() {
	guest_secret_hash a > "$STATE/secret-a"
	gssh a 'cat /etc/machine-id' > "$STATE/machine-id-a"
	host_key_fp a > "$STATE/hostkey-a"
	echo "서명키·machine-id·SSH 호스트 키 기록"
}
host_key_fp() { ssh-keyscan -p "${SSH_PORT[$1]}" -t ed25519 127.0.0.1 2> /dev/null | ssh-keygen -lf - | awk '{print $2}'; }
c_secrets_differ() {
	local a b
	a="$(cat "$STATE/secret-a")"
	b="$(guest_secret_hash b)"
	echo "A ${a} / B ${b} (SHA256 앞 16자)"
	[ -n "$a" ] && [ "$a" != "$b" ]
}
c_token_rejected() {
	local code
	code="$(web b /api/auth/me -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(cat "$STATE/token-a")")"
	echo "A 의 토큰으로 B 에 요청 → HTTP $code"
	[ "$code" = 401 ]
}
c_machine_ids_differ() {
	local a b
	a="$(cat "$STATE/machine-id-a")"
	b="$(gssh b 'cat /etc/machine-id')"
	echo "A ${a:0:12}… / B ${b:0:12}…"
	[ "$a" != "$b" ]
}
c_host_keys_differ() {
	local a b
	a="$(cat "$STATE/hostkey-a")"
	b="$(host_key_fp b)"
	echo "A $a / B $b"
	[ -n "$a" ] && [ -n "$b" ] && [ "$a" != "$b" ]
}
c_reboot() {
	local inst="$1" before after deadline
	before="$(gssh "$inst" 'cat /proc/sys/kernel/random/boot_id')"
	gssh "$inst" 'sudo systemctl reboot' || true
	BOOT_T0=$SECONDS
	deadline=$((SECONDS + BOOT_TIMEOUT))
	sleep 10
	while [ $SECONDS -lt $deadline ]; do
		after="$(gssh "$inst" 'cat /proc/sys/kernel/random/boot_id' 2> /dev/null || true)"
		if [ -n "$after" ] && [ "$after" != "$before" ]; then
			echo "재부팅 후 SSH 복귀 ($((SECONDS - BOOT_T0))초)"
			return 0
		fi
		sleep 5
	done
	console_tail "$WORK/console-$inst.log" 30
	return 1
}
c_secret_kept() {
	local inst="$1" now log
	now="$(guest_secret_hash "$inst")"
	log="$(gssh "$inst" 'sudo journalctl -b -u tester-instance-init --no-pager -o cat' | grep -o '같은 인스턴스.*' || true)"
	echo "서명키 ${now} · ${log:-로그 없음}"
	[ "$now" = "$(cat "$STATE/secret-$inst")" ] && [ -n "$log" ]
}
c_token_kept() {
	local inst="$1" token code jobs
	token="$(cat "$STATE/token-$inst")"
	code="$(api "$inst" GET /api/auth/me "$token" -o /dev/null -w '%{http_code}')"
	jobs="$(api "$inst" GET /api/jobs "$token" | jq '[.[].id] | map(tostring) | join(",")' -r)"
	echo "재부팅 전 토큰 → HTTP $code · 저장된 작업 [$jobs]"
	[ "$code" = 200 ] && echo ",$jobs," | grep -q ",$(cat "$STATE/job-$inst"),"
}

stop_instance() {
	local inst="$1"
	[ -n "${QPID[$inst]:-}" ] || return 0
	gssh "$inst" 'sudo systemctl poweroff' > /dev/null 2>&1 || true
	for _ in $(seq 1 60); do kill -0 "${QPID[$inst]}" 2> /dev/null || break; sleep 2; done
	kill "${QPID[$inst]}" 2> /dev/null || true
	wait "${QPID[$inst]}" 2> /dev/null || true
	info "서버 $inst 종료"
}
# 부팅에 실패하면 나머지 점검은 의미가 없다 — 각 항목이 제한 시간까지 기다리지 않게 바로 끝낸다.
abort_if_failed() {
	[ "$LAST_RC" -eq 0 ] && return 0
	stop_instance "$1"
	write_report
	die "서버 $1 이(가) 부팅되지 않아 나머지 점검을 하지 않습니다. 콘솔: $REPORT_DIR/console-$1.log"
}

# ================================================================ 보고서
write_report() {
ELAPSED=$((SECONDS))
python3 - "$RESULTS" "$REPORT_DIR" "$NAME" "$QEMU_ACCEL" "$ELAPSED" << 'EOF'
import sys
from xml.sax.saxutils import escape, quoteattr
results, out, name, accel, elapsed = sys.argv[1:]
rows = [line.rstrip("\n").split("\t", 4) for line in open(results, encoding="utf-8") if line.strip()]
fails = [r for r in rows if r[1] == "FAIL"]
skips = [r for r in rows if r[1] == "SKIP"]
with open(f"{out}/report.txt", "w", encoding="utf-8") as f:
    f.write(f"이미지 검증 결과 — {name}\n가속 {accel} · {elapsed}초 · 통과 {len(rows) - len(fails) - len(skips)} / 실패 {len(fails)} / 건너뜀 {len(skips)}\n\n")
    for r in rows:
        f.write(f"{r[1]:4}  {r[0]:4}  {r[2]}  ({r[3]}s)\n      {r[4]}\n")
with open(f"{out}/junit.xml", "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
    f.write(f'<testsuite name={quoteattr("openstack-image " + name)} tests="{len(rows)}" failures="{len(fails)}" skipped="{len(skips)}" time="{elapsed}">\n')
    for r in rows:
        f.write(f'  <testcase classname="openstack-image" name={quoteattr(r[0] + " " + r[2])} time="{r[3]}">')
        if r[1] == "FAIL":
            f.write(f'<failure message={quoteattr(r[2])}>{escape(r[4])}</failure>')
        elif r[1] == "SKIP":
            f.write(f'<skipped message={quoteattr(r[4])}/>')
        else:
            f.write(f'<system-out>{escape(r[4])}</system-out>')
        f.write("</testcase>\n")
    f.write("</testsuite>\n")
EOF
}

# ================================================================ 실행
echo "이미지 검증 하네스 — $(basename "$IMAGE") ($ARCH, $QEMU_ACCEL, 서버당 ${CPUS} vCPU · ${MEMORY}MB)"

step "이미지 파일"
check S1 "qemu-img check (손상 없음)" c_qemu_img_check
check S2 "SHA256 = 매니페스트" c_sha256
check S3 "qcow2 · 가상 크기 = 매니페스트" c_format

ssh-keygen -q -t ed25519 -N '' -C harness -f "$KEY"

step "서버 A 부팅 — user-data 없음 (OpenStack config drive 형식)"
launch a
BOOT_T0=$SECONDS
check A00 "부팅 · SSH (config drive 의 키로 ubuntu)" wait_ssh a
abort_if_failed a

step "서버 A — 기본 동작"
check A01 "cloud-init 완료 · 오류 없음" c_cloud_init a
check A02 "데이터소스 ConfigDrive · instance-id = 메타데이터 uuid" c_datasource a
check A03 "호스트명 = 메타데이터" c_hostname a
check A04 "루트 파일시스템 확장 (growpart)" c_growpart a
check A05 "앱 기동 대기" wait_app a
check A06 "systemd 정상 (실패한 유닛 없음)" c_system_running a
check A07 "서비스 enabled/active (instance-init · api · web)" c_services a
check A08 "화면 서버 /health" c_web_health a
check A09 "백엔드 헬스 (화면 서버 프록시 경유)" c_api_health a
check A10 "백엔드 8080 은 127.0.0.1 에만, 화면은 외부에" c_backend_local_only a
check A11 "로그인 서명키 인스턴스 첫 부팅에 생성" c_secret_generated a
check A12 "빌드 흔적 없음 (일반화)" c_hygiene a
check A13 "이미지 정보 파일 버전 = 매니페스트" c_release_file a
check A14 "시간대 = 빌드 설정" c_timezone a
check A15 "커널 파라미터 (소켓 버퍼)" c_sysctl a
check A16 "JVM 힙 = 메모리 비율 (MaxRAMPercentage=50)" c_jvm_opts a 'MaxRAMPercentage=50'
check A17 "가입 · 로그인 (API)" login a harness
check A18 "Netty epoll 네이티브 전송" c_epoll a
check A19 "실제 전송 E2E (보낸 건수 = 도착 건수)" c_e2e_send a
if [ "$GUEST_AGENT" = yes ]; then
	check A20 "qemu-guest-agent 응답 (hw_qemu_guest_agent)" c_guest_agent a
else
	skip A20 "qemu-guest-agent 응답" "이미지에 설치하지 않음"
fi

step "서버 A 재부팅 — 유지돼야 하는 것"
check R00 "재부팅 전 값 기록" record_identity
check R01 "재부팅 · SSH 복귀" c_reboot a
check R02 "앱 다시 기동" wait_app a
check R03 "서명키 유지 (같은 인스턴스)" c_secret_kept a
check R04 "재부팅 전 로그인 토큰 · 저장된 작업 유지" c_token_kept a
check R05 "systemd 정상" c_system_running a
stop_instance a

step "서버 B 부팅 — user-data 로 /etc/tester/override.conf (화면 포트 8088, JVM 옵션)"
launch b
BOOT_T0=$SECONDS
check B00 "부팅 · SSH" wait_ssh b
abort_if_failed b
check B01 "cloud-init 완료 · 오류 없음" c_cloud_init b
check B02 "앱 기동 대기 (화면 포트 8088)" wait_app b
check B03 "override 반영 (TESTER_WEB_PORT=8088, JAVA_OPTS)" c_override_conf b
check B04 "화면 서버 /health on 8088" c_web_health b
check B05 "JVM 옵션 override (MaxRAMPercentage=40)" c_jvm_opts b 'MaxRAMPercentage=40'
check B06 "서비스 enabled/active" c_services b

step "A 와 B 비교 — 같은 이미지라도 서버마다 달라야 하는 것"
check X01 "로그인 서명키가 다르다" c_secrets_differ
check X02 "A 의 로그인 토큰을 B 가 거부 (401)" c_token_rejected
check X03 "machine-id 가 다르다" c_machine_ids_differ
check X04 "SSH 호스트 키가 다르다" c_host_keys_differ
stop_instance b

write_report
echo
echo "결과: 통과 $PASS · 실패 $FAIL · 건너뜀 $SKIP ($((ELAPSED / 60))분 $((ELAPSED % 60))초)"
echo "보고서: $REPORT_DIR/report.txt, junit.xml, console-a.log, console-b.log"
[ "$FAIL" -eq 0 ]
