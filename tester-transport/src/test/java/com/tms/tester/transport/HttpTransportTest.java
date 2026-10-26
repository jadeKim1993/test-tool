package com.tms.tester.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tms.tester.common.Framing;
import com.tms.tester.common.HttpFormat;
import com.tms.tester.common.OverflowPolicy;
import com.tms.tester.common.Protocol;
import com.tms.tester.common.RunState;
import com.tms.tester.common.SelectionStrategy;
import com.tms.tester.common.WireEncoding;

import io.netty.buffer.ByteBuf;

/**
 * HTTP 전송 검증. JDK 내장 HTTP 서버를 수집기로 세우고, 본문 형식·실패 집계·연결 확인·묶음 동작을 본다.
 */
class HttpTransportTest {

	private NettyRuntime runtime;
	private HttpServer server;
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private final List<String> contentTypes = new CopyOnWriteArrayList<>();
	private final List<String> authHeaders = new CopyOnWriteArrayList<>();
	private final AtomicInteger postStatus = new AtomicInteger(200);
	private final AtomicInteger getStatus = new AtomicInteger(405);
	private volatile int delayMillis;

	@BeforeEach
	void setUp() throws IOException {
		runtime = new NettyRuntime(2);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
		runtime.close();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] body = in.readAllBytes();
			if ("POST".equals(exchange.getRequestMethod())) {
				if (delayMillis > 0) {
					try {
						Thread.sleep(delayMillis);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				bodies.add(new String(body, StandardCharsets.UTF_8));
				contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
				String auth = exchange.getRequestHeaders().getFirst("Authorization");
				authHeaders.add(auth == null ? "" : auth);
				byte[] reply = postStatus.get() >= 400 ? "{\"text\":\"Invalid token\",\"code\":4}".getBytes() : new byte[0];
				exchange.sendResponseHeaders(postStatus.get(), reply.length == 0 ? -1 : reply.length);
				if (reply.length > 0) {
					exchange.getResponseBody().write(reply);
				}
			}
			else {
				exchange.sendResponseHeaders(getStatus.get(), -1);
			}
		}
		finally {
			exchange.close();
		}
	}

	private TargetSpec target(HttpFormat format, String path, String headers, int batchBytes) {
		return new TargetSpec("http", Protocol.HTTP, "127.0.0.1", server.getAddress().getPort(), 1, Framing.NEWLINE,
				batchBytes, TargetSpec.DEFAULT_SEND_BUFFER, 2000, path, format, headers);
	}

	private ByteBuf record(LogSender sender, String text) {
		ByteBuf buf = sender.allocator().directBuffer();
		buf.writeCharSequence(text, StandardCharsets.UTF_8);
		return buf;
	}

