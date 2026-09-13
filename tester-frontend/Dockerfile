# 프론트엔드(tester-frontend) 이미지.
#
# 이 모듈은 다른 tester-* 모듈에 의존하지 않는 독립 npm 프로젝트이므로
# 빌드 컨텍스트는 이 디렉터리 자신이다:
#
#   docker build -t tester-frontend tester-frontend/
#
# nginx.conf 는 백엔드를 docker-compose 서비스명(backend:8080)으로 프록시한다.
# 컨테이너 없이 배포할 때는 이 이미지 대신 deploy/nginx.conf + frontend.zip 을 쓴다.

# 폐쇄망 빌드:
#   docker build -t 192.168.70.114:5000/tester-frontend:latest \
#     --build-arg NODE_IMAGE=192.168.70.114:5000/node:22-alpine \
#     --build-arg NGINX_IMAGE=192.168.70.114:5000/nginx:1.27-alpine \
#     --build-arg NPM_REGISTRY=https://test.com/repository/npm-public/ tester-frontend/

# ---------- 1단계: 빌드 ----------
ARG NODE_IMAGE=node:22-alpine
ARG NGINX_IMAGE=nginx:1.27-alpine

FROM ${NODE_IMAGE} AS builder
WORKDIR /app

# 사내 npm 미러. 비워 두면 registry.npmjs.org 를 쓴다.
ARG NPM_REGISTRY=""
RUN if [ -n "$NPM_REGISTRY" ]; then npm config set registry "$NPM_REGISTRY"; fi

# package.json 만 먼저 복사해 npm install 레이어를 캐시한다.
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; \
    else npm install --no-audit --no-fund; fi

COPY . .
RUN npm run build

# ---------- 2단계: 정적 서빙 ----------
FROM ${NGINX_IMAGE}

RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/tester.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=5 \
	CMD wget -qO- http://127.0.0.1/health || exit 1
