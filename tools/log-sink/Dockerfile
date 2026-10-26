# 로그 수신 테스트 서버.
#
# 순수 JDK API 만 쓰는 단일 파일이라 Gradle 없이 javac 로 바로 컴파일한다.
# 빌드 컨텍스트는 이 디렉터리 자신이다:
#
#   docker build -t tester-log-sink tools/log-sink/
#
# 보통은 이 디렉터리의 docker-compose.yml 로 띄운다: `docker compose up -d --build`

# ---------- 1단계: 컴파일 ----------
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /src
COPY LogSink.java .
RUN javac -d /out LogSink.java

# ---------- 2단계: 실행 ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /out/*.class ./

# 기본값. docker-compose.yml 이나 -e 로 바꿀 수 있다.
ENV TCP_PORT=9000 \
	UDP_PORT=5514 \
	HTTP_PORT=8090 \
	HTTP_INGEST_PORT=9880 \
	MAX_RECENT=50 \
	MAX_LINE_BYTES=8192

EXPOSE 9000/tcp 5514/udp 8090/tcp 9880/tcp

ENTRYPOINT ["java", "LogSink"]

# curl/wget 을 추가로 설치하지 않는다 — JDK 내장 HttpClient 로 자체 헬스체크 모드를 뒀다.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
	CMD ["java", "LogSink", "--healthcheck"]
