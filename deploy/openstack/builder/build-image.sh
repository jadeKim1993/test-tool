#!/usr/bin/env bash
#
# OpenStack qcow2 이미지 빌드 — 빌더 컨테이너 안에서 실행된다(호스트에서는 deploy/openstack/build.sh 를 쓴다).
#
#   build-image.sh --bundle /in/tester-offline-<버전>-linux-x64.tar.gz [옵션]
#
# 흐름
#   1) Ubuntu 24.04 클라우드 이미지 확보 — 캐시 → 내려받기 → SHA256SUMS 대조
#   2) 작업 디스크 — 베이스 위 qcow2 오버레이(--disk-size 로 키운다)
#   3) 시드 ISO(cidata) — cloud-init user-data + 오프라인 설치본 + guest 스크립트
#   4) QEMU 부팅 → provision.sh(설치·설정·검증) → seal.sh(일반화) → 스스로 전원 끔
#   5) 오버레이를 펼쳐 압축 qcow2 로 → qemu-img check → SHA256 · 매니페스트(JSON)
#
# 옵션
#   --bundle FILE        오프라인 설치본(./gradlew offlineBundle 결과). 필수
#   --arch amd64|arm64   게스트 아키텍처 (기본 amd64). 설치본 아키텍처와 맞아야 한다
#   --out DIR            결과물 위치 (기본 /out)
#   --name NAME          결과물 이름 (기본 tester-<버전>-ubuntu-24.04-<arch>)
#   --disk-size SIZE     이미지 가상 디스크 크기 = 최소 디스크 (기본 8G)
#   --timezone TZ        시간대 (기본 Asia/Seoul, 빈 값이면 UTC 그대로)
#   --packages "A B"     빌드 VM 에서 apt 로 더 설치할 패키지 (기본 qemu-guest-agent)
#   --offline            빌드 VM 네트워크를 막고 패키지 설치를 하지 않는다(폐쇄망)
#   --apt-mirror URL     빌드 중에만 쓸 apt 미러 (arm64 는 ubuntu-ports 미러)
#   --base-serial S      베이스 이미지 릴리스 (기본 release = 최신, 예: 20261017 로 고정)
#   --base-url-root URL  베이스 이미지 미러 (기본 https://cloud-images.ubuntu.com/releases/noble)
#   --base-image FILE    베이스 이미지를 파일로 직접 지정(내려받지 않음)
#   --cpus N / --memory MB   빌드 VM 크기 (기본 min(4, 코어 수) / 2048)
#   --timeout SEC        빌드 VM 제한 시간 (기본 KVM 1200, TCG 5400)
#   --keep-work          실패해도 작업 디렉터리를 남긴다(디버깅)

set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
# shellcheck source=../lib/common.sh
. "$ROOT/lib/common.sh"

BUNDLE=""
ARCH=amd64
OUT_DIR=/out
CACHE_DIR="${CACHE_DIR:-/cache}"
WORK_DIR="${WORK_DIR:-/work/build}"
NAME=""
DISK_SIZE=8G
TZ_NAME=Asia/Seoul
PACKAGES="qemu-guest-agent"
PACKAGES_GIVEN=0
OFFLINE=0
APT_MIRROR=""
BASE_SERIAL=release
BASE_URL_ROOT="https://cloud-images.ubuntu.com/releases/noble"
BASE_IMAGE=""
CPUS=""
MEMORY=2048
TIMEOUT=""
KEEP_WORK=0
GIT_COMMIT="${GIT_COMMIT:-unknown}"

