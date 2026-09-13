import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 테스트 도구가 보내는 TCP/UDP 트래픽을 받아 눈으로 확인하기 위한 테스트 전용 수신 서버.
 *
 * <p>순수 JDK API 만 쓴다(외부 의존성 0개). 목적이 "실제로 보낸 로그가 맞게 도착하는지
 * 손으로 확인하는 것"이지 처리량 측정이 아니므로, 블로킹 I/O + 스레드 하나당 커넥션 하나로
 * 충분히 단순하게 짰다. 10만 EPS 급 부하 검증은 tester-transport 의 벤치마크(`./gradlew perf`)가
 * 이미 다루고 있고, 이 도구는 그 반대편 — "적은 트래픽을 정확히" 를 위한 것이다.
 *
 * <p>제공 기능
 * <ul>
 *   <li>TCP(개행 구분) / UDP(1 데이터그램 = 1건) 수신, 건수/바이트 누적</li>
 *   <li>최근 수신분을 UTF-8 / CP949(MS949) / Base64 세 가지로 동시에 미리보기
 *       — 테스트 도구의 전송 인코딩 설정이 실제로 맞게 나가는지 바로 확인할 수 있다</li>
 *   <li>브라우저로 보는 실시간 대시보드 ({@code /}) 와 JSON API ({@code /stats}, {@code /recent})</li>
 * </ul>
 *
 * <p>환경변수: {@code TCP_PORT}(9000) / {@code UDP_PORT}(5514) / {@code HTTP_PORT}(8090) /
 * {@code MAX_RECENT}(50, 메모리에 보관할 최근 항목 수) / {@code MAX_LINE_BYTES}(8192,
 * 개행이 오지 않을 때 강제로 잘라내는 안전장치).
 *
 * <p><b>주의</b>: "최근 수신분" 미리보기는 TCP 를 개행(\n) 기준으로 자른다. 테스트 도구의
 * 기본 프레이밍(NEWLINE)에서는 정확하지만, LENGTH_PREFIX/OCTET_COUNTING/NONE 프레이밍을
 * 쓰면 미리보기가 깨져 보일 수 있다 — 그래도 건수·바이트 누적은 항상 정확하다.
 */
public final class LogSink {

	private static final int TCP_PORT = intEnv("TCP_PORT", 9000);
	private static final int UDP_PORT = intEnv("UDP_PORT", 5514);
	private static final int HTTP_PORT = intEnv("HTTP_PORT", 8090);
	private static final int MAX_RECENT = intEnv("MAX_RECENT", 50);
	private static final int MAX_LINE_BYTES = intEnv("MAX_LINE_BYTES", 8192);

	/** CP949 는 JDK 표준명이 아니라 x-windows-949(MS949) 로 찾는다. tester 본체와 같은 기준. */
	private static final Charset MS949 = charsetOrNull("x-windows-949");

