# shellcheck shell=bash
#
# 빌더 컨테이너 안에서 build-image.sh 와 harness/run.sh 가 함께 쓰는 함수.
# (호스트 쪽 build.sh / test.sh 는 lib/host.sh 를 쓴다)

STEP=0
step() { STEP=$((STEP + 1)); echo; echo "== [$STEP] $*"; }
info() { echo "   $*"; }
warn() { echo "   ! $*" >&2; }
die() { echo; echo "오류: $*" >&2; exit 1; }

# 게스트 아키텍처(amd64|arm64) → QEMU 실행 인자. 결과는 전역 변수에 담는다.
#   QEMU_CMD   (배열) qemu-system-* 와 머신·가속·CPU·펌웨어 인자
#   QEMU_ACCEL kvm | tcg  (매니페스트·보고서에 남긴다)
#   GUEST_UNAME x86_64 | aarch64
#
# KVM 은 /dev/kvm 이 컨테이너에 들어와 있고 게스트와 호스트 아키텍처가 같을 때만 쓴다. 아니면 TCG
# (소프트웨어 에뮬레이션)로 돈다 — 느리지만 결과물은 같다. macOS 의 Docker Desktop 은 /dev/kvm 이 없어 항상 TCG 다.
qemu_setup() {
	local arch="$1" tb_size="${2:-256}"
	local machine cpu
	case "$arch" in
		amd64) GUEST_UNAME=x86_64; QEMU_CMD=(qemu-system-x86_64); machine="q35" ;;
		arm64) GUEST_UNAME=aarch64; QEMU_CMD=(qemu-system-aarch64); machine="virt" ;;
		*) die "지원하지 않는 아키텍처: $arch (amd64 | arm64)" ;;
	esac
	if [ "$(uname -m)" = "$GUEST_UNAME" ] && [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
		QEMU_ACCEL=kvm
		cpu=host
		QEMU_CMD+=(-machine "$machine" -accel kvm)
	else
		QEMU_ACCEL=tcg
		cpu=max
		# 번역 캐시 기본값(1GiB)은 VM 두 대를 함께 띄우는 하네스에서 Docker VM 메모리를 압박한다.
		QEMU_CMD+=(-machine "$machine" -accel "tcg,thread=multi,tb-size=$tb_size")
	fi
	QEMU_CMD+=(-cpu "$cpu" -nodefaults -display none -device virtio-rng-pci)
	if [ "$arch" = "arm64" ]; then
		local fw=/usr/share/qemu-efi-aarch64/QEMU_EFI.fd
		[ -f "$fw" ] || die "arm64 UEFI 펌웨어가 없습니다: $fw"
		QEMU_CMD+=(-bios "$fw")
	fi
}

# 디렉터리 → ISO9660(Joliet+RockRidge). label 이 cloud-init 데이터소스를 정한다:
#   cidata   → NoCloud (이미지 빌드용 시드)
#   config-2 → ConfigDrive (OpenStack Nova 의 config drive 와 같은 형식 — 하네스가 OpenStack 을 흉내 낼 때)
make_iso() {
	local dir="$1" label="$2" out="$3"
	local log
	log="$(mktemp)"
	# xorriso 는 -quiet 여도 배너를 stderr 로 찍는다 — 실패했을 때만 보여 준다.
	xorriso -as mkisofs -quiet -volid "$label" -joliet -rock -output "$out" "$dir" 2> "$log" \
		|| { cat "$log" >&2; rm -f "$log"; die "ISO 생성 실패: $out"; }
	rm -f "$log"
}

# 시리얼 콘솔 로그에서 사람이 볼 줄만 골라 보여 준다(qemu 가 끝나면 함께 끝난다).
#   [tester-image] … : 게스트 스크립트(provision/seal)가 남기는 진행 상황
#   Cloud-init v. …  : cloud-init 단계 시작/끝
follow_console() {
	local log="$1" pid="$2"
	# 파이프 각 단계가 줄 단위로 흘려보내야 실시간으로 보인다(tr 은 버퍼링하므로 쓰지 않는다).
	tail -n +1 -F --pid="$pid" "$log" 2>/dev/null \
		| grep -a --line-buffered -oE '\[tester-image\].*|TESTER-IMAGE-RESULT:.*|Cloud-init v\. [0-9.]+[^ ]* (running|finished) .*' \
		| sed -u 's/\r//g; s/^/   │ /' || true
}

# 콘솔 로그 끝부분 — 실패했을 때 원인을 보여 주는 용도. 제어 문자와 systemd 진행 표시(스피너)는 뺀다.
# 게스트 스크립트가 남긴 [tester-image] 줄(실패 직전 서비스 로그 포함)을 먼저, 그다음 원래 콘솔 끝부분을 보여 준다.
console_tail() {
	local log="$1" n="${2:-40}" clean
	clean="$(tr -d '\r' < "$log" | sed 's/\x1b\[[0-9;?]*[A-Za-z]//g' | grep -av '^\s*$' | grep -avE 'Job .* running \(|A stop job is running')"
	if grep -aq '\[tester-image\]' <<< "$clean"; then
		echo "   ── 게스트 스크립트 마지막 기록 ──"
		grep -a '\[tester-image\]' <<< "$clean" | tail -n 50 | sed 's/^/   │ /'
	fi
	echo "   ── 콘솔 마지막 ${n}줄 ($log) ──"
	tail -n "$n" <<< "$clean" | sed 's/^/   │ /'
}

# 숫자 바이트 → 사람이 읽는 크기
human_size() {
	awk -v b="$1" 'BEGIN { split("B KB MB GB TB", u); i = 1; while (b >= 1024 && i < 5) { b /= 1024; i++ } printf "%.1f%s", b, u[i] }'
}

# 컨테이너는 root 로 돈다. 호스트 사용자 소유로 돌려준다(리눅스 호스트에서 build/ 가 root 소유가 되지 않게).
give_back() {
	if [ -n "${HOST_UID:-}" ] && [ -n "${HOST_GID:-}" ]; then
		chown -R "$HOST_UID:$HOST_GID" "$@" 2>/dev/null || true
	fi
}
