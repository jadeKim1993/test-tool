package com.tms.tester.transport;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.LongAdder;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.ByteProcessor;

/**
 * 테스트용 수신 카운터. 개행 개수로 로그 건수를 센다.
 * (운영에서 쓰는 검증 도구도 같은 방식이다.)
 */
final class CountingSink implements AutoCloseable {

	private final EventLoopGroup bossGroup;
	private final EventLoopGroup workerGroup;
	private final Channel channel;
	private final LongAdder lines = new LongAdder();
	private final LongAdder bytes = new LongAdder();

	private CountingSink(EventLoopGroup boss, EventLoopGroup worker, Channel channel) {
		this.bossGroup = boss;
		this.workerGroup = worker;
		this.channel = channel;
	}

	static CountingSink tcp() throws InterruptedException {
		EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
		EventLoopGroup worker = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
		CountingSink[] holder = new CountingSink[1];
		ServerBootstrap bootstrap = new ServerBootstrap()
				.group(boss, worker)
				.channel(NioServerSocketChannel.class)
				.childOption(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
							@Override
							public void channelRead(ChannelHandlerContext ctx, Object msg) {
								ByteBuf buf = (ByteBuf) msg;
								try {
									holder[0].count(buf);
								}
								finally {
									buf.release();
								}
							}
						});
					}
				});
		Channel serverChannel = bootstrap.bind(0).sync().channel();
		CountingSink sink = new CountingSink(boss, worker, serverChannel);
		holder[0] = sink;
		return sink;
	}

	static CountingSink udp() throws InterruptedException {
		EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
		CountingSink[] holder = new CountingSink[1];
		Bootstrap bootstrap = new Bootstrap()
				.group(group)
				.channel(NioDatagramChannel.class)
				.option(ChannelOption.SO_RCVBUF, 16 * 1024 * 1024)
				.handler(new ChannelInboundHandlerAdapter() {
					@Override
					public void channelRead(ChannelHandlerContext ctx, Object msg) {
						io.netty.channel.socket.DatagramPacket packet = (io.netty.channel.socket.DatagramPacket) msg;
						try {
							holder[0].count(packet.content());
						}
						finally {
							packet.release();
						}
					}
				});
		Channel channel = bootstrap.bind(0).sync().channel();
		CountingSink sink = new CountingSink(group, group, channel);
		holder[0] = sink;
		return sink;
	}

	/**
	 * 개행 개수로 건수를 센다.
	 * <p>바이트마다 {@code getByte()} 를 부르면 기가비트급에서 수신측이 먼저 병목이 된다.
	 * {@link ByteProcessor} 로 한 번에 훑어 그 비용을 없앤다.
	 */
	private void count(ByteBuf buf) {
		bytes.add(buf.readableBytes());
		NewlineCounter counter = new NewlineCounter();
		buf.forEachByte(counter);
		lines.add(counter.count);
	}

	private static final class NewlineCounter implements ByteProcessor {

		private int count;

		@Override
		public boolean process(byte value) {
			if (value == '\n') {
				count++;
			}
			return true;
		}
	}

	int port() {
		return ((InetSocketAddress) channel.localAddress()).getPort();
	}

	long lines() {
		return lines.sum();
	}

	long bytes() {
		return bytes.sum();
	}

	@Override
	public void close() {
		channel.close().syncUninterruptibly();
		bossGroup.shutdownGracefully();
		if (workerGroup != bossGroup) {
			workerGroup.shutdownGracefully();
		}
	}
}
