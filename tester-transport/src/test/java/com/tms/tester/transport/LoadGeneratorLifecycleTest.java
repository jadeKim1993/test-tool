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
	void 연결할_수_없는_대상은_즉시_오류를_알린다() {
		// 1번 포트는 권한/미사용이라 연결이 거부된다.
		TargetSpec target = new TargetSpec("sink", Protocol.TCP, "127.0.0.1", 1, 0, Framing.NEWLINE,
				4096, TargetSpec.DEFAULT_SEND_BUFFER, 2000);
		RunSpec spec = new RunSpec(4L, "unreachable", target,
				List.of(TemplateSource.of(1L, "t", TEMPLATE)),
				SelectionStrategy.ROUND_ROBIN, 1_000, 1, null, null, OverflowPolicy.DROP, WireEncoding.UTF_8);

		LoadGenerator generator = new LoadGenerator(spec, runtime, registry);
		try {
			generator.start();
			org.assertj.core.api.Assertions.fail("연결 실패 예외가 발생해야 한다");
		}
		catch (Exception e) {
			assertThat(generator.state()).isEqualTo(RunState.FAILED);
			assertThat(e.getMessage()).contains("127.0.0.1:1");
		}
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