	private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
	}

	@Test
	void JSON_Lines_는_따옴표_줄바꿈_한글을_이스케이프해_한_건씩_보낸다() throws Exception {
		try (LogSender sender = new HttpLogSender(target(HttpFormat.NDJSON, "/ingest", "", 64 * 1024), 1, runtime, false)) {
			sender.connect();
			SendEndpoint endpoint = sender.endpoint(0);
			endpoint.send(record(sender, "say \"hi\"\\ok"));
			endpoint.send(record(sender, "line1\n\tat Foo.bar(Foo.java:1)"));
			endpoint.send(record(sender, "한글 로그\u0001"));
			endpoint.flush();
			await(() -> !bodies.isEmpty());

			assertThat(bodies.get(0)).isEqualTo(
					"{\"message\":\"say \\\"hi\\\"\\\\ok\"}\n"
							+ "{\"message\":\"line1\\n\\tat Foo.bar(Foo.java:1)\"}\n"
							+ "{\"message\":\"한글 로그\\u0001\"}\n");
			assertThat(contentTypes.get(0)).isEqualTo("application/x-ndjson");
		}
	}

	@Test
	void 형식마다_수집기가_받는_본문_모양을_만든다() throws Exception {
		for (HttpFormat format : HttpFormat.values()) {
			bodies.clear();
			try (LogSender sender = new HttpLogSender(target(format, format.defaultPath(), "", 64 * 1024), 1, runtime, false)) {
				sender.connect();
				sender.endpoint(0).send(record(sender, "a"));
				sender.endpoint(0).send(record(sender, "b"));
				sender.endpoint(0).flush();
				await(() -> !bodies.isEmpty());
			}
			String body = bodies.get(0);
			switch (format) {
				case LINES -> assertThat(body).isEqualTo("a\nb\n");
				case NDJSON -> assertThat(body).isEqualTo("{\"message\":\"a\"}\n{\"message\":\"b\"}\n");
				case JSON_ARRAY -> assertThat(body).isEqualTo("[{\"message\":\"a\"},{\"message\":\"b\"}]");
				case SPLUNK_HEC -> assertThat(body).isEqualTo("{\"event\":\"a\"}\n{\"event\":\"b\"}\n");
				case ES_BULK -> assertThat(body)
						.matches("(\\{\"index\":\\{}}\\n\\{\"@timestamp\":\"[0-9T:.\\-]+Z\",\"message\":\"[ab]\"}\\n){2}");
				case LOKI -> assertThat(body)
						.matches("\\{\"streams\":\\[\\{\"stream\":\\{\"job\":\"tester\"},\"values\":"
								+ "\\[\\[\"\\d+\",\"a\"],\\[\"\\d+\",\"b\"]]}]}");
			}
		}
	}

	@Test
	void 사용자_헤더를_그대로_붙인다() throws Exception {
		String headers = "# Splunk 토큰\nAuthorization: Splunk 1234-abcd\n\n잘못된 줄\n";
		try (LogSender sender = new HttpLogSender(target(HttpFormat.SPLUNK_HEC, "/services/collector/event", headers, 1024),
				1, runtime, false)) {
			sender.connect();
			sender.endpoint(0).send(record(sender, "x"));
			sender.endpoint(0).flush();
			await(() -> !bodies.isEmpty());
			assertThat(authHeaders.get(0)).isEqualTo("Splunk 1234-abcd");
		}
	}

	@Test
	void 오류_응답이면_그_요청의_건수를_실패로_세고_이유를_보여준다() throws Exception {
		postStatus.set(403);
		try (LogSender sender = new HttpLogSender(target(HttpFormat.SPLUNK_HEC, "/services/collector/event", "", 64 * 1024),
				1, runtime, false)) {
			sender.connect();
			for (int i = 0; i < 3; i++) {
				sender.endpoint(0).send(record(sender, "e" + i));
			}
			sender.endpoint(0).flush();
			await(() -> sender.failedRecords() == 3);

			assertThat(sender.failedRecords()).isEqualTo(3);
			assertThat(sender.failedBytes()).isPositive();
			assertThat(sender.lastError()).startsWith("HTTP 403").contains("Invalid token");
		}
	}

	@Test
	void 연결_확인은_HTTP_응답을_보고_인증_거부는_실패로_알린다() throws Exception {
		try (LogSender sender = new HttpLogSender(target(HttpFormat.LINES, "/", "", 1024), 1, runtime, false)) {
			sender.connect();
			assertThat(sender.connectMessage()).contains("405");
		}
		getStatus.set(401);
		try (LogSender sender = new HttpLogSender(target(HttpFormat.LINES, "/", "", 1024), 1, runtime, false)) {
			assertThatThrownBy(sender::connect).hasMessageContaining("401").hasMessageContaining("토큰");
		}
	}

	@Test
	void 응답을_기다리는_동안_쌓인_로그는_다음_요청에_묶인다() throws Exception {
		delayMillis = 200;
		try (LogSender sender = new HttpLogSender(target(HttpFormat.LINES, "/", "", 64 * 1024), 1, runtime, false)) {
			sender.connect();
			SendEndpoint endpoint = sender.endpoint(0);
			for (int i = 0; i < 100; i++) {
				endpoint.send(record(sender, "r" + i));
				endpoint.flush();
			}
			await(() -> bodies.stream().mapToLong(b -> b.lines().count()).sum() == 100);

			// 첫 요청이 200ms 걸리는 동안 나머지 99건은 하나로 묶여야 한다.
			assertThat(bodies.stream().mapToLong(b -> b.lines().count()).sum()).isEqualTo(100);
			assertThat(bodies).hasSizeLessThanOrEqualTo(3);
		}
	}

	@Test
	void 엔진으로_보내면_건수_제한만큼_수집기에_도착한다() throws Exception {
		TargetSpec target = target(HttpFormat.LINES, "/bulk", "", 4096);
		RunSpec spec = new RunSpec(9L, "http", target,
				List.of(TemplateSource.of(1L, "t", "${ip} 줄바꿈\n이 든 로그 ${int(1,9)}")),
				SelectionStrategy.ROUND_ROBIN, 5_000, 1, null, 2_000L, OverflowPolicy.BLOCK, WireEncoding.UTF_8);
		LoadGenerator generator = new LoadGenerator(spec, runtime, TransportRegistry.standard());
		generator.start();
		long deadline = System.currentTimeMillis() + 10_000;
		while (generator.isActive() && System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		assertThat(generator.state()).isEqualTo(RunState.COMPLETED);
		long sent = generator.statistics().sent();
		// LINES 형식은 로그 안의 줄바꿈도 그대로 보낸다(줄 단위 수집기에 맞게 나눠지는 것이 의도된 동작).
		await(() -> bodies.stream().mapToLong(b -> b.split("\n", -1).length - 1).sum() == sent * 2);
		assertThat(bodies.stream().mapToLong(b -> b.split("\n", -1).length - 1).sum()).isEqualTo(sent * 2);
		assertThat(generator.sample().dropped()).isZero();
	}
}
