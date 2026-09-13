#!/usr/bin/env bash
# 내부 레지스트리용 이미지 빌드 + 푸시.
#
#   ./deploy/push-internal.sh                 # 인터넷에서 빌드해서 내부 레지스트리로 푸시
#   ./deploy/push-internal.sh --closed        # 폐쇄망 안에서 빌드(베이스 이미지/의존성도 내부 미러)
#   REGISTRY=harbor.test.com/tester TAG=1.2.0 ./deploy/push-internal.sh
#
# 푸시 후 내부망 서버에서:
#   docker compose -f docker-compose.internal.yml pull && docker compose -f docker-compose.internal.yml up -d
set -euo pipefail

REGISTRY="${REGISTRY:-192.168.70.114:5000}"
TAG="${TAG:-latest}"
# 폐쇄망 안에서 빌드할 때 쓰는 미러들. 환경변수로 덮어쓸 수 있다.
MAVEN_MIRROR="${TESTER_REPO_URL:-https://test.com/repository/maven-public/}"
NPM_MIRROR="${NPM_REGISTRY:-https://test.com/repository/npm-public/}"

CLOSED=0
[ "${1:-}" = "--closed" ] && CLOSED=1

cd "$(dirname "$0")/.."

backend_args=()
frontend_args=()
if [ "$CLOSED" = "1" ]; then
	echo "== 폐쇄망 모드: 베이스 이미지와 의존성을 내부 미러에서 받는다 =="
	backend_args+=(
		--build-arg "JDK_IMAGE=${REGISTRY}/eclipse-temurin:21-jdk-jammy"
		--build-arg "JRE_IMAGE=${REGISTRY}/eclipse-temurin:21-jre-jammy"
		--build-arg "TESTER_REPO_URL=${MAVEN_MIRROR}"
	)
	frontend_args+=(
		--build-arg "NODE_IMAGE=${REGISTRY}/node:22-alpine"
		--build-arg "NGINX_IMAGE=${REGISTRY}/nginx:1.27-alpine"
		--build-arg "NPM_REGISTRY=${NPM_MIRROR}"
	)
fi

echo "== 백엔드 빌드: ${REGISTRY}/tester-api:${TAG} =="
docker build -f tester-api/Dockerfile "${backend_args[@]}" \
	-t "${REGISTRY}/tester-api:${TAG}" .

echo "== 프론트엔드 빌드: ${REGISTRY}/tester-frontend:${TAG} =="
docker build "${frontend_args[@]}" \
	-t "${REGISTRY}/tester-frontend:${TAG}" tester-frontend/

echo "== 푸시 =="
docker push "${REGISTRY}/tester-api:${TAG}"
docker push "${REGISTRY}/tester-frontend:${TAG}"

echo
echo "완료. 내부망 서버에서:"
echo "  REGISTRY=${REGISTRY} TAG=${TAG} docker compose -f docker-compose.internal.yml pull"
echo "  REGISTRY=${REGISTRY} TAG=${TAG} docker compose -f docker-compose.internal.yml up -d"