usage() { sed -n '3,33p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
	case "$1" in
		--bundle) BUNDLE="${2:?}"; shift 2 ;;
		--arch) ARCH="${2:?}"; shift 2 ;;
		--out) OUT_DIR="${2:?}"; shift 2 ;;
		--name) NAME="${2:?}"; shift 2 ;;
		--disk-size) DISK_SIZE="${2:?}"; shift 2 ;;
		--timezone) TZ_NAME="${2-}"; shift 2 ;;
		--packages) PACKAGES="${2-}"; PACKAGES_GIVEN=1; shift 2 ;;
		--offline) OFFLINE=1; shift ;;
		--apt-mirror) APT_MIRROR="${2:?}"; shift 2 ;;
		--base-serial) BASE_SERIAL="${2:?}"; shift 2 ;;
		--base-url-root) BASE_URL_ROOT="${2%/}"; shift 2 ;;
		--base-image) BASE_IMAGE="${2:?}"; shift 2 ;;
		--cpus) CPUS="${2:?}"; shift 2 ;;
		--memory) MEMORY="${2:?}"; shift 2 ;;
		--timeout) TIMEOUT="${2:?}"; shift 2 ;;
		--keep-work) KEEP_WORK=1; shift ;;
		-h | --help) usage 0 ;;
		*) echo "알 수 없는 옵션: $1" >&2; usage 1 ;;
	esac
done

[ -n "$BUNDLE" ] || die "--bundle 이 필요합니다"
[ -f "$BUNDLE" ] || die "설치본이 없습니다: $BUNDLE"
if [ "$OFFLINE" -eq 1 ]; then
	[ "$PACKAGES_GIVEN" -eq 1 ] && [ -n "$PACKAGES" ] && die "--offline 과 --packages 는 함께 쓸 수 없습니다(빌드 VM 이 apt 저장소에 닿지 않는다)"
	PACKAGES=""
fi

qemu_setup "$ARCH" 256
CPUS="${CPUS:-$(( $(nproc) < 4 ? $(nproc) : 4 ))}"
if [ "$QEMU_ACCEL" = kvm ]; then
	TIMEOUT="${TIMEOUT:-1200}"
	START_TIMEOUT=180
else
	TIMEOUT="${TIMEOUT:-5400}"
	START_TIMEOUT=1200
fi

QEMU_PID=""
WATCHDOG_PID=""
cleanup() {
	local rc=$?
	[ -n "$WATCHDOG_PID" ] && kill "$WATCHDOG_PID" 2> /dev/null || true
	if [ -n "$QEMU_PID" ] && kill -0 "$QEMU_PID" 2> /dev/null; then
		kill "$QEMU_PID" 2> /dev/null || true
	fi
	if [ "$rc" -ne 0 ] && [ "$KEEP_WORK" -eq 1 ]; then
		echo "   작업 디렉터리를 남겼습니다: $WORK_DIR"
	else
		rm -rf "$WORK_DIR"
	fi
	rm -f "$OUT_DIR"/*.part 2> /dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT TERM

START_TS=$(date +%s)
BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------------------------------------------------------- 설치본
step "설치본 확인"
BUNDLE_INFO="$(tar -xzOf "$BUNDLE" --wildcards '*/bundle.info' 2> /dev/null)" || die "설치본에서 bundle.info 를 찾지 못했습니다: $BUNDLE"
BUNDLE_VERSION="$(sed -n 's/^BUNDLE_VERSION="\(.*\)"$/\1/p' <<< "$BUNDLE_INFO")"
BUNDLE_ARCH="$(sed -n 's/^BUNDLE_ARCH="\(.*\)"$/\1/p' <<< "$BUNDLE_INFO")"
case "$ARCH:$BUNDLE_ARCH" in
	amd64:x64 | arm64:aarch64) ;;
	*) die "설치본은 $BUNDLE_ARCH 용인데 이미지는 $ARCH 입니다(arm64 이미지는 ./gradlew offlineBundle -PbundleArch=aarch64)" ;;
esac
APP_VERSION="$BUNDLE_VERSION"
NAME="${NAME:-tester-$APP_VERSION-ubuntu-24.04-$ARCH}"
info "$(basename "$BUNDLE") — 버전 $APP_VERSION, $BUNDLE_ARCH, $(human_size "$(stat -c %s "$BUNDLE")")"
info "결과물 이름: $NAME"
info "가속: $QEMU_ACCEL$([ "$QEMU_ACCEL" = tcg ] && echo ' (KVM 없음 — 소프트웨어 에뮬레이션이라 수십 분 걸릴 수 있다)')"

