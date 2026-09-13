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
		 * HS256 서명키. 미설정 시 기동할 때마다 무작위로 생성한다
		 * (재시작하면 기존 토큰이 무효화되므로 운영에서는 반드시 지정할 것).
		 */
		private String secret;

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
