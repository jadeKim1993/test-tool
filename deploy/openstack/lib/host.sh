# shellcheck shell=bash
#
# 호스트에서 build.sh · test.sh · upload.sh 가 함께 쓰는 함수. 호스트에는 Docker 만 있으면 된다.

OPENSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$OPENSTACK_DIR/../.." && pwd)"
OUT_DIR="${TESTER_IMAGE_OUT:-$REPO_DIR/build/openstack}"
CACHE_DIR="${TESTER_IMAGE_CACHE:-$REPO_DIR/.runtime-cache/openstack}"
BUILDER_IMAGE="${TESTER_BUILDER_IMAGE:-tester-image-builder:latest}"

hdie() { echo "오류: $*" >&2; exit 1; }
hstep() { echo; echo "■ $*"; }

require_docker() {
	command -v docker > /dev/null 2>&1 || hdie "docker 명령이 없습니다. Docker 를 설치하세요."
	docker info > /dev/null 2>&1 || hdie "Docker 데몬에 연결할 수 없습니다. 데몬이 떠 있는지, 권한이 있는지 확인하세요
       (리눅스: sudo usermod -aG docker \$USER 후 다시 로그인, 또는 sudo 로 실행)."
}

# 빌더 이미지(QEMU 등). 바뀐 게 없으면 캐시로 몇 초 만에 끝난다.
#   TESTER_BUILDER_UBUNTU_IMAGE  베이스(기본 ubuntu:24.04) — 폐쇄망이면 내부 레지스트리 경로
#   TESTER_BUILDER_APT_MIRROR    apt 미러
#   TESTER_BUILDER_SKIP_BUILD=1  이미 있는 이미지를 그대로 쓴다(docker load 로 들여온 경우)
ensure_builder() {
	if [ "${TESTER_BUILDER_SKIP_BUILD:-0}" = 1 ]; then
		docker image inspect "$BUILDER_IMAGE" > /dev/null 2>&1 || hdie "빌더 이미지 $BUILDER_IMAGE 가 없습니다."
		echo "  빌더 이미지 그대로 사용: $BUILDER_IMAGE"
		return
	fi
	local args=(-t "$BUILDER_IMAGE")
	[ -n "${TESTER_BUILDER_UBUNTU_IMAGE:-}" ] && args+=(--build-arg "UBUNTU_IMAGE=$TESTER_BUILDER_UBUNTU_IMAGE")
	[ -n "${TESTER_BUILDER_APT_MIRROR:-}" ] && args+=(--build-arg "APT_MIRROR=$TESTER_BUILDER_APT_MIRROR")
	local log
	log="$(mktemp)"
	if ! docker build "${args[@]}" "$OPENSTACK_DIR/docker" > "$log" 2>&1; then
		tail -n 30 "$log" >&2
		rm -f "$log"
		hdie "빌더 이미지 빌드 실패"
	fi
	rm -f "$log"
	echo "  $BUILDER_IMAGE ($(docker image inspect -f '{{.Size}}' "$BUILDER_IMAGE" | awk '{printf "%.0fMB", $1/1e6}'))"
}

# 빌더 컨테이너에서 명령 실행. deploy/openstack 은 읽기 전용으로, 결과물·캐시 디렉터리는 쓰기로 붙인다.
#   run_builder [docker run 추가 인자…] -- 명령 [인자…]
run_builder() {
	local extra=()
	while [ $# -gt 0 ] && [ "$1" != "--" ]; do extra+=("$1"); shift; done
	[ "${1:-}" = "--" ] && shift
	mkdir -p "$OUT_DIR" "$CACHE_DIR"
	local args=(--rm --init
		-v "$OPENSTACK_DIR:/opt/tester-image:ro"
		-v "$OUT_DIR:/out"
		-v "$CACHE_DIR:/cache"
		-e "HOST_UID=$(id -u)" -e "HOST_GID=$(id -g)")
	[ -t 0 ] && [ -t 1 ] && args+=(-it)
	# 리눅스 호스트에 KVM 이 있으면 넘긴다 — 같은 아키텍처 게스트는 수십 배 빨라진다.
	# 컨테이너 안에서는 root 로 열므로 호스트 사용자가 kvm 그룹이 아니어도 된다.
	if [ -e /dev/kvm ]; then
		args+=(--device /dev/kvm)
	fi
	docker run "${args[@]}" "${extra[@]}" "$BUILDER_IMAGE" "$@"
}

# 빌더 컨테이너 안에서 Gradle 실행 — 호스트에 JDK·npm 이 없을 때 오프라인 설치본을 여기서 만든다.
#   run_gradle_in_builder <gradle 인자…>
# 저장소를 호스트와 같은 절대 경로로 붙이고 호스트 사용자 UID 로 돌린다(만든 파일이 root 소유가 되지 않게).
# Gradle·npm 캐시는 .runtime-cache/gradle-docker 에 둔다(다음 빌드가 빨라진다, clean.sh 로 지운다).
# 사내 미러 설정(TESTER_REPO_URL 등)은 환경변수로 넘긴다 — gradle.properties 에 적은 값은 저장소째 보인다.
run_gradle_in_builder() {
	local cache="$REPO_DIR/.runtime-cache/gradle-docker" env_args=() name
	mkdir -p "$cache/npm"
	for name in TESTER_REPO_URL TESTER_REPO_USER TESTER_REPO_PASSWORD NPM_REGISTRY TESTER_GRADLE_DIST_URL TESTER_JRE_URL TESTER_NODE_URL; do
		[ -n "${!name:-}" ] && env_args+=(-e "$name")
	done
	local args=(--rm --init
		--user "$(id -u):$(id -g)"
		-v "$REPO_DIR:$REPO_DIR" -w "$REPO_DIR"
		-e HOME=/tmp -e "GRADLE_USER_HOME=$cache" -e "npm_config_cache=$cache/npm"
		${env_args[@]+"${env_args[@]}"})
	[ -t 0 ] && [ -t 1 ] && args+=(-it)
	docker run "${args[@]}" "$BUILDER_IMAGE" ./gradlew --no-daemon --console=plain "$@"
}

# 호스트에서 설치본을 만들 수 있는가 — Java 21(빌드의 Java 툴체인이 21 이다)과 Node 22+·npm(프론트엔드 빌드).
# java 가 실제로 실행되는지 본다 — macOS 는 JDK 가 없어도 /usr/bin/java 가 있다. 애매하면 컨테이너에서 만든다(--bundle-on-host 로 강제).
host_can_build_bundle() {
	local java_major node_major
	java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)" || return 1
	node_major="$(node --version 2> /dev/null | sed -n 's/^v\([0-9]*\).*/\1/p')" || return 1
	[ "${java_major:-0}" = 21 ] && [ "${node_major:-0}" -ge 22 ] && command -v npm > /dev/null 2>&1
}

# 가장 최근에 만든 이미지 (build/openstack/*.qcow2)
latest_image() {
	find "$OUT_DIR" -maxdepth 1 -name '*.qcow2' -type f -print0 2> /dev/null | xargs -0 ls -t 2> /dev/null | head -1
}
