package com.tms.tester.generator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * 렌더링 처리량 회귀 감지용. 정밀 측정은 JMH 로 하되,
 * 여기서는 "10만 EPS 를 낼 수 있는 자릿수인가"만 확인한다.
 *
 * <p>벽시계 측정이라 다른 테스트와 CPU 를 나눠 쓰면 값이 크게 눌린다.
 * 일반 {@code build} 에서 제외하고 {@code ./gradlew perf} 로 단독 실행한다.
 */
@Tag("perf")
class RenderThroughputTest {

	private static final String TOMCAT_ACCESS =
			"${ip as cip} - - [${ts(dd/MMM/yyyy:HH:mm:ss Z,en)}] \"${choice(GET:70,POST:25,PUT:5)} "
					+ "/api/v1/${choice(users,orders,items)}/${int(1,99999)} HTTP/1.1\" "
					+ "${choice(200:85,201:5,404:7,500:3)} ${int(120,48000)} \"-\" \"${dict(user_agents)}\" ${int(1,3000)}";

	@Test
	void 단일스레드_렌더링이_초당_100만건_이상이다() {
		CompiledTemplate template = new TemplateCompiler().compile(TOMCAT_ACCESS);
		RenderContext ctx = RenderContext.standalone();
		ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(64 * 1024);
		try {
			// 워밍업 (JIT 컴파일 유도)
			for (int i = 0; i < 200_000; i++) {
				buf.clear();
				template.render(buf, ctx);
			}

			int iterations = 1_000_000;
			ctx.tick(System.currentTimeMillis());
			long start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				buf.clear();
				template.render(buf, ctx);
			}
			long elapsed = System.nanoTime() - start;

			double nanosPerLog = (double) elapsed / iterations;
			long logsPerSecond = (long) (1_000_000_000L / nanosPerLog);
			System.out.printf("[렌더링] %.1f ns/건, 단일스레드 %,d EPS, 로그크기 %d B%n",
					nanosPerLog, logsPerSecond, buf.readableBytes());

			// 워커 1개로 100만 EPS 이상이면 4워커로 10만 EPS 목표에 10배 이상의 여유가 있다.
			assertThat(logsPerSecond).isGreaterThan(1_000_000L);
		}
		finally {
			buf.release();
		}
	}
}
