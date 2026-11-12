#!/usr/bin/env bash
#
# 원클릭 OpenStack 이미지 빌드 — Ubuntu 24.04 + 테스트 도구 qcow2. 호스트에는 Docker 만 있으면 된다.
#
#   ./deploy/openstack/build.sh                  빌더 이미지 → 설치본 → qcow2(컨테이너 안 QEMU) → 하네스 검증
#   ./deploy/openstack/build.sh --no-test        검증 생략
#   ./deploy/openstack/build.sh --skip-bundle    이미 만든 설치본(build/distributions) 그대로 사용
#
# 설치본(jar·화면)은 호스트에 JDK·npm 이 있으면 호스트에서, 없으면 빌더 컨테이너 안(JDK 21 + Node 24)에서 만든다.
#
# 결과 (build/openstack/)
#   tester-<버전>-ubuntu-24.04-<arch>.qcow2          OpenStack 에 올릴 이미지
#   tester-<버전>-ubuntu-24.04-<arch>.qcow2.sha256   체크섬
#   tester-<버전>-ubuntu-24.04-<arch>.json           매니페스트(Glance 속성·베이스 이미지·빌드 정보)
#   harness/<이름>/report.txt, junit.xml            검증 결과
# 올리기: 대시보드(Horizon)에서 직접 — 끝에 입력값을 보여 준다. CLI 는 ./deploy/openstack/upload.sh
# 정리:   ./deploy/openstack/clean.sh
#
# 옵션
#   --arch amd64|arm64     게스트 아키텍처 (기본 amd64)
#   --bundle FILE          이 설치본 사용
#   --skip-bundle          설치본을 다시 만들지 않는다(build/distributions 의 것을 쓴다)
#   --bundle-in-docker     호스트에 JDK·npm 이 있어도 설치본을 빌더 컨테이너에서 만든다
#   --bundle-on-host       설치본을 반드시 호스트에서 만든다(JDK 21·npm 필요)
#   --no-test              하네스 검증 생략
#   --base-image FILE      베이스 클라우드 이미지 파일 직접 지정 (인터넷이 없는 빌드 장비)
#   그 밖의 옵션은 빌더(builder/build-image.sh)로 넘긴다:
#     --disk-size 8G  --timezone Asia/Seoul  --packages "qemu-guest-agent"  --offline  --apt-mirror URL
#     --base-serial 20261017  --base-url-root URL  --cpus N  --memory MB  --timeout SEC  --name NAME  --keep-work
#
# 환경변수: TESTER_BUILDER_UBUNTU_IMAGE / TESTER_BUILDER_APT_MIRROR (빌더 이미지를 폐쇄망에서 만들 때),
#           TESTER_BUILDER_SKIP_BUILD=1 (docker load 로 들여온 빌더 이미지를 그대로 쓸 때),
#           TESTER_REPO_URL / NPM_REGISTRY / TESTER_JRE_URL / TESTER_NODE_URL (설치본 빌드용 사내 미러)

set -euo pipefail

# shellcheck source=lib/host.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/host.sh"

ARCH=amd64
BUNDLE=""
SKIP_BUNDLE=0
BUNDLE_WHERE=auto
RUN_TEST=1
BASE_IMAGE=""
PASS_ARGS=()

usage() { sed -n '3,36p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while [ $# -gt 0 ]; do
	case "$1" in
		--arch) ARCH="${2:?}"; shift 2 ;;
		--bundle) BUNDLE="${2:?}"; shift 2 ;;
		--skip-bundle) SKIP_BUNDLE=1; shift ;;
		--bundle-in-docker) BUNDLE_WHERE=docker; shift ;;
		--bundle-on-host) BUNDLE_WHERE=host; shift ;;
		--no-test) RUN_TEST=0; shift ;;
		--base-image) BASE_IMAGE="${2:?}"; shift 2 ;;
		--offline | --keep-work) PASS_ARGS+=("$1"); shift ;;
		--disk-size | --timezone | --packages | --apt-mirror | --base-serial | --base-url-root | --cpus | --memory | --timeout | --name)
			PASS_ARGS+=("$1" "${2-}"); shift 2 ;;
		-h | --help) usage 0 ;;
		*) echo "알 수 없는 옵션: $1" >&2; usage 1 ;;
	esac
done
case "$ARCH" in
	amd64) BUNDLE_ARCH=x64 ;;
	arm64) BUNDLE_ARCH=aarch64 ;;
	*) hdie "--arch 는 amd64 또는 arm64" ;;
esac

T0=$(date +%s)
require_docker

# 빌드 중 디스크 사용: 결과물·캐시(저장소 쪽) 약 2GB, Docker 쪽(빌더 이미지 + 작업 디스크) 약 5GB.
mkdir -p "$OUT_DIR"
FREE_KB="$(df -Pk "$OUT_DIR" | awk 'NR == 2 {print $4}')"
if [ -n "$FREE_KB" ] && [ "$FREE_KB" -lt $((8 * 1024 * 1024)) ]; then
	echo "경고: $(dirname "$OUT_DIR") 쪽 남은 공간이 $((FREE_KB / 1024))MB 입니다. 빌드에 약 8GB 가 필요합니다(정리: ./deploy/openstack/clean.sh)." >&2
fi

# ---------------------------------------------------------------- 1. 빌더 이미지
hstep "1/4 빌더 컨테이너 이미지 (QEMU · qemu-img · xorriso · JDK 21 · Node)"
ensure_builder

