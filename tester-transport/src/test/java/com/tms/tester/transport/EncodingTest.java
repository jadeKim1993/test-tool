package com.tms.tester.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * 인코딩별로 <b>실제 회선에 나가는 바이트</b>를 확인한다.
 * <p>문자열로 비교하면 인코딩 버그가 그대로 가려지므로 원시 바이트를 받아서 검증한다.
 */
class EncodingTest {

	/** 한글이 들어간 템플릿. 고정 문자열과 choice 값이 같은 인코딩으로 나가야 한다. */
	private static final String KOREAN_TEMPLATE = "사용자=${choice(홍길동)} 상태=정상";

	private NettyRuntime runtime;
	private TransportRegistry registry;
	private ByteCollector sink;

	@BeforeEach
	void setUp() throws Exception {
		runtime = new NettyRuntime(2);
		registry = TransportRegistry.standard();
		sink = new ByteCollector();
	}

	@AfterEach
	void tearDown() {
		sink.close();
		runtime.close();
	}

	private byte[] sendOnce(String template, WireEncoding encoding, Framing framing) throws Exception {
		TargetSpec target = new TargetSpec("sink", Protocol.TCP, "127.0.0.1", sink.port(),
				1, framing, 1024, TargetSpec.DEFAULT_SEND_BUFFER, 2000);
		RunSpec spec = new RunSpec(1L, "encoding-test", target,
				List.of(TemplateSource.of(1L, "t", template)),
				com.tms.tester.common.SelectionStrategy.SEQUENTIAL, 50, 1, null, 1L,
				// 인코딩은 대상이 아니라 실행(= 카테고리) 설정이다(§16.14).
				com.tms.tester.common.OverflowPolicy.BLOCK, encoding);

		LoadGenerator generator = new LoadGenerator(spec, runtime, registry);
		generator.start();
		long deadline = System.currentTimeMillis() + 4000;
		while (sink.bytes().length == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		generator.stop();
		Thread.sleep(200);
		return sink.bytes();
	}

	/** 개행 프레이밍에서 첫 레코드만 떼어낸다(정지 신호 전에 여러 건이 나갈 수 있다). */
	private static byte[] firstLine(byte[] wire) {
		for (int i = 0; i < wire.length; i++) {
			if (wire[i] == '\n') {
				byte[] line = new byte[i];
				System.arraycopy(wire, 0, line, 0, i);
				return line;
			}
		}
		return wire;
	}

	@Test
	void UTF8_은_UTF8_바이트로_나간다() throws Exception {
		byte[] line = firstLine(sendOnce(KOREAN_TEMPLATE, WireEncoding.UTF_8, Framing.NEWLINE));
		String expected = "사용자=홍길동 상태=정상";
		assertThat(line).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
		System.out.printf("[UTF-8 ] %d bytes → %s%n", line.length,
				new String(line, StandardCharsets.UTF_8));
	}

	@Test
	void CP949_는_MS949_바이트로_나간다() throws Exception {
		byte[] line = firstLine(sendOnce(KOREAN_TEMPLATE, WireEncoding.CP949, Framing.NEWLINE));
		Charset ms949 = Charset.forName("x-windows-949");
		String expected = "사용자=홍길동 상태=정상";

		assertThat(line).isEqualTo(expected.getBytes(ms949));
		// UTF-8 과 실제로 다른 바이트여야 한다(그냥 통과하는 테스트가 되지 않도록 확인).
		assertThat(line).isNotEqualTo(expected.getBytes(StandardCharsets.UTF_8));
		// 되돌려 읽으면 원문이 나온다
		assertThat(new String(line, ms949)).isEqualTo(expected);
		System.out.printf("[CP949 ] %d bytes (UTF-8 이면 %d bytes) → %s%n",
				line.length, expected.getBytes(StandardCharsets.UTF_8).length,
				new String(line, ms949));
	}

	@Test
	void BASE64_는_디코딩하면_원문이_나온다() throws Exception {
		byte[] wire = firstLine(sendOnce(KOREAN_TEMPLATE, WireEncoding.BASE64, Framing.NEWLINE));
		String line = new String(wire, StandardCharsets.US_ASCII);

		// Base64 출력은 ASCII 라 개행 프레이밍과 충돌하지 않는다
		assertThat(line).matches("[A-Za-z0-9+/]+=*");
		assertThat(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8))
				.isEqualTo("사용자=홍길동 상태=정상");
		System.out.printf("[BASE64] %s → %s%n", line,
				new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8));
	}

	@Test
	void BASE64_와_길이프리픽스를_함께_쓰면_길이는_인코딩_후_기준이다() throws Exception {
		byte[] wire = sendOnce("abcd", WireEncoding.BASE64, Framing.LENGTH_PREFIX);
		int declared = ((wire[0] & 0xFF) << 24) | ((wire[1] & 0xFF) << 16)
				| ((wire[2] & 0xFF) << 8) | (wire[3] & 0xFF);
		// 선언한 길이만큼만 읽어 첫 레코드를 떼어낸다
		String payload = new String(wire, 4, declared, StandardCharsets.US_ASCII);

		assertThat(declared).isEqualTo(payload.length());
		assertThat(new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)).isEqualTo("abcd");
		System.out.printf("[BASE64+LEN] 선언 길이=%d 실제=%d payload=%s%n",
				declared, payload.length(), payload);
	}

	/** 수신 바이트를 그대로 모으는 최소 서버. */
	private static final class ByteCollector implements AutoCloseable {

		private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
		private final Channel channel;
		private final List<byte[]> chunks = new CopyOnWriteArrayList<>();

		ByteCollector() throws InterruptedException {
			channel = new ServerBootstrap()
					.group(group)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
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
			// 길이를 재는 사이에도 수신이 계속되므로 먼저 스냅샷을 뜬다.
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
}