# ---------------------------------------------------------------- 1. 베이스 이미지
step "베이스 이미지 (Ubuntu 24.04 클라우드 이미지, $ARCH)"
mkdir -p "$CACHE_DIR" "$WORK_DIR" "$OUT_DIR"
IMG_FILE="ubuntu-24.04-server-cloudimg-$ARCH.img"
BASE_SHA=""
BASE_SERIAL_RESOLVED=""

if [ -n "$BASE_IMAGE" ]; then
	[ -f "$BASE_IMAGE" ] || die "베이스 이미지가 없습니다: $BASE_IMAGE"
	BASE_PATH="$BASE_IMAGE"
	BASE_URL="file:$(basename "$BASE_IMAGE")"
	BASE_SERIAL_RESOLVED="$([ "$BASE_SERIAL" = release ] && echo unknown || echo "$BASE_SERIAL")"
	info "지정한 파일 사용: $BASE_IMAGE"
else
	if [ "$BASE_SERIAL" = release ]; then dir=release; else dir="release-$BASE_SERIAL"; fi
	URL_DIR="$BASE_URL_ROOT/$dir"
	BASE_URL="$URL_DIR/$IMG_FILE"
	expected=""
	if sums="$(curl -fsSL --max-time 30 "$URL_DIR/SHA256SUMS" 2> /dev/null)"; then
		expected="$(awk -v f="$IMG_FILE" '$2 == "*" f || $2 == f { print $1 }' <<< "$sums")"
		[ -n "$expected" ] || die "SHA256SUMS 에 $IMG_FILE 이 없습니다: $URL_DIR"
		BASE_SERIAL_RESOLVED="$(curl -fsSL --max-time 30 "$URL_DIR/unpacked/build-info.txt" 2> /dev/null | sed -n 's/^serial=//p')" || true
		[ -n "$BASE_SERIAL_RESOLVED" ] || BASE_SERIAL_RESOLVED="$([ "$BASE_SERIAL" = release ] && echo unknown || echo "$BASE_SERIAL")"
		BASE_PATH="$CACHE_DIR/noble-$BASE_SERIAL_RESOLVED-$IMG_FILE"
		if [ -f "$BASE_PATH" ] && [ "$(sha256sum "$BASE_PATH" | awk '{print $1}')" = "$expected" ]; then
			info "캐시 사용: $(basename "$BASE_PATH") (serial $BASE_SERIAL_RESOLVED, SHA256 일치)"
		else
			info "내려받는 중: $BASE_URL"
			progress=(-sS)
			[ -t 2 ] && progress=(--progress-bar)
			curl -fL --retry 3 --retry-delay 5 "${progress[@]}" -o "$BASE_PATH.part" "$BASE_URL" \
				|| die "베이스 이미지를 내려받지 못했습니다: $BASE_URL"
			[ "$(sha256sum "$BASE_PATH.part" | awk '{print $1}')" = "$expected" ] \
				|| { rm -f "$BASE_PATH.part"; die "SHA256 이 SHA256SUMS 와 다릅니다: $BASE_URL"; }
			mv "$BASE_PATH.part" "$BASE_PATH"
			info "받음: $(basename "$BASE_PATH") ($(human_size "$(stat -c %s "$BASE_PATH")"), serial $BASE_SERIAL_RESOLVED, SHA256 일치)"
		fi
	else
		# 인터넷이 안 되는 빌드 장비 — 캐시에 넣어 둔 파일을 쓴다.
		BASE_PATH=""
		if [ "$BASE_SERIAL" != release ] && [ -f "$CACHE_DIR/noble-$BASE_SERIAL-$IMG_FILE" ]; then
			BASE_PATH="$CACHE_DIR/noble-$BASE_SERIAL-$IMG_FILE"
		elif [ "$BASE_SERIAL" = release ]; then
			BASE_PATH="$(find "$CACHE_DIR" -maxdepth 1 -name "noble-*-$IMG_FILE" | sort | tail -1)"
			[ -n "$BASE_PATH" ] || { [ -f "$CACHE_DIR/$IMG_FILE" ] && BASE_PATH="$CACHE_DIR/$IMG_FILE"; }
		fi
		[ -n "$BASE_PATH" ] || die "$URL_DIR 에 닿지 않고 캐시에도 베이스 이미지가 없습니다.
       인터넷이 되는 곳에서 받은 $IMG_FILE 을 .runtime-cache/openstack/ 에 넣거나
       --base-image 로 지정하거나, --base-url-root 로 내부 미러를 지정하세요."
		BASE_SERIAL_RESOLVED="$(basename "$BASE_PATH" | sed -n 's/^noble-\([^-]*\)-.*/\1/p')"
		BASE_SERIAL_RESOLVED="${BASE_SERIAL_RESOLVED:-unknown}"
		warn "SHA256SUMS 에 닿지 않습니다 — 캐시 파일을 대조 없이 사용합니다: $(basename "$BASE_PATH")"
	fi
