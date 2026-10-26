# 프론트엔드(tester-frontend) 이미지.
#
# **이미지 안에서 빌드하지 않는다.** 호스트에서 만든 dist/ 를 nginx 에 담기만 한다:
#
#   ./gradlew :tester-frontend:npmBuild     (또는 npm run build)
#   docker build -t tester-frontend tester-frontend/
#
# 이렇게 바꾼 이유는 백엔드 이미지와 같다 — 폐쇄망에서 이미지 빌드용 npm 미러가 필요 없고,
# 호스트에서 검증한 산출물이 그대로 배포된다(tester-api/Dockerfile 주석 참고).
#
# 폐쇄망(베이스 이미지도 내부 레지스트리에서):
#   docker build --build-arg NGINX_IMAGE=192.168.70.114:5000/nginx:1.27-alpine \
#     -t 192.168.70.114:5000/tester-frontend:latest tester-frontend/
#
# nginx.conf 는 백엔드를 docker-compose 서비스명(backend:8080)으로 프록시한다.
# 컨테이너 없이 배포할 때는 오프라인 설치본(./gradlew offlineBundle, README §내부망 오프라인 설치)을 쓴다.

ARG NGINX_IMAGE=nginx:1.27-alpine
FROM ${NGINX_IMAGE}

RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/tester.conf

# 호스트 빌드 산출물(vite build 결과).
ARG DIST_DIR=dist
COPY ${DIST_DIR}/ /usr/share/nginx/html/

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=5 \
	CMD wget -qO- http://127.0.0.1/health || exit 1