# ---------------------------------------------------------------- 2. 오프라인 설치본
hstep "2/4 오프라인 설치본 ($BUNDLE_ARCH)"
if [ -z "$BUNDLE" ]; then
	if [ "$SKIP_BUNDLE" -eq 0 ]; then
		if [ "$BUNDLE_WHERE" = auto ]; then
			if host_can_build_bundle; then BUNDLE_WHERE=host; else BUNDLE_WHERE=docker; fi
		fi
		if [ "$BUNDLE_WHERE" = host ]; then
			host_can_build_bundle || hdie "--bundle-on-host: 호스트에 JDK 21 과 npm 이 필요합니다."
			echo "  호스트 JDK·npm 으로 빌드"
			(cd "$REPO_DIR" && ./gradlew --console=plain offlineBundle "-PbundleArch=$BUNDLE_ARCH")
		else
			echo "  빌더 컨테이너(JDK 21 · Node)에서 빌드 — 처음 한 번은 Gradle·npm 의존성을 받느라 몇 분 걸린다"
			run_gradle_in_builder offlineBundle "-PbundleArch=$BUNDLE_ARCH"
		fi
	fi
	BUNDLE="$(find "$REPO_DIR/build/distributions" -maxdepth 1 -name "tester-offline-*-linux-$BUNDLE_ARCH.tar.gz" -print0 2> /dev/null \
		| xargs -0 ls -t 2> /dev/null | head -1 || true)"
	[ -n "$BUNDLE" ] || hdie "설치본이 없습니다(build/distributions). --skip-bundle 없이 실행하세요."
fi
[ -f "$BUNDLE" ] || hdie "설치본이 없습니다: $BUNDLE"
BUNDLE="$(cd "$(dirname "$BUNDLE")" && pwd)/$(basename "$BUNDLE")"
echo "  $BUNDLE"

# ---------------------------------------------------------------- 3. qcow2
hstep "3/4 qcow2 빌드 (컨테이너 안 QEMU)"
MOUNTS=(-v "$(dirname "$BUNDLE"):/in:ro")
BUILD_ARGS=(--bundle "/in/$(basename "$BUNDLE")" --arch "$ARCH")
if [ -n "$BASE_IMAGE" ]; then
	[ -f "$BASE_IMAGE" ] || hdie "베이스 이미지가 없습니다: $BASE_IMAGE"
	BASE_IMAGE="$(cd "$(dirname "$BASE_IMAGE")" && pwd)/$(basename "$BASE_IMAGE")"
	MOUNTS+=(-v "$(dirname "$BASE_IMAGE"):/base:ro")
	BUILD_ARGS+=(--base-image "/base/$(basename "$BASE_IMAGE")")
fi
GIT_COMMIT=unknown
if GIT_COMMIT="$(git -C "$REPO_DIR" rev-parse --short HEAD 2> /dev/null)"; then
	git -C "$REPO_DIR" diff --quiet HEAD 2> /dev/null || GIT_COMMIT="$GIT_COMMIT-dirty"
else
	GIT_COMMIT=unknown
fi
run_builder "${MOUNTS[@]}" -e "GIT_COMMIT=$GIT_COMMIT" --name "tester-image-build-$$" -- \
	/opt/tester-image/builder/build-image.sh "${BUILD_ARGS[@]}" ${PASS_ARGS[@]+"${PASS_ARGS[@]}"}

IMAGE="$(latest_image)"
[ -n "$IMAGE" ] || hdie "결과 이미지를 찾지 못했습니다($OUT_DIR)"
MANIFEST="${IMAGE%.qcow2}.json"

# ---------------------------------------------------------------- 4. 검증
if [ "$RUN_TEST" -eq 1 ]; then
	hstep "4/4 하네스 검증 (OpenStack config drive 형식으로 서버 두 대를 차례로 부팅)"
	"$OPENSTACK_DIR/test.sh" --image "$IMAGE" --no-builder-build
else
	hstep "4/4 하네스 검증 — 생략(--no-test). 나중에: ./deploy/openstack/test.sh"
fi

ELAPSED=$(( $(date +%s) - T0 ))
echo
echo "완료 ($((ELAPSED / 60))분 $((ELAPSED % 60))초)"
echo "  이미지     $IMAGE"
echo "  체크섬     $IMAGE.sha256"
echo "  매니페스트 $MANIFEST"
echo
# 대시보드(Horizon) 이미지 만들기 화면에 넣을 값 — 매니페스트에서 읽는다(빌더 컨테이너의 jq, 호스트에 jq 가 없어도 된다).
docker run --rm -v "$MANIFEST:/m.json:ro" "$BUILDER_IMAGE" jq -r '
	"대시보드(Horizon) → 프로젝트 → 컴퓨트 → 이미지 → 이미지 생성",
	"  이미지 이름     \(.name)",
	"  파일           \(.file)",
	"  포맷           QCOW2 - QEMU 에뮬레이터",
	"  아키텍처       \(.glance.properties.architecture)",
	"  최소 디스크(GB) \(.glance.min_disk)",
	"  최소 RAM(MB)   \(.glance.min_ram)",
	"  메타데이터(사용자 지정 속성):",
	(.glance.properties | to_entries[] | select(.key != "architecture") | "    \(.key) = \(.value)"),
	"자세한 절차: docs/openstack-image.md §5.4"' /m.json
