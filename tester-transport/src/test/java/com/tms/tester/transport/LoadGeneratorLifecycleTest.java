package com.tms.tester.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tms.tester.common.Framing;
import com.tms.tester.common.OverflowPolicy;
import com.tms.tester.common.Protocol;
import com.tms.tester.common.RunState;
import com.tms.tester.common.SelectionStrategy;
import com.tms.tester.common.WireEncoding;

/**
 * 실행 생명주기 검증. 처리량이 아니라 "정해진 조건에서 제대로 멈추는가"를 본다.
 * <p>타이밍에 의존하지 않으므로 일반 빌드에 포함한다
 * (처리량 측정은 {@link LoadGeneratorThroughputTest}, {@code ./gradlew perf}).
 */
class LoadGeneratorLifecycleTest {

	private static final String TEMPLATE = "${ts} ${ip} ${choice(GET,POST)} ${int(1,999)}";

	private NettyRuntime runtime;
	private TransportRegistry registry;

	@BeforeEach
	void setUp() {
		runtime = new NettyRuntime(2);
		registry = TransportRegistry.standard();
	}

	@AfterEach
	void tearDown() {
		runtime.close();
	}

	@Test
	void 건수_제한에_도달하면_자동_종료된다() throws Exception {
		try (CountingSink sink = CountingSink.tcp()) {
			TargetSpec target = new TargetSpec("sink", Protocol.TCP, "127.0.0.1", sink.port(), 0, Framing.NEWLINE,
					4096, TargetSpec.DEFAULT_SEND_BUFFER, 2000);
			RunSpec spec = new RunSpec(2L, "limited", target,
					List.of(TemplateSource.of(1L, "t", TEMPLATE)),
					SelectionStrategy.ROUND_ROBIN, 20_000, 2, null, 10_000L, OverflowPolicy.BLOCK, WireEncoding.UTF_8);

			LoadGenerator generator = new LoadGenerator(spec, runtime, registry);
			generator.start();

			long deadline = System.currentTimeMillis() + 10_000;
			while (generator.isActive() && System.currentTimeMillis() < deadline) {
				Thread.sleep(100);
			}

			assertThat(generator.state()).isEqualTo(RunState.COMPLETED);
			assertThat(generator.completionReason()).contains("10000");
			// 감시 주기(200ms) 만큼은 넘길 수 있지만 크게 벗어나면 안 된다.
			assertThat(generator.statistics().sent()).isBetween(10_000L, 20_000L);
		}
	}

	@Test
	void 시간_제한에_도달하면_자동_종료된다() throws Exception {
		try (CountingSink sink = CountingSink.tcp()) {
			TargetSpec target = new TargetSpec("sink", Protocol.TCP, "127.0.0.1", sink.port(), 0, Framing.NEWLINE,
					4096, TargetSpec.DEFAULT_SEND_BUFFER, 2000);
			RunSpec spec = new RunSpec(3L, "timed", target,
					List.of(TemplateSource.of(1L, "t", TEMPLATE)),
					SelectionStrategy.ROUND_ROBIN, 1_000, 1, 1, null, OverflowPolicy.DROP, WireEncoding.UTF_8);

			LoadGenerator generator = new LoadGenerator(spec, runtime, registry);
			generator.start();

			long deadline = System.currentTimeMillis() + 10_000;
			while (generator.isActive() && System.currentTimeMillis() < deadline) {
				Thread.sleep(100);
			}

			assertThat(generator.state()).isEqualTo(RunState.COMPLETED);
			assertThat(generator.completionReason()).contains("실행 시간");
		}
	}

	@Test
	void 연결_테스트용_connect_는_연결_실패를_그대로_알린다() {
		// 전송 대상 화면의 "연결 테스트"는 이 결과를 쓴다. 작업이 재시도를 계속하게 된 뒤에도
		// 연결 테스트까지 성공으로 보이면 안 된다.
		TargetSpec target = new TargetSpec("sink", Protocol.TCP, "127.0.0.1", freePort(), 0, Framing.NEWLINE,
				4096, TargetSpec.DEFAULT_SEND_BUFFER, 1000);
		try (LogSender sender = registry.create(target, 1, runtime)) {
			org.assertj.core.api.Assertions.assertThatThrownBy(sender::connect).hasMessageContaining("연결 실패");
		}
	}

