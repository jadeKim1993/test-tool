package com.tms.tester.transport;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * HTTP / HTTPS 송신기. 로그를 모아 POST 요청 본문으로 보낸다.
 *
 * <h2>흐름</h2>
 * 워커마다 keep-alive 커넥션 하나를 갖고, 커넥션마다 <b>요청은 한 번에 하나</b>만 보낸다. 응답을 기다리는
 * 동안 들어온 로그는 다음 요청 본문에 쌓인다. 그래서 속도가 낮으면 요청 하나에 몇 건, 높으면 자연스럽게
 * 큰 묶음이 되고(최대 {@link TargetSpec#batchBytes()}), 따로 "몇 건씩 보낼지"를 정할 필요가 없다.
 * 쌓인 양이 묶음 크기의 4배를 넘으면 {@link #endpoint(int) 창구}가 쓰기 불가로 바뀌어 포화 정책(버림/대기)이 적용된다.
 *
 * <h2>실패 집계</h2>
 * 워커는 본문에 넣는 순간 "전송"으로 센다. 응답이 2xx 가 아니거나 응답 전에 끊기면 그 요청에 담긴 건수를
 * {@link #failedRecords()} 로 따로 세고, {@link LoadGenerator} 가 전송에서 빼서 버림으로 옮긴다.
 * 상태 코드와 응답 본문 앞부분은 {@link #lastError()} 로 화면에 보인다(401 이면 토큰을 고치라는 뜻이다).
 *
 * <h2>연결 확인</h2>
 * {@link #connect()} 는 TCP(·TLS) 연결 뒤 같은 경로로 GET 을 보내 HTTP 서버가 응답하는지 본다. 로그를 받는
 * 경로라 대개 405 등이 오지만 "HTTP 서버가 거기 있다"는 확인으로 충분하다. 401/403 은 인증 문제로 보고 실패로 알린다.
 */
final class HttpLogSender implements LogSender {

	private static final Logger log = LoggerFactory.getLogger(HttpLogSender.class);

	private static final int INITIAL_BACKOFF_MILLIS = 500;
	private static final int MAX_BACKOFF_MILLIS = 3_000;
	/** 응답 본문은 오류 메시지용으로 앞부분만 본다. 큰 응답 때문에 메모리를 쓰지 않도록 상한을 둔다. */
	private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
	private static final int ERROR_SNIPPET_CHARS = 200;
	/** 쌓아 둘 수 있는 본문 크기 = 묶음 크기 × 이 값. 넘으면 포화로 본다. */
	private static final int PENDING_LIMIT_FACTOR = 4;

	private final TargetSpec spec;
	private final NettyRuntime runtime;
	private final boolean tls;
	private final Connection[] connections;
	private final Bootstrap bootstrap;
	private final List<Map.Entry<String, String>> extraHeaders;
	private final String hostHeader;
	private final long requestTimeoutMillis;

	private final LongAdder failedRecords = new LongAdder();
	private final LongAdder failedBytes = new LongAdder();

	private volatile boolean closed;
	private volatile String lastError;
	private volatile String connectMessage;

	HttpLogSender(TargetSpec spec, int workerCount, NettyRuntime runtime, boolean tls) {
		this.spec = spec;
		this.runtime = runtime;
		this.tls = tls;
		int count = spec.connections() > 0 ? spec.connections() : workerCount;
		this.connections = new Connection[Math.max(1, count)];
		this.extraHeaders = parseHeaders(spec.httpHeaders());
		boolean defaultPort = (!tls && spec.port() == 80) || (tls && spec.port() == 443);
		this.hostHeader = defaultPort ? spec.host() : spec.host() + ":" + spec.port();
		// 수집기가 잠깐 바쁜 것과 멈춘 것을 구분한다. 연결 타임아웃보다 넉넉하게, 최소 10초.
		this.requestTimeoutMillis = Math.max(10_000L, spec.connectTimeoutMs() * 3L);
		this.bootstrap = new Bootstrap()
				.group(runtime.group())
				.channel(runtime.socketChannelType())
				.option(ChannelOption.ALLOCATOR, runtime.allocator())
				.option(ChannelOption.TCP_NODELAY, true)
				.option(ChannelOption.SO_KEEPALIVE, true)
				.option(ChannelOption.SO_SNDBUF, spec.sendBufferBytes())
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, spec.connectTimeoutMs());
	}

	/** "이름: 값" 줄을 읽는다. 빈 줄과 # 주석은 건너뛰고, 콜론이 없는 줄은 무시한다. */
	static List<Map.Entry<String, String>> parseHeaders(String raw) {
		List<Map.Entry<String, String>> headers = new ArrayList<>();
		if (raw == null) {
			return headers;
		}
		for (String line : raw.split("\\R")) {
			String trimmed = line.trim();
			int colon = trimmed.indexOf(':');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || colon <= 0) {
				continue;
			}
			headers.add(Map.entry(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim()));
		}
		return headers;
	}

	/** TLS 컨텍스트는 실제로 HTTPS 대상을 쓸 때만 초기화되도록 지연 로딩한다. */
	private static final class InsecureSslContext {
		static final SslContext INSTANCE = build();

		private static SslContext build() {
			try {
				// 내부 수집 서버는 자체 서명 인증서가 대부분이다. 신뢰 체인이 아니라 전송 자체를 시험한다(TLS 와 같은 이유).
				return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
			}
			catch (SSLException e) {
				throw new IllegalStateException("TLS 클라이언트 컨텍스트 초기화 실패", e);
			}
		}
	}

	@Override
	public void connect() throws Exception {
		for (int i = 0; i < connections.length; i++) {
			connections[i] = new Connection(i);
		}
		ChannelFuture first = connections[0].doConnect();
		for (int i = 1; i < connections.length; i++) {
			connections[i].doConnect();
		}
		if (!first.await(spec.connectTimeoutMs() + 500L, TimeUnit.MILLISECONDS)) {
			throw new java.net.ConnectException(spec.endpoint() + " 연결 시간 초과");
		}
		if (!first.isSuccess()) {
			throw new java.net.ConnectException(spec.endpoint() + " 연결 실패: " + first.cause().getMessage());
		}
		if (tls) {
			SslHandler ssl = first.channel().pipeline().get(SslHandler.class);
			if (!ssl.handshakeFuture().await(spec.connectTimeoutMs() + 500L, TimeUnit.MILLISECONDS)) {
				throw new java.net.ConnectException(spec.endpoint()
						+ " TLS 핸드셰이크 시간 초과 — 평문 HTTP 서버라면 프로토콜을 HTTP 로 바꾸세요");
			}
			if (!ssl.handshakeFuture().isSuccess()) {
				throw new java.net.ConnectException(spec.endpoint() + " TLS 핸드셰이크 실패: "
						+ ssl.handshakeFuture().cause().getMessage() + " (평문 HTTP 서버라면 프로토콜을 HTTP 로 바꾸세요)");
			}
		}
		FullHttpResponseSummary probe;
		try {
			probe = connections[0].probe().get(spec.connectTimeoutMs() + 2_000L, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			throw new java.net.ConnectException(spec.endpoint() + " — 연결은 됐지만 HTTP 응답이 없습니다."
					+ (tls ? "" : " HTTPS 서버라면 프로토콜을 HTTPS 로 바꾸세요."));
		}
		catch (java.util.concurrent.ExecutionException e) {
			throw new java.net.ConnectException(spec.endpoint() + " — HTTP 응답을 읽지 못했습니다: "
					+ e.getCause().getMessage() + (tls ? "" : " (HTTPS 서버라면 프로토콜을 HTTPS 로 바꾸세요)"));
		}
		if (probe.status() == 401 || probe.status() == 403) {
			throw new java.net.ConnectException("HTTP " + probe.statusLine()
					+ " — 서버가 인증을 거부했습니다. 헤더(Authorization 등)의 토큰을 확인하세요.");
		}
		connectMessage = "HTTP 서버 응답 확인 (GET " + spec.httpPath() + " → " + probe.statusLine()
				+ "). 로그는 POST 로 보냅니다.";
	}

	@Override
	public String connectMessage() {
		return connectMessage;
	}

	@Override
	public boolean keepsRetrying() {
		return true;
	}

	@Override
	public ByteBufAllocator allocator() {
		return runtime.allocator();
	}

	@Override
	public SendEndpoint endpoint(int workerIndex) {
		return connections[workerIndex % connections.length];
	}

	@Override
	public SenderState state() {
		if (closed) {
			return SenderState.CLOSED;
		}
		for (Connection connection : connections) {
			Channel channel = connection == null ? null : connection.channel;
			if (channel == null || !channel.isActive()) {
				return SenderState.RECONNECTING;
			}
		}
		return SenderState.CONNECTED;
	}

	@Override
	public String lastError() {
		return lastError;
	}

	@Override
	public long failedRecords() {
		return failedRecords.sum();
	}

	@Override
	public long failedBytes() {
		return failedBytes.sum();
	}

	@Override
	public String describe() {
		return spec.endpoint() + " (" + spec.httpFormat().label() + ", 커넥션 " + connections.length + "개, "
				+ runtime.transportName() + ")";
	}

	/** 정지할 때 남은 로그를 보내고 응답을 기다리는 최대 시간. */
	private static final long DRAIN_MILLIS = 3_000;

	@Override
	public void close() {
		// 응답을 기다리는 요청과 아직 못 보낸 본문이 있으면 먼저 마저 보낸다. 건수 제한으로 끝난 작업의
		// 마지막 묶음이 닫히면서 사라지면 "보낸 건수"와 "도착한 건수"가 어긋난다.
		long deadline = System.currentTimeMillis() + DRAIN_MILLIS;
		for (Connection connection : connections) {
			if (connection != null) {
				connection.drain(deadline);
			}
		}
		closed = true;
		for (Connection connection : connections) {
			if (connection != null) {
				connection.close();
			}
		}
	}

	/** 오류 메시지용 응답 요약. */
	record FullHttpResponseSummary(int status, String statusLine) {
	}

	/**
	 * 커넥션 1개 = 워커 1개의 창구. 본문 조립(워커 스레드)과 응답 처리(이벤트 루프)가 같은 상태를 만지므로
	 * 본문·요청 상태는 이 객체의 잠금 안에서만 바꾼다. 잠금은 짧다(바이트 복사와 write 호출뿐).
	 */
	private final class Connection implements SendEndpoint {

		private final int index;
		private final AtomicInteger backoffMillis = new AtomicInteger(INITIAL_BACKOFF_MILLIS);
		private final HttpBodyWriter writer = new HttpBodyWriter(spec.httpFormat());
		private volatile Channel channel;

		// ---- 아래는 this 잠금으로 보호 ----
		private ByteBuf pending;
		private int pendingCount;
		private boolean inFlight;
		private int inFlightCount;
		private long inFlightBytes;
		private long requestSeq;
		private CompletableFuture<FullHttpResponseSummary> probe;
		/** isWritable 은 잠금 없이 본다. */
		private volatile int pendingBytes;

		private Connection(int index) {
			this.index = index;
		}

		ChannelFuture doConnect() {
			Bootstrap b = bootstrap.clone().handler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) {
					if (tls) {
						ch.pipeline().addLast(InsecureSslContext.INSTANCE.newHandler(ch.alloc(), spec.host(), spec.port()));
					}
					ch.pipeline().addLast(new HttpClientCodec());
					ch.pipeline().addLast(new HttpObjectAggregator(MAX_RESPONSE_BYTES));
					ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
						@Override
						protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
							onResponse(ctx.channel(), response);
						}

						@Override
						public void channelInactive(ChannelHandlerContext ctx) {
							onDisconnected();
							ctx.fireChannelInactive();
						}

						@Override
						public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
							lastError = cause.getMessage();
							failProbe(cause);
							ctx.close();
						}
					});
				}
			});
			ChannelFuture future = b.connect(new InetSocketAddress(spec.host(), spec.port()));
			future.addListener(f -> {
				if (f.isSuccess()) {
					Channel connected = future.channel();
					channel = connected;
					connected.closeFuture().addListener(cf -> {
						channel = null;
						scheduleReconnect();
					});
					if (tls) {
						connected.pipeline().get(SslHandler.class).handshakeFuture().addListener(h -> {
							if (h.isSuccess()) {
								markConnected();
							}
						});
					}
					else {
						markConnected();
					}
				}
				else {
					lastError = future.cause() == null ? "연결 실패" : future.cause().getMessage();
					scheduleReconnect();
				}
			});
			return future;
		}

		private void markConnected() {
			backoffMillis.set(INITIAL_BACKOFF_MILLIS);
			// 끊긴 동안 쌓인 로그가 있으면 바로 보낸다.
			synchronized (this) {
				if (!inFlight && pendingCount > 0) {
					dispatch();
				}
			}
		}

		private void scheduleReconnect() {
			if (closed) {
				return;
			}
			int delay = backoffMillis.get();
			backoffMillis.set(Math.min(delay * 2, MAX_BACKOFF_MILLIS));
			log.debug("HTTP 재연결 예약: {} #{} ({}ms 후)", spec.endpoint(), index, delay);
			runtime.group().schedule(() -> {
				if (!closed && channel == null) {
					doConnect();
				}
			}, delay, TimeUnit.MILLISECONDS);
		}

		/** 연결 확인용 GET. 응답 상태만 본다. */
		synchronized CompletableFuture<FullHttpResponseSummary> probe() {
			CompletableFuture<FullHttpResponseSummary> future = new CompletableFuture<>();
			Channel current = channel;
			if (current == null || !current.isActive()) {
				future.completeExceptionally(new IllegalStateException("연결이 없습니다"));
				return future;
			}
			probe = future;
			inFlight = true;
			FullHttpRequest request = request(HttpMethod.GET, Unpooled.EMPTY_BUFFER);
			current.writeAndFlush(request);
			return future;
		}

		private synchronized void failProbe(Throwable cause) {
			if (probe != null) {
				probe.completeExceptionally(cause);
				probe = null;
				inFlight = false;
			}
		}

		@Override
		public boolean isWritable() {
			Channel current = channel;
			return current != null && current.isActive() && pendingBytes < spec.batchBytes() * PENDING_LIMIT_FACTOR;
		}

		@Override
		public synchronized boolean send(ByteBuf record) {
			try {
				Channel current = channel;
				if (current == null || !current.isActive() || closed) {
					return false;
				}
				if (pending == null) {
					pending = runtime.allocator().directBuffer(Math.min(spec.batchBytes() + 1024, 1 << 20));
				}
				writer.append(pending, record, pendingCount == 0, System.currentTimeMillis());
				pendingCount++;
				pendingBytes = pending.readableBytes();
				if (!inFlight && pending.readableBytes() >= spec.batchBytes()) {
					dispatch();
				}
				return true;
			}
			finally {
				record.release();
			}
		}

		@Override
		public synchronized void flush() {
			if (!inFlight && pendingCount > 0) {
				dispatch();
			}
		}

		/** 쌓인 본문을 요청 하나로 보낸다. 잠금 안에서만 부른다. */
		private void dispatch() {
			Channel current = channel;
			if (current == null || !current.isActive() || pending == null) {
				return;
			}
			writer.finish(pending);
			ByteBuf body = pending;
			inFlightCount = pendingCount;
			inFlightBytes = body.readableBytes();
			pending = null;
			pendingCount = 0;
			pendingBytes = 0;
			inFlight = true;
			long seq = ++requestSeq;
			current.writeAndFlush(request(HttpMethod.POST, body));
			current.eventLoop().schedule(() -> onTimeout(current, seq), requestTimeoutMillis, TimeUnit.MILLISECONDS);
		}

		private FullHttpRequest request(HttpMethod method, ByteBuf body) {
			FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, spec.httpPath(), body);
			request.headers()
					.set(HttpHeaderNames.HOST, hostHeader)
					.set(HttpHeaderNames.USER_AGENT, "tester-log-generator")
					.set(HttpHeaderNames.ACCEPT, "*/*")
					.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
					.set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
			if (method == HttpMethod.POST) {
				request.headers().set(HttpHeaderNames.CONTENT_TYPE, spec.httpFormat().contentType());
			}
			// 사용자가 준 헤더가 기본값보다 우선한다(Content-Type 을 바꿔야 하는 수집기도 있다).
			for (Map.Entry<String, String> header : extraHeaders) {
				request.headers().set(header.getKey(), header.getValue());
			}
			return request;
		}

		private void onResponse(Channel ch, FullHttpResponse response) {
			int status = response.status().code();
			// 이유 문구를 안 보내는 서버가 많다(Tomcat 등). 비어 있으면 표준 문구로 채운다.
			String reason = response.status().reasonPhrase();
			if (reason == null || reason.isBlank()) {
				reason = io.netty.handler.codec.http.HttpResponseStatus.valueOf(status).reasonPhrase();
			}
			String statusLine = (status + " " + reason).trim();
			synchronized (this) {
				if (probe != null) {
					probe.complete(new FullHttpResponseSummary(status, statusLine));
					probe = null;
					inFlight = false;
				}
				else if (inFlight) {
					inFlight = false;
					if (status >= 200 && status < 300) {
						lastError = null;
					}
					else {
						failedRecords.add(inFlightCount);
						failedBytes.add(inFlightBytes);
						lastError = "HTTP " + statusLine + snippet(response.content());
					}
				}
				if (!HttpUtil.isKeepAlive(response)) {
					ch.close();
					return;
				}
				if (pendingCount > 0) {
					dispatch();
				}
			}
		}

		/** 응답을 기다리던 중 끊겼으면 그 요청은 실패로 센다. 쌓인 본문은 다시 붙으면 보낸다. */
		private synchronized void onDisconnected() {
			if (probe != null) {
				probe.completeExceptionally(new IllegalStateException("응답 전에 연결이 끊겼습니다"));
				probe = null;
			}
			else if (inFlight) {
				failedRecords.add(inFlightCount);
				failedBytes.add(inFlightBytes);
				if (lastError == null) {
					lastError = "응답을 받기 전에 연결이 끊겼습니다";
				}
			}
			inFlight = false;
		}

		private void onTimeout(Channel ch, long seq) {
			synchronized (this) {
				if (!inFlight || requestSeq != seq || probe != null) {
					return;
				}
				lastError = "HTTP 응답 시간 초과(" + requestTimeoutMillis / 1000 + "초) — 수집 서버가 응답하지 않습니다";
			}
			ch.close();
		}

		void drain(long deadline) {
			while (System.currentTimeMillis() < deadline) {
				synchronized (this) {
					Channel current = channel;
					if (current == null || !current.isActive()) {
						return;
					}
					if (!inFlight) {
						if (pendingCount == 0) {
							return;
						}
						dispatch();
					}
				}
				try {
					Thread.sleep(10);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}

		synchronized void close() {
			Channel current = channel;
			channel = null;
			if (pending != null) {
				pending.release();
				pending = null;
			}
			if (current != null) {
				current.close();
			}
		}
	}

	/** 오류 본문 앞부분을 한 줄로. 수집기가 이유를 본문에 적어 주는 경우가 많다(예: HEC "Invalid token"). */
	private static String snippet(ByteBuf content) {
		if (content == null || !content.isReadable()) {
			return "";
		}
		int length = Math.min(content.readableBytes(), ERROR_SNIPPET_CHARS * 4);
		String text = content.toString(content.readerIndex(), length, StandardCharsets.UTF_8)
				.replaceAll("\\s+", " ").trim();
		if (text.length() > ERROR_SNIPPET_CHARS) {
			text = text.substring(0, ERROR_SNIPPET_CHARS) + "…";
		}
		return text.isEmpty() ? "" : " — " + text;
	}
}
