package com.tms.tester.transport;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * 애플리케이션 전역에서 공유하는 Netty I/O 자원.
 * <p>전송 작업마다 이벤트 루프를 새로 만들면 스레드가 폭증하므로, 루프 그룹과
 * 버퍼 풀은 하나만 두고 작업은 그 위에 채널만 얹는다.
 * Linux 에서는 epoll 네이티브 전송을 사용한다(syscall 비용이 NIO 보다 낮다).
 */
public final class NettyRuntime implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(NettyRuntime.class);

	private final EventLoopGroup group;
	private final boolean nativeTransport;
	private final ByteBufAllocator allocator;

	public NettyRuntime() {
		this(Math.max(2, Runtime.getRuntime().availableProcessors()));
	}

	public NettyRuntime(int ioThreads) {
		this.nativeTransport = Epoll.isAvailable();
		DefaultThreadFactory threadFactory = new DefaultThreadFactory("tester-io", true);
		this.group = nativeTransport
				? new MultiThreadIoEventLoopGroup(ioThreads, threadFactory, EpollIoHandler.newFactory())
				: new MultiThreadIoEventLoopGroup(ioThreads, threadFactory, NioIoHandler.newFactory());
		// preferDirect=true: 소켓 기록 시 힙→다이렉트 복사를 피한다.
		this.allocator = new PooledByteBufAllocator(true);
		log.info("Netty 전송 초기화: transport={}, ioThreads={}", nativeTransport ? "epoll(native)" : "nio", ioThreads);
	}

	public EventLoopGroup group() {
		return group;
	}

	public ByteBufAllocator allocator() {
		return allocator;
	}

	public boolean isNativeTransport() {
		return nativeTransport;
	}

	public String transportName() {
		return nativeTransport ? "epoll" : "nio";
	}

	public Class<? extends SocketChannel> socketChannelType() {
		return nativeTransport ? EpollSocketChannel.class : NioSocketChannel.class;
	}

	public Class<? extends DatagramChannel> datagramChannelType() {
		return nativeTransport ? EpollDatagramChannel.class : NioDatagramChannel.class;
	}

	@Override
	public void close() {
		group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
	}
}