	@Test
	void 수신_서버가_꺼져_있어도_시작하고_서버가_뜨면_이어서_보낸다() throws Exception {
		int port = freePort();
		TargetSpec target = new TargetSpec("sink", Protocol.TCP, "127.0.0.1", port, 0, Framing.NEWLINE,
				1024, TargetSpec.DEFAULT_SEND_BUFFER, 1000);
		RunSpec spec = new RunSpec(4L, "late-server", target,
				List.of(TemplateSource.of(1L, "t", TEMPLATE)),
				SelectionStrategy.ROUND_ROBIN, 500, 1, null, null, OverflowPolicy.DROP, WireEncoding.UTF_8);

		LoadGenerator generator = new LoadGenerator(spec, runtime, registry);
		generator.start();                          // 예전에는 여기서 예외로 거부됐다
		try {
			Thread.sleep(800);
			MetricSample down = generator.sample();
			assertThat(down.state()).isEqualTo(RunState.RUNNING);
			assertThat(down.connection()).isEqualTo(SenderState.RECONNECTING);
			assertThat(down.lastError()).contains("Connection refused");
			assertThat(generator.statistics().dropped()).isPositive();

			try (CountingSink sink = CountingSink.tcp(port)) {
				awaitLines(sink, 50, 8_000);
				MetricSample up = generator.sample();
				assertThat(up.connection()).isEqualTo(SenderState.CONNECTED);
				// 연결된 뒤에는 끊겼을 때의 오류가 남아 있지 않아야 한다(화면에 계속 경고가 뜨지 않도록).
				assertThat(up.lastError()).isNull();
			}
		}
		finally {
			generator.stop();
		}
	}

	@Test
	void 실행_중_서버가_죽었다_살아나도_작업은_멈추지_않고_이어서_보낸다() throws Exception {
		int port = freePort();
		TargetSpec target = new TargetSpec("sink", Protocol.TCP, "127.0.0.1", port, 0, Framing.NEWLINE,
				1024, TargetSpec.DEFAULT_SEND_BUFFER, 1000);
		RunSpec spec = new RunSpec(6L, "flapping-server", target,
				List.of(TemplateSource.of(1L, "t", TEMPLATE)),
				SelectionStrategy.ROUND_ROBIN, 500, 1, null, null, OverflowPolicy.DROP, WireEncoding.UTF_8);

		LoadGenerator generator = new LoadGenerator(spec, runtime, registry);
		CountingSink first = CountingSink.tcp(port);
		generator.start();
		try {
			awaitLines(first, 50, 5_000);
			first.close();                          // 수신 서버 다운
			Thread.sleep(1_500);
			assertThat(generator.state()).isEqualTo(RunState.RUNNING);
			assertThat(generator.sample().connection()).isEqualTo(SenderState.RECONNECTING);

			try (CountingSink second = CountingSink.tcp(port)) {   // 같은 주소로 재기동
				awaitLines(second, 50, 8_000);
				assertThat(generator.state()).isEqualTo(RunState.RUNNING);
			}
		}
		finally {
			generator.stop();
		}
	}

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void awaitLines(CountingSink sink, long atLeast, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (sink.lines() < atLeast && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(sink.lines()).as("수신 서버가 받은 줄 수").isGreaterThanOrEqualTo(atLeast);
	}

	@Test
	void 실행중_템플릿_교체가_반영된다() throws Exception {
		try (CountingSink sink = CountingSink.tcp()) {
			TargetSpec target = new TargetSpec("sink", Protocol.TCP, "127.0.0.1", sink.port(), 0, Framing.NEWLINE,
					1024, TargetSpec.DEFAULT_SEND_BUFFER, 2000);
			RunSpec spec = new RunSpec(5L, "swap", target,
					List.of(TemplateSource.of(1L, "before", "BEFORE")),
					SelectionStrategy.ROUND_ROBIN, 200, 1, null, null, OverflowPolicy.DROP, WireEncoding.UTF_8);

			LoadGenerator generator = new LoadGenerator(spec, runtime, registry);
			generator.start();
			Thread.sleep(500);
			long beforeSwap = generator.statistics().sent();

			generator.updateTemplates(List.of(TemplateSource.of(2L, "after", "AFTER")));
			Thread.sleep(500);
			generator.stop();

			// 교체 후에도 전송이 계속되어야 한다(끊기지 않음)
			assertThat(generator.statistics().sent()).isGreaterThan(beforeSwap);
			assertThat(sink.lines()).isPositive();
		}
	}
}