fi
BASE_SHA="${BASE_SHA:-$(sha256sum "$BASE_PATH" | awk '{print $1}')}"
[ "$(qemu-img info --output=json "$BASE_PATH" | jq -r .format)" = qcow2 ] || die "베이스 이미지가 qcow2 가 아닙니다: $BASE_PATH"

# ---------------------------------------------------------------- 2. 작업 디스크
step "작업 디스크 (베이스 위 오버레이, $DISK_SIZE)"
# 베이스를 컨테이너 안으로 복사해 백킹 파일로 쓴다 — macOS Docker 의 바인드 마운트는 임의 읽기가 느리다.
cp "$BASE_PATH" "$WORK_DIR/base.img"
qemu-img create -q -f qcow2 -F qcow2 -b "$WORK_DIR/base.img" "$WORK_DIR/disk.qcow2" "$DISK_SIZE"
info "$(qemu-img info --output=json "$WORK_DIR/disk.qcow2" | jq -r '"가상 크기 \(."virtual-size" / 1073741824)GiB"')"

# ---------------------------------------------------------------- 3. 시드 ISO
step "시드 ISO (cloud-init NoCloud: user-data + 설치본 + guest 스크립트)"
SEED="$WORK_DIR/seed"
mkdir -p "$SEED/payload/guest"
cp "$BUNDLE" "$SEED/payload/"
cp "$ROOT"/guest/* "$SEED/payload/guest/"
{
	printf 'IMAGE_NAME=%q\n' "$NAME"
	printf 'APP_VERSION=%q\n' "$APP_VERSION"
	printf 'IMAGE_ARCH=%q\n' "$ARCH"
	printf 'BUNDLE_FILE=%q\n' "$(basename "$BUNDLE")"
	printf 'BASE_SERIAL=%q\n' "$BASE_SERIAL_RESOLVED"
	printf 'BASE_SHA256=%q\n' "$BASE_SHA"
	printf 'BUILD_TIME=%q\n' "$BUILD_TIME"
	printf 'GIT_COMMIT=%q\n' "$GIT_COMMIT"
	printf 'TZ_NAME=%q\n' "$TZ_NAME"
	printf 'PACKAGES=%q\n' "$PACKAGES"
	printf 'APT_MIRROR=%q\n' "$APT_MIRROR"
	printf 'START_TIMEOUT=%q\n' "$START_TIMEOUT"
} > "$SEED/payload/build.env"
cat > "$SEED/meta-data" << 'EOF'
instance-id: tester-image-build
local-hostname: tester-image-build
EOF
cat > "$SEED/user-data" << 'EOF'
#cloud-config
# 이미지 빌드 전용(build-image.sh 가 만든다). seal.sh 가 cloud-init 상태를 지우므로 이미지에 남지 않는다.
# 빌드 VM 에는 로그인 계정을 만들지 않는다 — 기본 계정(ubuntu)은 OpenStack 인스턴스 첫 부팅 때 만들어진다.
users: []
disable_root: true
ssh_pwauth: false
package_update: false
package_upgrade: false
runcmd:
  - - /bin/bash
    - -c
    - >-
      mkdir -p /run/tester-seed &&
      mount -o ro LABEL=cidata /run/tester-seed &&
      exec /bin/bash /run/tester-seed/payload/guest/provision.sh /run/tester-seed
      || { echo 'TESTER-IMAGE-RESULT: FAIL 시드 ISO 를 마운트하지 못했습니다' > /dev/console; systemctl poweroff; }
EOF
make_iso "$SEED" cidata "$WORK_DIR/seed.iso"
info "seed.iso $(human_size "$(stat -c %s "$WORK_DIR/seed.iso")")"

# ---------------------------------------------------------------- 4. 빌드 VM
step "빌드 VM 부팅 — 설치 · 설정 · 검증 · 일반화 (제한 ${TIMEOUT}초, ${CPUS} vCPU, ${MEMORY}MB, $QEMU_ACCEL)"
CONSOLE="$WORK_DIR/console.log"
: > "$CONSOLE"
NETDEV="user,id=n0"
if [ "$OFFLINE" -eq 1 ]; then
	# 게스트 → 바깥 연결을 막는다(DHCP 는 된다). 빌드가 정말 네트워크 없이 되는지도 함께 보장한다.
	NETDEV="$NETDEV,restrict=on"
	info "네트워크 차단(--offline)"
fi
# cache=unsafe: 빌드 VM 이 정상 종료하고 qemu 가 끝날 때만 결과를 쓰므로 flush 를 생략해 빠르게 돈다.
"${QEMU_CMD[@]}" -smp "$CPUS" -m "$MEMORY" -no-reboot \
	-drive "file=$WORK_DIR/disk.qcow2,if=virtio,format=qcow2,discard=unmap,detect-zeroes=unmap,cache=unsafe" \
	-drive "file=$WORK_DIR/seed.iso,if=virtio,format=raw,readonly=on" \
	-netdev "$NETDEV" -device virtio-net-pci,netdev=n0,romfile= \
	-serial "file:$CONSOLE" &
QEMU_PID=$!
( sleep "$TIMEOUT"; touch "$WORK_DIR/timed-out"; kill "$QEMU_PID" 2> /dev/null ) &
WATCHDOG_PID=$!

follow_console "$CONSOLE" "$QEMU_PID"
qemu_rc=0
wait "$QEMU_PID" || qemu_rc=$?
QEMU_PID=""
kill "$WATCHDOG_PID" 2> /dev/null || true
WATCHDOG_PID=""
cp "$CONSOLE" "$OUT_DIR/$NAME.build-console.log"
give_back "$OUT_DIR/$NAME.build-console.log"

if [ -f "$WORK_DIR/timed-out" ]; then
	console_tail "$CONSOLE"
	die "빌드 VM 이 ${TIMEOUT}초 안에 끝나지 않았습니다(--timeout 으로 늘릴 수 있다). 전체 콘솔: $NAME.build-console.log"
fi
if ! grep -aq 'TESTER-IMAGE-RESULT: OK' "$CONSOLE"; then
	console_tail "$CONSOLE"
	reason="$(tr -d '\r' < "$CONSOLE" | grep -ao 'TESTER-IMAGE-RESULT: FAIL.*' | head -1 | sed 's/^TESTER-IMAGE-RESULT: FAIL //')"
	die "빌드 VM 안에서 실패했습니다: ${reason:-결과를 남기지 않고 꺼짐 (qemu exit $qemu_rc)}. 전체 콘솔: $NAME.build-console.log"
fi
info "빌드 VM 정상 종료"

# ---------------------------------------------------------------- 5. qcow2
step "qcow2 압축 (오버레이를 펼쳐 독립 이미지로)"
OUT_IMG="$OUT_DIR/$NAME.qcow2"
qemu-img convert -O qcow2 -c -o compat=1.1 "$WORK_DIR/disk.qcow2" "$OUT_IMG.part"
qemu-img check -q "$OUT_IMG.part" || die "qemu-img check 실패"
mv "$OUT_IMG.part" "$OUT_IMG"
SIZE=$(stat -c %s "$OUT_IMG")
VSIZE=$(qemu-img info --output=json "$OUT_IMG" | jq '."virtual-size"')
MIN_DISK=$(( (VSIZE + 1073741823) / 1073741824 ))
info "$(basename "$OUT_IMG") — $(human_size "$SIZE") (가상 ${MIN_DISK}GiB)"

step "체크섬 · 매니페스트"
SHA=$(sha256sum "$OUT_IMG" | awk '{print $1}')
echo "$SHA  $(basename "$OUT_IMG")" > "$OUT_IMG.sha256"
case "$ARCH" in amd64) OS_ARCH=x86_64 ;; arm64) OS_ARCH=aarch64 ;; esac
GUEST_AGENT=no
case " $PACKAGES " in *" qemu-guest-agent "*) GUEST_AGENT=yes ;; esac
jq -n \
	--arg name "$NAME" --arg file "$(basename "$OUT_IMG")" --arg sha "$SHA" \
	--argjson size "$SIZE" --argjson vsize "$VSIZE" --argjson min_disk "$MIN_DISK" \
	--arg arch "$ARCH" --arg os_arch "$OS_ARCH" --arg app "$APP_VERSION" --arg commit "$GIT_COMMIT" \
	--arg bundle "$(basename "$BUNDLE")" \
	--arg base_url "$BASE_URL" --arg base_serial "$BASE_SERIAL_RESOLVED" --arg base_sha "$BASE_SHA" \
	--arg time "$BUILD_TIME" --arg accel "$QEMU_ACCEL" --arg tz "$TZ_NAME" --arg packages "$PACKAGES" \
	--argjson offline "$([ "$OFFLINE" -eq 1 ] && echo true || echo false)" \
	--argjson seconds "$(( $(date +%s) - START_TS ))" \
	--arg qemu "$("${QEMU_CMD[0]}" --version | head -1)" --arg agent "$GUEST_AGENT" '
	{
		name: $name, file: $file, sha256: $sha, size_bytes: $size, virtual_size_bytes: $vsize,
		arch: $arch,
		os: { distro: "ubuntu", version: "24.04", codename: "noble" },
		app: { version: $app, git_commit: $commit, bundle: $bundle },
		base_image: { url: $base_url, serial: $base_serial, sha256: $base_sha },
		build: { time: $time, seconds: $seconds, accel: $accel, qemu: $qemu, timezone: $tz,
		         packages: ($packages | split(" ") | map(select(. != ""))), offline: $offline },
		glance: {
			disk_format: "qcow2", container_format: "bare", min_disk: $min_disk, min_ram: 2048,
			properties: ({
				os_type: "linux", os_distro: "ubuntu", os_version: "24.04", os_admin_user: "ubuntu",
				architecture: $os_arch, hw_disk_bus: "virtio", hw_vif_model: "virtio",
				tester_version: $app, tester_base_serial: $base_serial
			}
			+ (if $agent == "yes" then { hw_qemu_guest_agent: "yes" } else {} end)
			+ (if $arch == "arm64" then { hw_firmware_type: "uefi" } else {} end))
		}
	}' > "$OUT_DIR/$NAME.json"
give_back "$OUT_IMG" "$OUT_IMG.sha256" "$OUT_DIR/$NAME.json"
info "$NAME.qcow2.sha256  $SHA"
info "$NAME.json         (Glance 속성 · 베이스 이미지 · 빌드 정보)"

echo
echo "완료 ($(( ($(date +%s) - START_TS) / 60 ))분 $(( ($(date +%s) - START_TS) % 60 ))초): $OUT_IMG"
