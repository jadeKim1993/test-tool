package com.tms.tester.generator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code ${dict(name)}} 이 사용하는 단어 사전.
 * <p>내장 사전 외에 사용자 사전을 런타임에 등록할 수 있다(확장 지점).
 */
public final class Dictionaries {

	private static final Map<String, String[]> REGISTRY = new ConcurrentHashMap<>();

	static {
		register("user_agents",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
				"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
				"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
				"Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
				"curl/8.4.0",
				"python-requests/2.31.0",
				"Java/21.0.4",
				"Go-http-client/2.0",
				"kube-probe/1.28",
				"Googlebot/2.1 (+http://www.google.com/bot.html)");

		register("http_methods", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

		register("paths",
				"/api/v1/users", "/api/v1/orders", "/api/v1/items", "/api/v1/payments",
				"/api/v1/auth/login", "/health", "/metrics", "/static/app.js", "/index.html",
				"/api/v2/search", "/api/v1/carts", "/favicon.ico");

		register("usernames",
				"gtkim", "admin", "svc_batch", "jdoe", "anonymous", "tester01",
				"app_user", "root", "monitor", "guest");

		register("log_levels", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");

		register("services",
				"order-service", "user-service", "payment-gateway", "auth-server",
				"search-api", "notification", "batch-worker", "api-gateway");

		register("exceptions",
				"java.lang.NullPointerException",
				"java.lang.IllegalArgumentException",
				"java.sql.SQLException",
				"java.net.SocketTimeoutException",
				"org.springframework.dao.DataIntegrityViolationException",
				"java.util.concurrent.TimeoutException",
				"java.io.IOException");

		register("words",
				"request", "response", "session", "cache", "queue", "worker", "shard",
				"index", "commit", "rollback", "timeout", "retry", "connection", "pool",
				"handler", "buffer", "segment", "cluster", "replica", "snapshot");

		register("firewall_actions", "ALLOW", "DENY", "DROP", "RESET");

		register("countries", "KR", "US", "JP", "CN", "DE", "FR", "GB", "SG", "IN", "BR");
	}

	private Dictionaries() {
	}

	public static void register(String name, String... values) {
		REGISTRY.put(name, values);
	}

	public static void register(String name, List<String> values) {
		REGISTRY.put(name, values.toArray(new String[0]));
	}

	public static String[] get(String name) {
		String[] values = REGISTRY.get(name);
		if (values == null) {
			throw new TemplateSyntaxException("등록되지 않은 사전입니다: " + name + " (사용 가능: " + String.join(", ", names()) + ")", -1);
		}
		return values;
	}

	public static java.util.Set<String> names() {
		return new LinkedHashMap<>(REGISTRY).keySet();
	}
}
