package com.tms.tester.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.tms.tester.common.SelectionStrategy;
import com.tms.tester.common.OverflowPolicy;
import com.tms.tester.common.RunState;
import com.tms.tester.common.Framing;
import com.tms.tester.common.Protocol;
import com.tms.tester.common.WireEncoding;

/**
 * 전송 엔진의 실제 처리량 검증.
 * <p>루프백으로 보내므로 네트워크 자체의 한계는 반영되지 않지만,
 * 생성/배치/syscall 경로의 병목은 그대로 드러난다.
 *
 * <p><b>벽시계 기반 측정이라 CPU 를 나눠 쓰면 값이 눌린다.</b> 병렬 빌드에서 다른 모듈과
 * 겹치면 엉뚱하게 실패하므로 일반 {@code build} 에서 제외하고
 * {@code ./gradlew perf} 로 단독 실행한다.
 */
@Tag("perf")
class LoadGeneratorThroughputTest {

	private static final String ACCESS_LOG =
			"${ip as cip} - - [${ts(dd/MMM/yyyy:HH:mm:ss Z,en)}] \"${choice(GET:70,POST:30)} "
					+ "/api/v1/${choice(users,orders,items)}/${int(1,99999)} HTTP/1.1\" "
					+ "${choice(200:85,404:15)} ${int(120,48000)} \"-\" \"${dict(user_agents)}\"";

	private NettyRuntime runtime;
	private TransportRegistry registry;

	@BeforeEach
	void setUp() {
		runtime = new NettyRuntime(4);
		registry = TransportRegistry.standard();
	}

	@AfterEach
	void tearDown() {
		runtime.close();
	}

	private RunSpec spec(String protocol, int port, int eps, int workers) {
		TargetSpec target = new TargetSpec("sink", protocol, "127.0.0.1", port, 0, Framing.NEWLINE,
				TargetSpec.DEFAULT_BATCH_BYTES, TargetSpec.DEFAULT_SEND_BUFFER, 2000);
		return new RunSpec(1L, "throughput-test", target,
				List.of(TemplateSource.of(1L, "access", ACCESS_LOG)),
				SelectionStrategy.WEIGHTED_RANDOM, eps, workers, null, null, OverflowPolicy.BLOCK, WireEncoding.UTF_8);
	}

	@Test
	void TCP_로_초당_10만건을_전송한다() throws Exception {
		try (CountingSink sink = CountingSink.tcp()) {
			LoadGenerator generator = new LoadGenerator(spec(Protocol.TCP, sink.port(), 100_000, 4), runtime, registry);
			generator.start();

			Thread.sleep(6000);
			generator.sample();
			generator.stop();
			Thread.sleep(500); // 수신측 잔여 처리 대기

			long sent = generator.statistics().sent();
			long received = sink.lines();
			double actualEps = sent / 6.0;

			System.out.printf("[TCP] 전송 %,d건 / 수신 %,d건 / 실측 %,.0f EPS / %,d KB / 버림 %d건%n",
					sent, received, actualEps, sink.bytes() / 1024, generator.statistics().dropped());

			assertThat(actualEps).isGreaterThan(95_000);
			assertThat(generator.statistics().dropped()).isZero();
			// 수신 건수가 전송 건수와 일치해야 한다(TCP 는 유실이 없어야 정상).
			assertThat(received).isBetween((long) (sent * 0.98), sent);
		}
	}

	@Test
	void TCP_최대속도를_측정한다() throws Exception {
		try (CountingSink sink = CountingSink.tcp()) {
			// eps=0 : 속도 제한 없이 낼 수 있는 최대치를 본다.
			LoadGenerator generator = new LoadGenerator(spec(Protocol.TCP, sink.port(), 0, 0), runtime, registry);
			generator.start();

			Thread.sleep(1500); // 워밍업(JIT/커넥션 수립) 구간은 측정에서 제외한다.
			long baseline = generator.statistics().sent();
			long start = System.nanoTime();

			MetricSample peak = null;
			for (int i = 0; i < 5; i++) {
				Thread.sleep(1000);
				MetricSample sample = generator.sample();
				if (peak == null || sample.eps() > peak.eps()) {
					peak = sample;
				}
			}
			long elapsedNanos = System.nanoTime() - start;
			long produced = generator.statistics().sent() - baseline;
			generator.stop();

			// 구간 평균으로 판정한다. 루프백 수신측 GC 등으로 특정 1초가 눌릴 수 있는데,
			// 그건 생성기 한계가 아니라 수신측 사정이다.
			double averageEps = produced * 1_000_000_000.0 / elapsedNanos;
			System.out.printf("[TCP 최대] 평균 %,.0f EPS / 피크 %,.0f EPS / %,.1f Mbps / 워커 %d개 / 누적 %,d건%n",
					averageEps, peak.eps(), peak.mbps(), peak.workers(), generator.statistics().sent());

			// 이 수치는 '수신측이 같은 장비에 있을 때'의 값이라 생성기 자체의 상한은 아니다.
			// 목표(10만 EPS)를 크게 웃도는지만 확인한다.
			assertThat(averageEps).isGreaterThan(500_000);
			assertThat(generator.statistics().dropped()).isZero();
		}
	}

	@Test
	void UDP_로_초당_10만건을_전송한다() throws Exception {
		try (CountingSink sink = CountingSink.udp()) {
			LoadGenerator generator = new LoadGenerator(spec(Protocol.UDP, sink.port(), 100_000, 4), runtime, registry);
			generator.start();

			Thread.sleep(6000);
			generator.sample();
			generator.stop();
			Thread.sleep(500);

			long sent = generator.statistics().sent();
			long received = sink.lines();
			double actualEps = sent / 6.0;
			double lossRate = 100.0 * (sent - received) / sent;

			System.out.printf("[UDP] 전송 %,d건 / 수신 %,d건 / 실측 %,.0f EPS / 유실률 %.2f%%%n",
					sent, received, actualEps, lossRate);

			assertThat(actualEps).isGreaterThan(95_000);
		}
	}

	@Test
	void 실행중_속도변경이_즉시_반영된다() throws Exception {
		try (CountingSink sink = CountingSink.tcp()) {
			LoadGenerator generator = new LoadGenerator(spec(Protocol.TCP, sink.port(), 10_000, 2), runtime, registry);
			generator.start();
			Thread.sleep(2000);
			generator.sample();
			Thread.sleep(2000);
			MetricSample before = generator.sample();

			generator.updateRate(50_000);
			Thread.sleep(2000);
			MetricSample after = generator.sample();
			generator.stop();

			System.out.printf("[속도변경] 10k 설정 → 실측 %,.0f EPS, 50k 설정 → 실측 %,.0f EPS%n",
					before.eps(), after.eps());

			assertThat(before.eps()).isBetween(9_000.0, 11_000.0);
			assertThat(after.eps()).isBetween(45_000.0, 55_000.0);
		}
	}

	@Test
	void 저속_전송도_정확하다() throws Exception {
		try (CountingSink sink = CountingSink.tcp()) {
			LoadGenerator generator = new LoadGenerator(spec(Protocol.TCP, sink.port(), 100, 1), runtime, registry);
			generator.start();
			Thread.sleep(1000);
			generator.sample();
			Thread.sleep(3000);
			MetricSample sample = generator.sample();
			generator.stop();

			System.out.printf("[저속] 100 EPS 설정 → 실측 %,.1f EPS%n", sample.eps());
			assertThat(sample.eps()).isBetween(95.0, 105.0);
		}
	}

}
