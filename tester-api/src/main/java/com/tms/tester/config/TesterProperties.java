package com.tms.tester.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 애플리케이션 설정. {@code application.yml} 의 {@code tester.*} 에 대응한다.
 * <p>확장 지점: 새 설정은 여기에 필드를 추가하면 자동으로 바인딩된다.
 */
@ConfigurationProperties(prefix = "tester")
public class TesterProperties {

	private final Jwt jwt = new Jwt();
	private final Engine engine = new Engine();
	private final Security security = new Security();

	public Jwt getJwt() {
		return jwt;
	}

	public Engine getEngine() {
		return engine;
	}

	public Security getSecurity() {
		return security;
	}

	public static class Jwt {

		/**
		 * HS256 서명키. 비워 두면 {@link #secretFile} 에 만들어 둔 키를 쓴다(없으면 만들어 저장한다).
		 */
		private String secret;

		/**
		 * 서명키를 지정하지 않았을 때 생성한 키를 보관하는 파일. H2 DB 와 같은 data 폴더라 개발 실행·Docker 볼륨·
		 * 오프라인 설치 모두에서 재시작해도 남는다. 예전처럼 매번 새 키를 만들면 재시작마다 모두 로그아웃됐다.
		 */
		private String secretFile = "./data/jwt-secret";

		/** 토큰 유효기간. */
		private Duration ttl = Duration.ofHours(12);

		/** 만료가 이 시간 이내로 남으면 응답 헤더로 새 토큰을 내려준다. */
		private Duration refreshThreshold = Duration.ofHours(2);

		public String getSecret() {
			return secret;
		}

		public void setSecret(String secret) {
			this.secret = secret;
		}

		public String getSecretFile() {
			return secretFile;
		}

		public void setSecretFile(String secretFile) {
			this.secretFile = secretFile;
		}

		public Duration getTtl() {
			return ttl;
		}

		public void setTtl(Duration ttl) {
			this.ttl = ttl;
		}

		public Duration getRefreshThreshold() {
			return refreshThreshold;
		}

		public void setRefreshThreshold(Duration refreshThreshold) {
			this.refreshThreshold = refreshThreshold;
		}
	}

	public static class Engine {

		/** Netty I/O 스레드 수. 0 이면 CPU 코어 수. */
		private int ioThreads;

		/** 동시에 실행 가능한 전송 작업 수. */
		private int maxConcurrentJobs = 20;

		/** 전체 작업의 EPS 합계 상한(안전장치). 0 이면 무제한. */
		private int maxTotalEps = 1_000_000;

		/** 메트릭 샘플링 주기. */
		private Duration metricsInterval = Duration.ofSeconds(1);

		/** 메모리에 보관할 샘플 개수(기본 1800 = 30분). */
		private int historySize = 1800;

		public int getIoThreads() {
			return ioThreads;
		}

		public void setIoThreads(int ioThreads) {
			this.ioThreads = ioThreads;
		}

		public int getMaxConcurrentJobs() {
			return maxConcurrentJobs;
		}

		public void setMaxConcurrentJobs(int maxConcurrentJobs) {
			this.maxConcurrentJobs = maxConcurrentJobs;
		}

		public int getMaxTotalEps() {
			return maxTotalEps;
		}

		public void setMaxTotalEps(int maxTotalEps) {
			this.maxTotalEps = maxTotalEps;
		}

		public Duration getMetricsInterval() {
			return metricsInterval;
		}

		public void setMetricsInterval(Duration metricsInterval) {
			this.metricsInterval = metricsInterval;
		}

		public int getHistorySize() {
			return historySize;
		}

		public void setHistorySize(int historySize) {
			this.historySize = historySize;
		}
	}

	public static class Security {

		/** 신규 가입 허용 여부. */
		private boolean allowSignup = true;

		public boolean isAllowSignup() {
			return allowSignup;
		}

		public void setAllowSignup(boolean allowSignup) {
			this.allowSignup = allowSignup;
		}
	}
}
