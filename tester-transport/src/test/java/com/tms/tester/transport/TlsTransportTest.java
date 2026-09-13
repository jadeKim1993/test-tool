package com.tms.tester.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.tms.tester.common.Framing;
import com.tms.tester.common.Protocol;
import com.tms.tester.common.WireEncoding;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * TLS 송신이 실제로 암호화된 회선 위에서 동작하는지 확인한다.
 * <p>자체 서명 인증서를 쓰는 서버에도 (신뢰 체인 검증을 하지 않으므로) 연결되고, 서버가
 * 평문으로만 응답하는 경우엔 핸드셰이크 자체가 실패해야 한다.
 */
class TlsTransportTest {

	private NettyRuntime runtime;
	private TransportRegistry registry;

	@AfterEach
	void tearDown() {
		if (runtime != null) {
			runtime.close();
		}
	}

	@Test
	void TLS_핸드셰이크_후_수신측이_평문으로_복호화한다() throws Exception {
		runtime = new NettyRuntime(2);
		registry = TransportRegistry.standard();
		assertThat(registry.supports("TLS")).isTrue();

		try (TlsEchoServer server = new TlsEchoServer()) {
			TargetSpec target = new TargetSpec("tls-sink", Protocol.TLS, "127.0.0.1", server.port(),
					1, Framing.NEWLINE, 1024, TargetSpec.DEFAULT_SEND_BUFFER, 3000);
			RunSpec spec = new RunSpec(1L, "tls-test", target,
					List.of(TemplateSource.of(1L, "t", "hello-tls")),
					com.tms.tester.common.SelectionStrategy.SEQUENTIAL, 20, 1, null, 1L,
					com.tms.tester.common.OverflowPolicy.BLOCK, WireEncoding.UTF_8);

			LoadGenerator generator = new LoadGenerator(spec, runtime, registry);
			generator.start();
			try {
				long deadline = System.currentTimeMillis() + 5000;
				while (server.bytes().length == 0 && System.currentTimeMillis() < deadline) {
					Thread.sleep(50);
				}
			}
			finally {
				generator.stop();
			}

			byte[] received = server.bytes();
			assertThat(received).isNotEmpty();
			assertThat(new String(received, StandardCharsets.UTF_8)).contains("hello-tls");
		}
	}

	@Test
	void 평문_서버에_TLS로_접속하면_핸드셰이크_실패로_연결이_거부된다() throws Exception {
		runtime = new NettyRuntime(2);
		registry = TransportRegistry.standard();

		try (PlainTextServer server = new PlainTextServer()) {
			TargetSpec target = new TargetSpec("tls-sink", Protocol.TLS, "127.0.0.1", server.port(),
					1, Framing.NEWLINE, 1024, TargetSpec.DEFAULT_SEND_BUFFER, 1500);
			try (LogSender sender = registry.create(target, 1, runtime)) {
				assertThatThrownBy(sender::connect).hasMessageContaining("핸드셰이크");
			}
		}
	}

	/** TLS 로 감싸고 평문으로 되돌려주지 않는, 그냥 받은 바이트를 모으는 서버. */
	private static final class TlsEchoServer implements AutoCloseable {

		private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
		private final Channel channel;
		private final List<byte[]> chunks = new CopyOnWriteArrayList<>();

		TlsEchoServer() throws Exception {
			SelfSignedCertificate cert = new SelfSignedCertificate();
			SslContext serverSsl = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build();
			channel = new ServerBootstrap()
					.group(group)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(serverSsl.newHandler(ch.alloc()));
							ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
								@Override
								public void channelRead(ChannelHandlerContext ctx, Object msg) {
									ByteBuf buf = (ByteBuf) msg;
									try {
										byte[] data = new byte[buf.readableBytes()];
										buf.getBytes(buf.readerIndex(), data);
										chunks.add(data);
									}
									finally {
										buf.release();
									}
								}
							});
						}
					})
					.bind(0).sync().channel();
		}

		int port() {
			return ((InetSocketAddress) channel.localAddress()).getPort();
		}

		byte[] bytes() {
			List<byte[]> snapshot = List.copyOf(chunks);
			int total = snapshot.stream().mapToInt(c -> c.length).sum();
			byte[] all = new byte[total];
			int offset = 0;
			for (byte[] chunk : snapshot) {
				System.arraycopy(chunk, 0, all, offset, chunk.length);
				offset += chunk.length;
			}
			return all;
		}

		@Override
		public void close() {
			channel.close().syncUninterruptibly();
			group.shutdownGracefully();
		}
	}

	/** TLS 클라이언트가 접속했을 때 핸드셰이크 실패를 유발하기 위한, TLS 를 전혀 모르는 서버. */
	private static final class PlainTextServer implements AutoCloseable {

		private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
		private final Channel channel;

		PlainTextServer() throws InterruptedException {
			channel = new ServerBootstrap()
					.group(group)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
								@Override
								public void channelRead(ChannelHandlerContext ctx, Object msg) {
									io.netty.util.ReferenceCountUtil.release(msg);
								}
							});
						}
					})
					.bind(0).sync().channel();
		}

		int port() {
			return ((InetSocketAddress) channel.localAddress()).getPort();
		}

		@Override
		public void close() {
			channel.close().syncUninterruptibly();
			group.shutdownGracefully();
		}
	}
}
