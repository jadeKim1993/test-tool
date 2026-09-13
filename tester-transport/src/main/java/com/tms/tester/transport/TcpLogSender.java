package com.tms.tester.transport;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * TCP / TLS 송신기.
 * <p>워커마다 전용 커넥션을 주어 스레드 간 경합을 없앴고, 실제 {@code write()} 는
 * 워커가 16KB 단위로 모은 배치 버퍼 하나를 넘기는 형태다. 건당 write 대비
 * syscall 이 수십 분의 1 로 줄어드는 것이 10만 EPS 달성의 핵심이다.
 * <p>연결이 끊기면 지수 백오프로 자동 재연결하며, 그동안의 전송은 drop 으로 집계된다.
 * <p>{@code tls=true} 면 파이프라인 맨 앞에 {@link SslHandler} 를 붙여 TLS 로 감싼다.
 * 내부 테스트 서버가 대부분 자체 서명 인증서를 쓰므로 인증서 검증은 하지 않는다
 * (신뢰 체인이 아니라 암호화 자체를 테스트하는 용도).
 */
final class TcpLogSender implements LogSender {

	private static final Logger log = LoggerFactory.getLogger(TcpLogSender.class);

	private final TargetSpec spec;
	private final NettyRuntime runtime;
	private final boolean tls;
	private final Connection[] connections;
	private final Bootstrap bootstrap;

	private volatile boolean closed;
	private volatile String lastError;

	TcpLogSender(TargetSpec spec, int workerCount, NettyRuntime runtime, boolean tls) {
		this.spec = spec;
		this.runtime = runtime;
		this.tls = tls;
		int count = spec.connections() > 0 ? spec.connections() : workerCount;
		this.connections = new Connection[Math.max(1, count)];
		this.bootstrap = new Bootstrap()
				.group(runtime.group())
				.channel(runtime.socketChannelType())
				.option(ChannelOption.ALLOCATOR, runtime.allocator())
				.option(ChannelOption.TCP_NODELAY, true)
				.option(ChannelOption.SO_KEEPALIVE, true)
				.option(ChannelOption.SO_SNDBUF, spec.sendBufferBytes())
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, spec.connectTimeoutMs())
				// 수신측이 느릴 때 메모리가 무한정 늘지 않도록 상한을 둔다.
				.option(ChannelOption.WRITE_BUFFER_WATER_MARK,
						new WriteBufferWaterMark(spec.batchBytes() * 4, spec.batchBytes() * 16))
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						if (tls) {
							ch.pipeline().addLast(
									InsecureSslContext.INSTANCE.newHandler(ch.alloc(), spec.host(), spec.port()));
						}
						// 수신 데이터는 관심 대상이 아니다. 예외만 처리한다.
						ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
							@Override
							public void channelRead(ChannelHandlerContext ctx, Object msg) {
								io.netty.util.ReferenceCountUtil.release(msg);
							}

							@Override
							public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
								lastError = cause.getMessage();
								ctx.close();
							}
						});
					}
				});
	}

	/** TLS 컨텍스트는 실제로 TLS 대상을 쓸 때만 초기화되도록 지연 로딩한다. */
	private static final class InsecureSslContext {
		static final SslContext INSTANCE = build();

		private static SslContext build() {
			try {
				return SslContextBuilder.forClient()
						.trustManager(InsecureTrustManagerFactory.INSTANCE)
						.build();
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
		// 첫 커넥션은 동기로 확인해 잘못된 대상을 즉시 사용자에게 알린다.
		ChannelFuture first = connections[0].doConnect();
		if (!first.await(spec.connectTimeoutMs() + 500L, TimeUnit.MILLISECONDS)) {
			throw new java.net.ConnectException(spec.endpoint() + " 연결 시간 초과");
		}
		if (!first.isSuccess()) {
			throw new java.net.ConnectException(spec.endpoint() + " 연결 실패: " + first.cause().getMessage());
		}
		if (tls) {
			SslHandler sslHandler = first.channel().pipeline().get(SslHandler.class);
			if (!sslHandler.handshakeFuture().await(spec.connectTimeoutMs() + 500L, TimeUnit.MILLISECONDS)) {
				throw new java.net.ConnectException(spec.endpoint() + " TLS 핸드셰이크 시간 초과");
			}
			if (!sslHandler.handshakeFuture().isSuccess()) {
				throw new java.net.ConnectException(
						spec.endpoint() + " TLS 핸드셰이크 실패: " + sslHandler.handshakeFuture().cause().getMessage());
			}
		}
		for (int i = 1; i < connections.length; i++) {
			connections[i].doConnect();
		}
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
		boolean anyActive = false;
		boolean allActive = true;
		for (Connection connection : connections) {
			Channel channel = connection.channel;
			if (channel != null && channel.isActive()) {
				anyActive = true;
			}
			else {
				allActive = false;
			}
		}
		if (allActive) {
			return SenderState.CONNECTED;
		}
		return anyActive ? SenderState.RECONNECTING : SenderState.FAILED;
	}

	@Override
	public String lastError() {
		return lastError;
	}

	@Override
	public String describe() {
		return spec.endpoint() + " (커넥션 " + connections.length + "개, " + runtime.transportName() + ")";
	}

	@Override
	public void close() {
		closed = true;
		for (Connection connection : connections) {
			if (connection != null) {
				connection.close();
			}
		}
	}

	/** 커넥션 1개 = 워커 1개의 전용 창구. */
	private final class Connection implements SendEndpoint {

		private final int index;
		private final AtomicInteger backoffMillis = new AtomicInteger(500);
		private volatile Channel channel;

		private Connection(int index) {
			this.index = index;
		}

		ChannelFuture doConnect() {
			ChannelFuture future = bootstrap.connect(new InetSocketAddress(spec.host(), spec.port()));
			future.addListener(f -> {
				if (f.isSuccess()) {
					Channel connected = future.channel();
					channel = connected;
					backoffMillis.set(500);
					connected.closeFuture().addListener(cf -> {
						channel = null;
						scheduleReconnect();
					});
				}
				else {
					lastError = future.cause() == null ? "연결 실패" : future.cause().getMessage();
					scheduleReconnect();
				}
			});
			return future;
		}

		private void scheduleReconnect() {
			if (closed) {
				return;
			}
			int delay = backoffMillis.get();
			backoffMillis.set(Math.min(delay * 2, 10_000));
			log.debug("TCP 재연결 예약: {} #{} ({}ms 후)", spec.endpoint(), index, delay);
			runtime.group().schedule(() -> {
				if (!closed && channel == null) {
					doConnect();
				}
			}, delay, TimeUnit.MILLISECONDS);
		}

		@Override
		public boolean isWritable() {
			Channel current = channel;
			return current != null && current.isActive() && current.isWritable();
		}

		@Override
		public boolean send(ByteBuf payload) {
			Channel current = channel;
			if (current == null || !current.isActive()) {
				payload.release();
				return false;
			}
			// voidPromise: 건당 promise 객체 생성을 없앤다. 실패는 채널 종료로 감지한다.
			current.write(payload, current.voidPromise());
			return true;
		}

		@Override
		public void flush() {
			Channel current = channel;
			if (current != null && current.isActive()) {
				current.flush();
			}
		}

		void close() {
			Channel current = channel;
			channel = null;
			if (current != null) {
				current.close();
			}
		}
	}
}
