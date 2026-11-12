#!/usr/bin/env bash
#
# 만든 qcow2 를 OpenStack(Glance)에 올린다. 매니페스트(.json)에 적힌 권장 속성을 함께 붙인다.
#
#   source ~/my-project-openrc.sh                 # 또는 export OS_CLOUD=mycloud (clouds.yaml)
#   ./deploy/openstack/upload.sh                  # 가장 최근 이미지
#   ./deploy/openstack/upload.sh --image FILE.qcow2 --name tester-1.0 --visibility shared
#   ./deploy/openstack/upload.sh --dry-run        # 실행할 명령만 보여 준다
#
# 호스트에 openstack CLI 가 있으면 그것을, 없으면 빌더 컨테이너 안의 것을 쓴다(OS_* 환경변수와
# ~/.config/openstack/clouds.yaml 을 넘겨 준다).

set -euo pipefail

# shellcheck source=lib/host.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/host.sh"

IMAGE=""
NAME=""
VISIBILITY=private
DRY_RUN=0
usage() { sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while [ $# -gt 0 ]; do
	case "$1" in
		--image) IMAGE="${2:?}"; shift 2 ;;
		--name) NAME="${2:?}"; shift 2 ;;
		--visibility) VISIBILITY="${2:?}"; shift 2 ;;
		--dry-run) DRY_RUN=1; shift ;;
		-h | --help) usage 0 ;;
		*) echo "알 수 없는 옵션: $1" >&2; usage 1 ;;
	esac
done

IMAGE="${IMAGE:-$(latest_image)}"
[ -n "$IMAGE" ] && [ -f "$IMAGE" ] || hdie "올릴 이미지가 없습니다. ./deploy/openstack/build.sh 로 먼저 만드세요."
IMAGE="$(cd "$(dirname "$IMAGE")" && pwd)/$(basename "$IMAGE")"
MANIFEST="${IMAGE%.qcow2}.json"
[ -f "$MANIFEST" ] || hdie "매니페스트가 없습니다: $MANIFEST"

# 매니페스트 읽기 — 호스트에 jq 가 없어도 되도록 python3 를 쓰고, 그것도 없으면 컨테이너에서 읽는다.
read_manifest() {
	local expr='import json,sys; m=json.load(open(sys.argv[1])); g=m["glance"]
print(m["name"]); print(m["build"]["time"][:10].replace("-","")); print(g["min_disk"]); print(g["min_ram"])
for k,v in sorted(g["properties"].items()): print(f"{k}={v}")'
	if command -v python3 > /dev/null 2>&1; then
		python3 -c "$expr" "$MANIFEST"
	else
		docker run --rm -v "$MANIFEST:/m.json:ro" "$BUILDER_IMAGE" python3 -c "$expr" /m.json
	fi
}
MANIFEST_LINES=()
while IFS= read -r line; do MANIFEST_LINES+=("$line"); done < <(read_manifest)
BASE_NAME="${MANIFEST_LINES[0]}"
BUILD_DATE="${MANIFEST_LINES[1]}"
MIN_DISK="${MANIFEST_LINES[2]}"
MIN_RAM="${MANIFEST_LINES[3]}"
NAME="${NAME:-$BASE_NAME-$BUILD_DATE}"

# 올리기 전에 파일이 매니페스트와 같은지 확인한다(옮기다 깨진 파일을 올리지 않게).
want="$(sed -n 's/.*"sha256": "\([0-9a-f]\{64\}\)".*/\1/p' "$MANIFEST" | head -1)"
if command -v sha256sum > /dev/null 2>&1; then got="$(sha256sum "$IMAGE" | awk '{print $1}')"; else got="$(shasum -a 256 "$IMAGE" | awk '{print $1}')"; fi
[ "$want" = "$got" ] || hdie "SHA256 이 매니페스트와 다릅니다: $IMAGE"

CMD=(openstack image create "$NAME"
	--disk-format qcow2 --container-format bare
	--min-disk "$MIN_DISK" --min-ram "$MIN_RAM"
	--"$VISIBILITY" --tag tester)
for prop in "${MANIFEST_LINES[@]:4}"; do CMD+=(--property "$prop"); done

echo "이미지   $IMAGE"
echo "이름     $NAME ($VISIBILITY, 최소 디스크 ${MIN_DISK}GB · 메모리 ${MIN_RAM}MB)"
echo "속성     ${MANIFEST_LINES[*]:4}"
if [ "$DRY_RUN" -eq 1 ]; then
	echo
	printf '%q ' "${CMD[@]}" --file "$IMAGE"
	echo
	exit 0
fi
env | grep -q '^OS_' || hdie "OpenStack 인증 정보가 없습니다. openrc 를 source 하거나 OS_CLOUD 를 지정하세요."

if command -v openstack > /dev/null 2>&1; then
	"${CMD[@]}" --file "$IMAGE" -f value -c id -c status > "$OUT_DIR/.upload-result"
else
	echo "(호스트에 openstack CLI 가 없어 빌더 컨테이너에서 실행합니다)"
	require_docker
	envfile="$(mktemp)"
	trap 'rm -f "$envfile"' EXIT
	env | grep '^OS_' > "$envfile"
	DOCKER_ARGS=(--rm --env-file "$envfile" -v "$(dirname "$IMAGE"):/img:ro")
	[ -d "$HOME/.config/openstack" ] && DOCKER_ARGS+=(-v "$HOME/.config/openstack:/root/.config/openstack:ro")
	[ -d /etc/openstack ] && DOCKER_ARGS+=(-v /etc/openstack:/etc/openstack:ro)
	if [ -n "${OS_CACERT:-}" ] && [ -f "$OS_CACERT" ]; then
		DOCKER_ARGS+=(-v "$OS_CACERT:/cacert.pem:ro" -e OS_CACERT=/cacert.pem)
	fi
	docker run "${DOCKER_ARGS[@]}" "$BUILDER_IMAGE" "${CMD[@]}" --file "/img/$(basename "$IMAGE")" -f value -c id -c status \
		> "$OUT_DIR/.upload-result"
fi
read -r IMAGE_ID IMAGE_STATUS < <(tr '\n' ' ' < "$OUT_DIR/.upload-result")
rm -f "$OUT_DIR/.upload-result"
echo
echo "올림: $IMAGE_ID ($IMAGE_STATUS)"
cat << EOF

서버 만들기 예 (보안 그룹에서 TCP 22·80 을 연다 — docs/openstack-image.md §5):
  openstack server create tester-01 --image $IMAGE_ID --flavor <플레이버> \\
    --network <네트워크> --key-name <키페어> --security-group <보안그룹>
EOF
