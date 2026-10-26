package com.tms.tester.transport;

import com.tms.tester.common.RunState;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBufAllocator;

/**
 * 전송 작업 1회의 실행 단위.
 *
 * <p>워커 스레드 N개가 각자 전용 소켓으로 비동기 전송하고, 공유하는 것은 카운터와
 * 몇 개의 설정값뿐이다. 목표 EPS 와 템플릿은 <b>실행 중에도 무중단으로 변경</b>할 수 있다
 * ({@link #updateRate(int)}, {@link #updateTemplates(List)}).
 *
 * <p>실행 시간/건수 제한은 별도 감시 태스크가 200ms 주기로 확인한다. 워커 루프에서
 * 확인하지 않는 이유는 핫패스에 조건 분기를 늘리지 않기 위해서다.
 */
public final class LoadGenerator implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

	private final RunSpec spec;
	private final NettyRuntime runtime;
	private final TransportRegistry registry;
	/** 템플릿 함수 목록. 화면의 저장 검증과 같은 목록을 써야 저장된 템플릿이 전송에서도 컴파일된다. */
	private final com.tms.tester.generator.ValueWriterRegistry functions;
	private final SendStats stats = new SendStats();
	private final AtomicLong sequence = new AtomicLong(1);

	private volatile boolean running;
	private volatile int targetEps;
	private volatile List<TemplateSource> templates;
	private volatile int templateGeneration;
	private volatile RunState state = RunState.STARTING;
	private volatile String lastError;
	private volatile String completionReason;

	private LogSender sender;
	private Thread[] threads;
	private int workerCount;
	private ScheduledFuture<?> supervisor;
	private long startedAtMillis;
	private long endedAtMillis;

	/** 직전 샘플 기준값 (초당 속도 계산용). */
	private long lastSampleMillis;
	private long lastSampleSent;
	private long lastSampleBytes;

	public LoadGenerator(RunSpec spec, NettyRuntime runtime, TransportRegistry registry) {
		this(spec, runtime, registry, com.tms.tester.generator.ValueWriterRegistry.standard());
	}

	public LoadGenerator(RunSpec spec, NettyRuntime runtime, TransportRegistry registry,
			com.tms.tester.generator.ValueWriterRegistry functions) {
		this.spec = spec;
		this.runtime = runtime;
		this.registry = registry;
		this.functions = functions;
		this.targetEps = spec.eps();
		this.templates = List.copyOf(spec.templates());
		this.templateGeneration = 1;
	}

	// ---------------- 생명주기 ----------------

	public synchronized void start() throws Exception {
		if (running) {
			return;
		}
		state = RunState.STARTING;
		workerCount = spec.resolveWorkers();
		sender = registry.create(spec.target(), workerCount, runtime);
		try {
			sender.connect();
		}
		catch (Exception e) {
			if (!sender.keepsRetrying()) {
				state = RunState.FAILED;
				lastError = e.getMessage();
				sender.close();
				sender = null;
				throw e;
			}
			// 수신 서버가 아직 안 떠 있어도 시작한다. 송신기가 뒤에서 계속 붙어 보고, 붙는 순간부터
			// 이어서 보낸다. 오류는 sender.lastError() 로 화면에 보이다가 연결되면 지워진다
			// (여기 lastError 에 넣으면 그쪽이 우선이라 연결된 뒤에도 남는다).
			log.warn("수신 서버에 아직 연결하지 못했지만 재연결을 계속하며 시작한다: {} ({})",
					spec.target().endpoint(), e.getMessage());
		}

		startedAtMillis = System.currentTimeMillis();
		lastSampleMillis = startedAtMillis;
		lastSampleSent = 0;
		lastSampleBytes = 0;
		running = true;
		state = RunState.RUNNING;

		threads = new Thread[workerCount];
		for (int i = 0; i < workerCount; i++) {
			GeneratorWorker worker = new GeneratorWorker(i, this, sender.endpoint(i), sequence);
			Thread thread = new Thread(worker, "logGen-" + spec.jobId() + "-" + i);
			thread.setDaemon(true);
			threads[i] = thread;
			thread.start();
		}
		scheduleSupervisor();

		log.info("전송 시작: job={} 대상={} 워커={} 목표EPS={} 템플릿={}개",
				spec.jobName(), sender.describe(), workerCount,
				targetEps == 0 ? "무제한" : targetEps, templates.size());
	}

	/** 시간/건수 제한 감시. 핫패스 밖에서 200ms 주기로만 확인한다. */
	private void scheduleSupervisor() {
		Long limit = spec.totalLimit();
		Integer duration = spec.durationSec();
		if (limit == null && duration == null) {
			return;
		}
		long deadline = duration == null ? Long.MAX_VALUE : startedAtMillis + duration * 1000L;
		supervisor = runtime.group().scheduleAtFixedRate(() -> {
			if (!running) {
				return;
			}
			if (System.currentTimeMillis() >= deadline) {
				stopInternal(RunState.COMPLETED, "지정한 실행 시간(" + duration + "초)에 도달했습니다");
			}
			else if (limit != null && stats.sent() >= limit) {
				stopInternal(RunState.COMPLETED, "지정한 전송 건수(" + limit + "건)에 도달했습니다");
			}
		}, 200, 200, TimeUnit.MILLISECONDS);
	}

	public void stop() {
		stopInternal(RunState.STOPPED, "사용자가 정지했습니다");
	}

	private synchronized void stopInternal(RunState finalState, String reason) {
		if (!running) {
			return;
		}
		state = RunState.STOPPING;
		running = false;
		if (supervisor != null) {
			supervisor.cancel(false);
			supervisor = null;
		}
		if (threads != null) {
			for (Thread thread : threads) {
				try {
					thread.join(2000);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			threads = null;
		}
		if (sender != null) {
			sender.close();
		}
		endedAtMillis = System.currentTimeMillis();
		completionReason = reason;
		state = finalState;
		log.info("전송 종료: job={} 사유={} 전송={}건 버림={}건",
				spec.jobName(), reason, stats.sent(), stats.dropped());
	}

	void reportFailure(Throwable cause) {
		lastError = cause.getMessage();
		stopInternal(RunState.FAILED, "워커 오류: " + cause.getMessage());
	}

	@Override
	public void close() {
		stop();
	}

	// ---------------- 무중단 변경 ----------------

	/** 실행 중 목표 속도를 변경한다. 다음 틱부터 반영된다. */
	public void updateRate(int eps) {
		this.targetEps = Math.max(0, eps);
	}

	/** 실행 중 템플릿을 교체한다. 워커가 다음 틱에 재컴파일한다. */
	public void updateTemplates(List<TemplateSource> newTemplates) {
		if (newTemplates == null || newTemplates.isEmpty()) {
			return;
		}
		this.templates = List.copyOf(newTemplates);
		this.templateGeneration++;
	}

	// ---------------- 워커가 사용하는 접근자 ----------------

	boolean isRunning() {
		return running;
	}

	int targetEps() {
		return targetEps;
	}

	int templateGeneration() {
		return templateGeneration;
	}

	List<TemplateSource> templates() {
		return templates;
	}

	int workerCount() {
		return workerCount;
	}

	RunSpec spec() {
		return spec;
	}

	SendStats stats() {
		return stats;
	}

	ByteBufAllocator allocator() {
		return sender.allocator();
	}

	// ---------------- 상태 조회 ----------------

	public long jobId() {
		return spec.jobId();
	}

	/** 설정된 목표 EPS(무중단 변경분 반영). */
	public int configuredEps() {
		return targetEps;
	}

	public RunState state() {
		return state;
	}

	public boolean isActive() {
		return running;
	}

	public String completionReason() {
		return completionReason;
	}

	public long startedAt() {
		return startedAtMillis;
	}

	public long endedAt() {
		return endedAtMillis;
	}

	com.tms.tester.generator.ValueWriterRegistry functions() {
		return functions;
	}

	public SendStats statistics() {
		return stats;
	}

	/**
	 * 수신측이 받은 건수. 워커가 센 전송 건수에서 수신측이 거부했다고 확인된 건(HTTP 4xx/5xx 등)을 뺀다.
	 * 화면(실시간)과 실행 이력이 같은 값을 쓰도록 여기 한 곳에서 계산한다.
	 */
	public long deliveredCount() {
		return stats.sent() - (sender == null ? 0 : sender.failedRecords());
	}

	public long deliveredBytes() {
		return stats.bytes() - (sender == null ? 0 : sender.failedBytes());
	}

	/** 버린 건수 + 수신측이 거부한 건수. */
	public long droppedCount() {
		return stats.dropped() + (sender == null ? 0 : sender.failedRecords());
	}

	public String endpointDescription() {
		return sender != null ? sender.describe() : spec.target().endpoint();
	}

	/**
	 * 직전 호출 이후의 실측 속도를 포함한 스냅샷. 1초 주기로 호출하는 것을 전제로 한다.
	 * <p><b>단일 스레드에서만 호출해야 한다</b>(메트릭 스케줄러 전용).
	 */
	public MetricSample sample() {
		long now = System.currentTimeMillis();
		// 수신측이 거부했다고 확인된 건(HTTP 4xx/5xx 등)은 전송에서 빼고 버림으로 센다.
		long sent = deliveredCount();
		long bytes = deliveredBytes();
		long elapsedMillis = Math.max(1, now - lastSampleMillis);

		double eps = (sent - lastSampleSent) * 1000.0 / elapsedMillis;
		double mbps = (bytes - lastSampleBytes) * 8.0 / 1_000_000.0 * (1000.0 / elapsedMillis);

		lastSampleMillis = now;
		lastSampleSent = sent;
		lastSampleBytes = bytes;

		String error = lastError;
		SenderState connection = SenderState.CLOSED;
		if (sender != null) {
			connection = sender.state();
			if (error == null) {
				error = sender.lastError();
			}
		}
		long endMillis = running ? now : (endedAtMillis > 0 ? endedAtMillis : now);
		return new MetricSample(
				now,
				Math.max(0, (endMillis - startedAtMillis) / 1000),
				Math.max(0, eps),
				targetEps,
				Math.max(0, mbps),
				sent,
				bytes,
				droppedCount(),
				stats.errors(),
				state,
				connection,
				workerCount,
				endpointDescription(),
				error);
	}
}
