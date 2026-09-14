# 백엔드(tester-api) 이미지.
#
# **이미지 안에서 빌드하지 않는다.** 호스트에서 만든 jar 를 담기만 한다:
#
#   ./gradlew :tester-api:bootJar
#   docker build -t tester-api tester-api/
#
# 빌드 컨텍스트는 이 디렉터리(tester-api/) 다 — 예전처럼 저장소 루트일 필요가 없다.
# 이렇게 바꾼 이유:
#   - 폐쇄망에서 이미지 빌드용 Maven/npm 미러를 따로 챙길 필요가 없다(호스트 빌드가 이미 함).
#   - 같은 jar 를 테스트하고 그대로 배포한다 — 이미지 안에서 다시 빌드하면 산출물이 달라질 수 있다.
#   - 빌드 시간이 몇 분에서 몇 초로 줄어든다.
#
# 폐쇄망(베이스 이미지도 내부 레지스트리에서):
#   docker build --build-arg JRE_IMAGE=192.168.70.114:5000/eclipse-temurin:21-jre-jammy \
#     -t 192.168.70.114:5000/tester-api:latest tester-api/

ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${JRE_IMAGE}

# 컨테이너 안에서도 root 로 돌리지 않는다.
RUN groupadd --system tester && useradd --system --gid tester --home-dir /app tester

WORKDIR /app

# 호스트 빌드 산출물. 경로를 바꾸고 싶으면 --build-arg JAR_FILE=... 로 넘긴다.
ARG JAR_FILE=build/libs/tester-api.jar
COPY ${JAR_FILE} app.jar

# H2 파일 DB 위치(application.yml 의 jdbc:h2:file:./data/tester 가 여기 기준으로 풀린다).
# 이 디렉터리를 볼륨으로 마운트해야 컨테이너를 다시 만들어도 데이터가 남는다.
RUN mkdir -p /app/data && chown -R tester:tester /app

USER tester

EXPOSE 8080

# JVM 컨테이너 인식(-XX:+UseContainerSupport 는 21 기본 활성) 그대로 사용.
# TESTER_JWT_SECRET 은 실행 시 반드시 환경변수로 주입할 것(README 참고).
ENTRYPOINT ["java", "-jar", "app.jar"]

# curl/wget 대신 애플리케이션 자신의 --healthcheck 모드를 쓴다(TesterApplication.main).
# 이미지에 apt 레이어를 하나도 더하지 않기 위한 선택이라, 폐쇄망에서도 그대로 빌드된다.
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
	CMD ["java", "-jar", "app.jar", "--healthcheck"]