	private static final Instant STARTED_AT = Instant.now();

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && "--healthcheck".equals(args[0])) {
			System.exit(healthcheck() ? 0 : 1);
			return;
		}

		Stats tcpStats = new Stats();
		Stats udpStats = new Stats();
		RecentBuffer recent = new RecentBuffer(MAX_RECENT);

		System.out.printf(
				"로그 수신 테스트 서버 시작 — TCP:%d  UDP:%d  대시보드: http://localhost:%d  (최근 %d건 보관)%n",
				TCP_PORT, UDP_PORT, HTTP_PORT, MAX_RECENT);

		startTcpServer(tcpStats, recent);
		startUdpServer(udpStats, recent);
		startHttpServer(tcpStats, udpStats, recent);
		startConsoleReporter(tcpStats, udpStats);

		Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("종료합니다.")));

		// 데몬 스레드들이 도는 동안 메인 스레드는 그냥 대기한다. 컨테이너는 SIGTERM 으로 멈춘다.
		new CountDownLatch(1).await();
	}

	// ==================== TCP ====================

	private static void startTcpServer(Stats stats, RecentBuffer recent) {
		Thread thread = new Thread(() -> {
			try (ServerSocket server = new ServerSocket(TCP_PORT)) {
				while (true) {
					Socket socket = server.accept();
					stats.activeConnections.incrementAndGet();
					Thread handler = new Thread(() -> handleTcpConnection(socket, stats, recent), "tcp-conn");
					handler.setDaemon(true);
					handler.start();
				}
			}
			catch (IOException e) {
				System.err.println("TCP 서버 오류: " + e.getMessage());
			}
		}, "tcp-acceptor");
		thread.setDaemon(true);
		thread.start();
	}

	/** 개행(\n)으로 로그 한 건을 구분한다. 테스트 도구의 기본 프레이밍(NEWLINE)과 맞다. */
	private static void handleTcpConnection(Socket socket, Stats stats, RecentBuffer recent) {
		String remote = String.valueOf(socket.getRemoteSocketAddress());
		try (InputStream in = new BufferedInputStream(socket.getInputStream())) {
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			int b;
			while ((b = in.read()) != -1) {
				if (b == '\n') {
					record(stats, recent, "TCP", remote, line.toByteArray());
					line.reset();
				}
				else {
					line.write(b);
					if (line.size() >= MAX_LINE_BYTES) {
						// 개행 없이 너무 길어지면 안전하게 끊어서 기록한다(프레이밍이 다른 경우 대비).
						record(stats, recent, "TCP", remote, line.toByteArray());
						line.reset();
					}
				}
			}
			if (line.size() > 0) {
				record(stats, recent, "TCP", remote, line.toByteArray());
			}
		}
		catch (IOException ignored) {
			// 클라이언트가 연결을 끊는 것은 정상 흐름이다.
		}
		finally {
			stats.activeConnections.decrementAndGet();
			try {
				socket.close();
			}
			catch (IOException ignored) {
			}
		}
	}

	// ==================== UDP ====================

	private static void startUdpServer(Stats stats, RecentBuffer recent) {
		Thread thread = new Thread(() -> {
			try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
				byte[] buffer = new byte[65535];
				while (true) {
					DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
					socket.receive(packet);
					byte[] raw = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
							packet.getOffset() + packet.getLength());
					record(stats, recent, "UDP", String.valueOf(packet.getSocketAddress()), raw);
				}
			}
			catch (IOException e) {
				System.err.println("UDP 서버 오류: " + e.getMessage());
			}
		}, "udp-receiver");
		thread.setDaemon(true);
		thread.start();
	}

	// ==================== 공통 기록 ====================

	private static void record(Stats stats, RecentBuffer recent, String protocol, String remote, byte[] raw) {
		stats.count.increment();
		stats.bytes.add(raw.length);
		recent.add(new Entry(Instant.now(), protocol, remote, raw));
	}

	// ==================== 콘솔 요약 출력 ====================

	/** docker logs 로 지켜볼 때 쓰는 요약. 트래픽이 없으면 조용히 있는다. */
	private static void startConsoleReporter(Stats tcp, Stats udp) {
		Thread thread = new Thread(() -> {
			long lastTcp = 0;
			long lastUdp = 0;
			while (true) {
				try {
					Thread.sleep(2000);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				long curTcp = tcp.count.sum();
				long curUdp = udp.count.sum();
				if (curTcp != lastTcp || curUdp != lastUdp) {
					System.out.printf("[수신 현황] TCP 누적 %,d건(%,d B, 연결 %d개) · UDP 누적 %,d건(%,d B)%n",
							curTcp, tcp.bytes.sum(), tcp.activeConnections.get(), curUdp, udp.bytes.sum());
					lastTcp = curTcp;
					lastUdp = curUdp;
				}
			}
		}, "console-reporter");
		thread.setDaemon(true);
		thread.start();
	}

	// ==================== HTTP 대시보드 / API ====================

	private static void startHttpServer(Stats tcp, Stats udp, RecentBuffer recent) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
		server.createContext("/health", ex -> respond(ex, 200, "text/plain; charset=utf-8", "ok"));
		server.createContext("/stats", ex -> respond(ex, 200, "application/json", toJson(statsPayload(tcp, udp))));
		server.createContext("/recent", ex -> handleRecent(ex, recent));
		server.createContext("/", ex -> respond(ex, 200, "text/html; charset=utf-8", DASHBOARD_HTML));
		server.setExecutor(Executors.newFixedThreadPool(4));
		server.start();
	}

	private static void handleRecent(HttpExchange exchange, RecentBuffer recent) throws IOException {
		Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
		int limit = Math.min(parseIntOr(query.get("n"), 20), MAX_RECENT);
		String protocol = query.get("proto");

		List<Object> items = new ArrayList<>();
		for (Entry entry : recent.snapshot(protocol, limit)) {
			items.add(describe(entry));
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("count", items.size());
		payload.put("items", items);
		respond(exchange, 200, "application/json", toJson(payload));
	}

	private static Map<String, Object> statsPayload(Stats tcp, Stats udp) {
		Map<String, Object> payload = new LinkedHashMap<>();
		Map<String, Object> tcpMap = new LinkedHashMap<>();
		tcpMap.put("port", TCP_PORT);
		tcpMap.put("connections", tcp.activeConnections.get());
		tcpMap.put("count", tcp.count.sum());
		tcpMap.put("bytes", tcp.bytes.sum());
		Map<String, Object> udpMap = new LinkedHashMap<>();
		udpMap.put("port", UDP_PORT);
		udpMap.put("count", udp.count.sum());
		udpMap.put("bytes", udp.bytes.sum());
		payload.put("tcp", tcpMap);
		payload.put("udp", udpMap);
		payload.put("startedAt", STARTED_AT.toString());
		payload.put("uptimeSec", Duration.between(STARTED_AT, Instant.now()).getSeconds());
		return payload;
	}

	/** 받은 그대로의 바이트를 세 가지 인코딩으로 동시에 풀어 보여준다. */
	private static Map<String, Object> describe(Entry entry) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("time", entry.receivedAt().toString());
		map.put("protocol", entry.protocol());
		map.put("remote", entry.remote());
		map.put("bytes", entry.raw().length);
		map.put("utf8", new String(entry.raw(), StandardCharsets.UTF_8));
		if (MS949 != null) {
			map.put("cp949", new String(entry.raw(), MS949));
		}
		String base64Decoded = tryBase64Decode(entry.raw());
		if (base64Decoded != null) {
			map.put("base64Decoded", base64Decoded);
		}
		map.put("hex", toHexPreview(entry.raw(), 64));
		return map;
	}

	private static String tryBase64Decode(byte[] raw) {
		try {
			String text = new String(raw, StandardCharsets.US_ASCII).trim();
			if (text.isEmpty() || !text.matches("[A-Za-z0-9+/]+=*")) {
				return null;
			}
			return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static String toHexPreview(byte[] raw, int maxBytes) {
		int n = Math.min(raw.length, maxBytes);
		StringBuilder sb = new StringBuilder(n * 2 + 3);
		for (int i = 0; i < n; i++) {
			sb.append(String.format("%02x", raw[i]));
		}
		if (raw.length > maxBytes) {
			sb.append("...");
		}
		return sb.toString();
	}

	// ==================== 헬스체크(클라이언트 모드) ====================

	/** Docker HEALTHCHECK 용. curl/wget 을 이미지에 더 넣지 않고 JDK 내장 HttpClient 로 대신한다. */
	private static boolean healthcheck() {
		try {
			HttpClient client = HttpClient.newHttpClient();
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + HTTP_PORT + "/health"))
					.timeout(Duration.ofSeconds(2))
					.GET()
					.build();
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			return response.statusCode() == 200;
		}
		catch (Exception e) {
			return false;
		}
	}

	// ==================== 자료 구조 ====================

	private record Entry(Instant receivedAt, String protocol, String remote, byte[] raw) {
	}

	private static final class Stats {
		final LongAdder count = new LongAdder();
		final LongAdder bytes = new LongAdder();
		final AtomicInteger activeConnections = new AtomicInteger();
	}

	/** 최근 N건만 메모리에 들고 있는 원형 버퍼. 트래픽이 적다는 전제라 동기화만으로 충분하다. */
	private static final class RecentBuffer {
		private final int capacity;
		private final Deque<Entry> buffer = new ArrayDeque<>();

		RecentBuffer(int capacity) {
			this.capacity = Math.max(1, capacity);
		}

		synchronized void add(Entry entry) {
			buffer.addLast(entry);
			while (buffer.size() > capacity) {
				buffer.removeFirst();
			}
		}

		synchronized List<Entry> snapshot(String protocolFilter, int limit) {
			List<Entry> result = new ArrayList<>();
			Iterator<Entry> it = buffer.descendingIterator();
			while (it.hasNext() && result.size() < limit) {
				Entry entry = it.next();
				if (protocolFilter == null || protocolFilter.isBlank()
						|| protocolFilter.equalsIgnoreCase(entry.protocol())) {
					result.add(entry);
				}
			}
			return result;
		}
	}

	// ==================== 아주 작은 JSON/HTTP 유틸 ====================
	// 우리가 만드는 데이터 구조만 직렬화하면 되므로 외부 JSON 라이브러리 없이 직접 짠다.

	private static String toJson(Object value) {
		StringBuilder sb = new StringBuilder();
		writeJson(value, sb);
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static void writeJson(Object value, StringBuilder sb) {
		if (value == null) {
			sb.append("null");
		}
		else if (value instanceof String s) {
			sb.append('"').append(escapeJson(s)).append('"');
		}
		else if (value instanceof Number || value instanceof Boolean) {
			sb.append(value);
		}
		else if (value instanceof Map<?, ?> map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append('"').append(escapeJson(String.valueOf(e.getKey()))).append("\":");
				writeJson(e.getValue(), sb);
			}
			sb.append('}');
		}
		else if (value instanceof List<?> list) {
			sb.append('[');
			boolean first = true;
			for (Object item : list) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				writeJson(item, sb);
			}
			sb.append(']');
		}
		else {
			sb.append('"').append(escapeJson(String.valueOf(value))).append('"');
		}
	}

	private static String escapeJson(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					}
					else {
						sb.append(c);
					}
				}
			}
		}
		return sb.toString();
	}

	private static void respond(HttpExchange exchange, int status, String contentType, String body)
			throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static Map<String, String> parseQuery(String rawQuery) {
		Map<String, String> result = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isBlank()) {
			return result;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			String key = eq < 0 ? pair : pair.substring(0, eq);
			String value = eq < 0 ? "" : pair.substring(eq + 1);
			result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
		}
		return result;
	}

	private static int parseIntOr(String value, int fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int intEnv(String name, int fallback) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static Charset charsetOrNull(String name) {
		try {
			return Charset.forName(name);
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	// ==================== 대시보드 ====================

	private static final String DASHBOARD_HTML = """
			<!doctype html>
			<html lang="ko">
			<head>
			<meta charset="utf-8">
			<meta name="viewport" content="width=device-width, initial-scale=1">
			<title>로그 수신 테스트 서버</title>
			<style>
			  :root { color-scheme: dark; }
			  body { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; background: #111417;
			         color: #d8dee3; margin: 0; padding: 1.25rem; font-size: 13px; }
			  h1 { font-size: 1rem; margin: 0 0 .25rem; }
			  .sub { color: #7a8290; margin-bottom: 1rem; }
			  .stats { display: flex; gap: 1rem; margin-bottom: 1rem; flex-wrap: wrap; }
			  .stat { background: #1b1f24; border: 1px solid #2a2f36; padding: .6rem .9rem; border-radius: 8px; min-width: 8rem; }
			  .stat .label { color: #7a8290; font-size: .7rem; text-transform: uppercase; letter-spacing: .04em; }
			  .stat .value { font-size: 1.25rem; font-weight: 600; margin-top: .15rem; }
			  .toolbar { display: flex; align-items: center; gap: .6rem; margin-bottom: .9rem; }
			  select, button { background: #1b1f24; color: #d8dee3; border: 1px solid #2a2f36;
			                    padding: .35rem .6rem; border-radius: 6px; font: inherit; }
			  button { cursor: pointer; }
			  button:hover { border-color: #4a90e2; }
			  .entry { background: #1b1f24; border-left: 3px solid #3ecf8e; padding: .55rem .8rem;
			           margin-bottom: .5rem; border-radius: 0 8px 8px 0; }
			  .entry.udp { border-left-color: #4a90e2; }
			  .meta { color: #7a8290; font-size: .72rem; margin-bottom: .3rem; }
			  .line { white-space: pre-wrap; word-break: break-all; line-height: 1.5; }
			  .label { color: #4a90e2; margin-right: .5rem; font-weight: 600; font-size: .72rem; }
			  .empty { color: #555; padding: 2rem 0; text-align: center; }
			</style>
			</head>
			<body>
			<h1>&#x1F9EA; 로그 수신 테스트 서버</h1>
			<div class="sub">테스트 도구에서 보낸 TCP/UDP 트래픽을 그대로 받아 확인합니다 · 1초마다 자동 새로고침</div>

			<div class="stats" id="stats"></div>

			<div class="toolbar">
			  프로토콜:
			  <select id="proto">
			    <option value="">전체</option>
			    <option value="tcp">TCP</option>
			    <option value="udp">UDP</option>
			  </select>
			  <button onclick="load()">지금 새로고침</button>
			</div>

			<div id="recent"></div>

			<script>
			function escapeHtml(s) {
			  return (s || '').replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
			}

			async function load() {
			  try {
			    const s = await (await fetch('/stats')).json();
			    document.getElementById('stats').innerHTML = `
			      <div class="stat"><div class="label">TCP 수신</div><div class="value">${s.tcp.count.toLocaleString()}건</div></div>
			      <div class="stat"><div class="label">TCP 바이트</div><div class="value">${s.tcp.bytes.toLocaleString()} B</div></div>
			      <div class="stat"><div class="label">TCP 연결</div><div class="value">${s.tcp.connections}개</div></div>
			      <div class="stat"><div class="label">UDP 수신</div><div class="value">${s.udp.count.toLocaleString()}건</div></div>
			      <div class="stat"><div class="label">UDP 바이트</div><div class="value">${s.udp.bytes.toLocaleString()} B</div></div>
			      <div class="stat"><div class="label">가동 시간</div><div class="value">${s.uptimeSec}s</div></div>`;

			    const proto = document.getElementById('proto').value;
			    const r = await (await fetch('/recent?n=30' + (proto ? '&proto=' + proto : ''))).json();
			    const box = document.getElementById('recent');
			    if (r.items.length === 0) {
			      box.innerHTML = '<div class="empty">아직 수신된 로그가 없습니다. 테스트 도구에서 이 서버로 전송을 시작해 보세요.</div>';
			      return;
			    }
			    box.innerHTML = r.items.map(e => `
			      <div class="entry ${e.protocol.toLowerCase()}">
			        <div class="meta">[${e.protocol}] ${e.remote} · ${e.bytes}B · ${e.time}</div>
			        <div class="line"><span class="label">UTF-8</span>${escapeHtml(e.utf8)}</div>
			        ${e.cp949 && e.cp949 !== e.utf8 ? `<div class="line"><span class="label">CP949</span>${escapeHtml(e.cp949)}</div>` : ''}
			        ${e.base64Decoded ? `<div class="line"><span class="label">Base64→</span>${escapeHtml(e.base64Decoded)}</div>` : ''}
			      </div>`).join('');
			  } catch (err) {
			    console.error(err);
			  }
			}
			load();
			setInterval(load, 1000);
			</script>
			</body>
			</html>
			""";
}
