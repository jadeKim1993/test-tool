package com.tms.tester.transport;

import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.DatagramChannel;

/**
 * UDP 송신기.
 * <p>syslog 관례대로 datagram 1개 = 로그 1건이다. 건당 소켓 호출이 발생하는 구조이므로
 * 다음 두 가지로 비용을 낮춘다.
 * <ul>
 *   <li><b>connected UDP</b>: 소켓을 대상에 connect 해두면 건마다 주소를 넘길 필요가 없고
 *       {@code DatagramPacket} 객체 생성도 사라진다.</li>
 *   <li><b>write 누적 + 틱 단위 flush</b>: Netty 가 출력 버퍼에 모아두었다가 한 번에
 *       내보내므로 wakeup 비용이 건당이 아니라 틱당으로 줄어든다.</li>
 * </ul>
 * 워커마다 전용 소켓을 주어 경합도 제거한다.
 */
final class UdpLogSender implements LogSender {

	private static final Logger log = LoggerFactory.getLogger(UdpLogSender.class);

	/** 이 크기를 넘으면 IP 단편화가 일어나 유실 위험이 커진다. */
	static final int SAFE_DATAGRAM_BYTES = 1400;

	private final TargetSpec spec;
	private final NettyRuntime runtime;
	private final Socket[] sockets;
	private final Bootstrap bootstrap;
	private final LongAdder unreachable = new LongAdder();

	private volatile boolean closed;
	private volatile String lastError;

	UdpLogSender(TargetSpec spec, int workerCount, NettyRuntime runtime) {
		this.spec = spec;
		this.runtime = runtime;
		int count = spec.connections() > 0 ? spec.connections() : workerCount;
		this.sockets = new Socket[Math.max(1, count)];
		this.bootstrap = new Bootstrap()
				.group(runtime.group())
				.channel(runtime.datagramChannelType())
				.option(ChannelOption.ALLOCATOR, runtime.allocator())
				.option(ChannelOption.SO_SNDBUF, Math.max(spec.sendBufferBytes(), 8 * 1024 * 1024))
				.option(ChannelOption.WRITE_BUFFER_WATER_MARK,
						new WriteBufferWaterMark(256 * 1024, 1024 * 1024))
				.handler(new ChannelInitializer<DatagramChannel>() {
					@Override
					protected void initChannel(DatagramChannel ch) {
						ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
							@Override
							public void channelRead(ChannelHandlerContext ctx, Object msg) {
								io.netty.util.ReferenceCountUtil.release(msg);
							}

							@Override
							public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
								if (cause instanceof PortUnreachableException) {
									// 수신 포트가 닫혀 있다는 ICMP 응답. UDP 특성상 전송은 계속한다.
									unreachable.increment();
									lastError = "대상 포트 도달 불가(ICMP): " + spec.endpoint();
									return;
								}
								lastError = cause.getMessage();
							}
						});
					}
				});
	}

	@Override
	public void connect() throws Exception {
		InetSocketAddress remote = new InetSocketAddress(spec.host(), spec.port());
		if (remote.isUnresolved()) {
			throw new java.net.UnknownHostException("호스트를 찾을 수 없습니다: " + spec.host());
		}
		for (int i = 0; i < sockets.length; i++) {
			io.netty.channel.ChannelFuture future = bootstrap.connect(remote);
			if (!future.await(spec.connectTimeoutMs(), TimeUnit.MILLISECONDS) || !future.isSuccess()) {
				future.cancel(true);
				throw new java.net.SocketException("UDP 소켓 생성 실패: " + spec.endpoint()
						+ (future.cause() != null ? " (" + future.cause().getMessage() + ")" : ""));
			}
			sockets[i] = new Socket(future.channel());
		}
		log.info("UDP 송신 준비 완료: {} (소켓 {}개)", spec.endpoint(), sockets.length);
	}

	@Override
	public ByteBufAllocator allocator() {
		return runtime.allocator();
	}

	@Override
	public SendEndpoint endpoint(int workerIndex) {
		return sockets[workerIndex % sockets.length];
	}

	@Override
	public SenderState state() {
		if (closed) {
			return SenderState.CLOSED;
		}
		for (Socket socket : sockets) {
			if (socket == null || !socket.channel.isActive()) {
				return SenderState.FAILED;
			}
		}
		return SenderState.CONNECTED;
	}

	@Override
	public String lastError() {
		return lastError;
	}

	@Override
	public String describe() {
		return spec.endpoint() + " (소켓 " + sockets.length + "개, " + runtime.transportName() + ")";
	}

	@Override
	public void close() {
		closed = true;
		for (Socket socket : sockets) {
			if (socket != null) {
				socket.channel.close();
			}
		}
	}

	private static final class Socket implements SendEndpoint {

		private final Channel channel;

		private Socket(Channel channel) {
			this.channel = channel;
		}

		@Override
		public boolean isWritable() {
			return channel.isActive() && channel.isWritable();
		}

		@Override
		public boolean send(ByteBuf payload) {
			if (!channel.isActive()) {
				payload.release();
				return false;
			}
			// connect 된 소켓이므로 ByteBuf 를 그대로 보내면 된다(DatagramPacket 생성 없음).
			channel.write(payload, channel.voidPromise());
			return true;
		}

		@Override
		public void flush() {
			if (channel.isActive()) {
				channel.flush();
			}
		}
	}
}
