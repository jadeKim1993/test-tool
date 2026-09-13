# 백엔드(tester-api) 이미지.
#
# 멀티모듈 Gradle 프로젝트라 빌드 컨텍스트는 **저장소 루트**여야 한다
# (settings.gradle 과 모든 tester-* 모듈이 필요하다). 반드시 루트에서 빌드한다:
#
#   docker build -f tester-api/Dockerfile -t tester-api .
#
# docker-compose.yml 은 이 규칙에 맞춰 context: . / dockerfile: tester-api/Dockerfile 로 되어 있다.
#
# 폐쇄망 빌드(내부 레지스트리 + 사내 Maven 미러):
#   docker build -f tester-api/Dockerfile \
#     --build-arg JDK_IMAGE=192.168.70.114:5000/eclipse-temurin:21-jdk-jammy \
#     --build-arg JRE_IMAGE=192.168.70.114:5000/eclipse-temurin:21-jre-jammy \
#     --build-arg TESTER_REPO_URL=https://test.com/repository/maven-public/ \
#     -t 192.168.70.114:5000/tester-api:latest .

# ---------- 1단계: 빌드 ----------
ARG JDK_IMAGE=eclipse-temurin:21-jdk-jammy
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${JDK_IMAGE} AS builder
WORKDIR /workspace

# 사내 Maven 미러. 비워 두면 Maven Central 을 쓴다(settings.gradle 참고).
ARG TESTER_REPO_URL=""
ARG TESTER_REPO_USER=""
ARG TESTER_REPO_PASSWORD=""
ENV TESTER_REPO_URL=${TESTER_REPO_URL} \
	TESTER_REPO_USER=${TESTER_REPO_USER} \
	TESTER_REPO_PASSWORD=${TESTER_REPO_PASSWORD}

# 의존성 캐시를 살리기 위해 소스보다 먼저 빌드 스크립트만 복사한다.
COPY gradlew gradlew.bat gradle.properties settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY tester-common/build.gradle tester-common/
COPY tester-domain/build.gradle tester-domain/
COPY tester-generator/build.gradle tester-generator/
COPY tester-transport/build.gradle tester-transport/
COPY tester-api/build.gradle tester-api/
# settings.gradle 이 tester-frontend 도 서브프로젝트로 포함하므로, 소스를 넣지 않더라도
# Gradle 설정 단계가 "존재하지 않는 프로젝트 디렉터리"로 실패하지 않도록 build.gradle 만 넣어
# 디렉터리 자체는 있게 만든다(그 안의 npm 태스크는 등록만 되고 여기서 실행되지 않는다).
COPY tester-frontend/build.gradle tester-frontend/

# 여기서 한 번 의존성만 내려받아 레이어로 굳힌다. 소스만 바뀐 이후 빌드에서는
# 이 레이어가 캐시되어 매번 전체 의존성을 다시 받지 않는다.
RUN chmod +x gradlew && ./gradlew :tester-api:dependencies --configuration runtimeClasspath -q || true

# 소스 복사 후 실제 빌드. bootJar 는 test 에 의존하지 않으므로 이 단계에서
# 단위 테스트는 돌지 않는다(테스트/성능검증은 CI 나 `./gradlew build`/`perf` 로 별도 수행).
COPY tester-common/src tester-common/src
COPY tester-domain/src tester-domain/src
COPY tester-generator/src tester-generator/src
COPY tester-transport/src tester-transport/src
COPY tester-api/src tester-api/src

RUN ./gradlew :tester-api:bootJar -x test --no-daemon \
	&& mkdir -p /out \
	&& cp tester-api/build/libs/tester-api.jar /out/tester-api.jar

# HEALTHCHECK 용 최소 프로그램을 여기서 컴파일해 둔다(실행 이미지에는 JDK 가 없다).
COPY deploy/HealthCheck.java /workspace/HealthCheck.java
RUN javac -d /out/health /workspace/HealthCheck.java

# ---------- 2단계: 실행 ----------
FROM ${JRE_IMAGE}

# 컨테이너 안에서도 root 로 돌리지 않는다.
RUN groupadd --system tester && useradd --system --gid tester --home-dir /app tester

WORKDIR /app
COPY --from=builder /out/tester-api.jar app.jar
COPY --from=builder /out/health /app/health

# H2 파일 DB 위치(application.yml 의 jdbc:h2:file:./data/tester 가 여기 기준으로 풀린다).
# 이 디렉터리를 볼륨으로 마운트해야 컨테이너를 다시 만들어도 데이터가 남는다.
RUN mkdir -p /app/data && chown -R tester:tester /app

USER tester

EXPOSE 8080

# JVM 컨테이너 인식(-XX:+UseContainerSupport 는 21 기본 활성) 그대로 사용.
# TESTER_JWT_SECRET 은 실행 시 반드시 환경변수로 주입할 것(README 참고).
ENTRYPOINT ["java", "-jar", "app.jar"]

# curl/wget 대신 JDK 내장 HttpClient 를 쓴다 — 폐쇄망에서 apt 미러 없이도 이미지가 만들어진다.
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
	CMD ["java", "-cp", "/app/health", "HealthCheck"]
