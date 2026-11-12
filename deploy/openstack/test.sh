#!/usr/bin/env bash
#
# 이미지 검증 하네스 — 만든 qcow2 를 빌더 컨테이너 안 QEMU 로 OpenStack 처럼 띄워 점검한다.
#
#   ./deploy/openstack/test.sh                       가장 최근 이미지(build/openstack/*.qcow2)
#   ./deploy/openstack/test.sh --image FILE.qcow2    지정한 이미지 (같은 이름의 .json 매니페스트가 옆에 있어야 한다)
#
# 옵션: --cpus N --memory MB (서버 한 대당, 기본 min(4, 코어 수) / 2048), --keep-work (작업 디스크 남김)
# 결과: build/openstack/harness/<이름>/report.txt · junit.xml · console-*.log. 실패가 있으면 exit 1.
# 점검 항목은 docs/openstack-image.md §6 에 있다.

set -euo pipefail

# shellcheck source=lib/host.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/host.sh"

IMAGE=""
BUILD_BUILDER=1
PASS_ARGS=()
usage() { sed -n '3,11p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while [ $# -gt 0 ]; do
	case "$1" in
		--image) IMAGE="${2:?}"; shift 2 ;;
		--no-builder-build) BUILD_BUILDER=0; shift ;;
		--cpus | --memory) PASS_ARGS+=("$1" "${2:?}"); shift 2 ;;
		--keep-work) PASS_ARGS+=("$1"); shift ;;
		-h | --help) usage 0 ;;
		*) echo "알 수 없는 옵션: $1" >&2; usage 1 ;;
	esac
done

require_docker
IMAGE="${IMAGE:-$(latest_image)}"
[ -n "$IMAGE" ] && [ -f "$IMAGE" ] || hdie "검증할 이미지가 없습니다. ./deploy/openstack/build.sh 로 먼저 만드세요."
IMAGE="$(cd "$(dirname "$IMAGE")" && pwd)/$(basename "$IMAGE")"
[ -f "${IMAGE%.qcow2}.json" ] || hdie "매니페스트가 없습니다: ${IMAGE%.qcow2}.json"

[ "$BUILD_BUILDER" -eq 1 ] && ensure_builder

# 결과물 디렉터리(/out) 밖의 이미지면 그 디렉터리를 따로 붙인다. 보고서는 늘 build/openstack/harness 에.
IMAGE_DIR="$(dirname "$IMAGE")"
MOUNTS=()
IN_PATH="/out/$(basename "$IMAGE")"
if [ "$IMAGE_DIR" != "$(cd "$OUT_DIR" 2> /dev/null && pwd)" ]; then
	MOUNTS=(-v "$IMAGE_DIR:/img:ro")
	IN_PATH="/img/$(basename "$IMAGE")"
fi
NAME="$(basename "$IMAGE" .qcow2)"
run_builder ${MOUNTS[@]+"${MOUNTS[@]}"} --name "tester-image-test-$$" -- \
	/opt/tester-image/harness/run.sh --image "$IN_PATH" --report-dir "/out/harness/$NAME" ${PASS_ARGS[@]+"${PASS_ARGS[@]}"}
