#!/usr/bin/env bash
# 호스트에서 빌드 → 이미지에 담기 → 내부 레지스트리로 푸시.
#
#   ./deploy/push-internal.sh              # 빌드 + 이미지 + 푸시
#   ./deploy/push-internal.sh --no-push    # 이미지까지만(푸시 생략)
#   ./deploy/push-internal.sh --skip-build # 이미 빌드된 jar/dist 를 그대로 사용
#
# 미러·레지스트리 값은 **따로 입력하지 않는다.** deploy/internal-config.sh 가
# gradle.properties(의존성 미러)와 .env(레지스트리)에서 읽는다 —
# 즉 `./deploy/use-internal-mirror.sh` 를 한 번 돌려 두면 여기서는 아무것도 안 줘도 된다.
# 일회성으로 바꾸고 싶을 때만 REGISTRY=... TAG=... 처럼 환경변수로 덮어쓴다.
#
# 푸시 후 내부망 서버에서:
#   docker compose -f docker-compose.internal.yml pull && docker compose -f docker-compose.internal.yml up -d
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=deploy/internal-config.sh
. deploy/internal-config.sh

PUSH=1
BUILD=1
for arg in "$@"; do
	case "$arg" in
		--no-push) PUSH=0 ;;
		--skip-build) BUILD=0 ;;
		*) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
	esac
done

echo "== 설정 =="
print_internal_config
if [ "$INTERNAL_MODE" = "1" ]; then
	echo "  → 내부망 모드: 호스트 빌드가 위 미러를 사용하고, 베이스 이미지도 레지스트리에서 받는다"
fi
echo

if [ "$BUILD" = "1" ]; then
	# 이미지 안에서 빌드하지 않는다. 여기서 만든 산출물을 그대로 담는다.
	echo "== 호스트 빌드 (jar + dist) =="
	./gradlew :tester-api:bootJar :tester-frontend:npmBuild
	echo
fi

for artifact in tester-api/build/libs/tester-api.jar tester-frontend/dist/index.html; do
	[ -e "$artifact" ] || { echo "빌드 산출물이 없다: $artifact (--skip-build 를 뺐는지 확인)" >&2; exit 1; }
done

# 폐쇄망에서는 베이스 이미지도 내부 레지스트리에서 받는다.
backend_args=()
frontend_args=()
if [ "$INTERNAL_MODE" = "1" ]; then
	backend_args+=(--build-arg "JRE_IMAGE=${REGISTRY}/eclipse-temurin:21-jre-jammy")
	frontend_args+=(--build-arg "NGINX_IMAGE=${REGISTRY}/nginx:1.27-alpine")
fi

echo "== 이미지 =="
docker build ${backend_args[@]+"${backend_args[@]}"} -t "${REGISTRY}/tester-api:${TAG}" tester-api/
docker build ${frontend_args[@]+"${frontend_args[@]}"} -t "${REGISTRY}/tester-frontend:${TAG}" tester-frontend/

if [ "$PUSH" = "0" ]; then
	echo
	echo "이미지 생성 완료(푸시 생략):"
	echo "  ${REGISTRY}/tester-api:${TAG}"
	echo "  ${REGISTRY}/tester-frontend:${TAG}"
	exit 0
fi

echo
echo "== 푸시 =="
docker push "${REGISTRY}/tester-api:${TAG}"
docker push "${REGISTRY}/tester-frontend:${TAG}"

echo
echo "완료. 내부망 서버에서:"
echo "  docker compose -f docker-compose.internal.yml pull"
echo "  docker compose -f docker-compose.internal.yml up -d"
