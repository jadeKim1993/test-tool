package com.tms.tester.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tms.tester.common.RunState;
import com.tms.tester.common.TesterException;
import com.tms.tester.config.TesterProperties;
import com.tms.tester.metrics.JobMetricsDtos;
import com.tms.tester.metrics.MetricsService;
import com.tms.tester.transport.LoadGenerator;
import com.tms.tester.transport.MetricSample;
import com.tms.tester.transport.NettyRuntime;
import com.tms.tester.transport.RunSpec;
import com.tms.tester.transport.TemplateSource;
import com.tms.tester.transport.TransportRegistry;

import jakarta.annotation.PreDestroy;

/**
 * 실행 중인 전송 작업들의 소유자.
 *
 * <p>DB 트랜잭션과 무관한 순수 런타임 상태를 들고 있으며, 1초마다 각 작업의 메트릭을
 * 수집해 SSE 로 밀어낸다. 스스로 종료된 작업(시간/건수 제한 도달, 오류)도 이 주기에서
 * 감지해 이력을 마감한다.
 */
@Component
public class RunManager {

	private static final Logger log = LoggerFactory.getLogger(RunManager.class);

	private final NettyRuntime nettyRuntime;
	private final TransportRegistry transportRegistry;
	private final MetricsService metricsService;
	private final RunHistoryService historyService;
	private final TesterProperties properties;

	private final Map<Long, ActiveRun> runs = new ConcurrentHashMap<>();

	public RunManager(NettyRuntime nettyRuntime, TransportRegistry transportRegistry,
			MetricsService metricsService, RunHistoryService historyService, TesterProperties properties) {
		this.nettyRuntime = nettyRuntime;
		this.transportRegistry = transportRegistry;
		this.metricsService = metricsService;
		this.historyService = historyService;
		this.properties = properties;
	}

	/** 실행 중인 작업 1개의 런타임 상태. */
	private static final class ActiveRun {

		private final LoadGenerator generator;
		private final Long runId;
		private final Long ownerId;
		private final String jobName;
		private final Long categoryId;

		private double epsSum;
		private int sampleCount;
		private int peakEps;
		/** 설정과 실제 동작이 어긋났을 때 사용자에게 알릴 내용. */
		private volatile String warning;

		private ActiveRun(LoadGenerator generator, Long runId, Long ownerId, String jobName, Long categoryId) {
			this.generator = generator;
			this.runId = runId;
			this.ownerId = ownerId;
			this.jobName = jobName;
			this.categoryId = categoryId;
		}

		void record(double eps) {
			epsSum += eps;
			sampleCount++;
			peakEps = Math.max(peakEps, (int) Math.round(eps));
		}

		int avgEps() {
			return sampleCount == 0 ? 0 : (int) Math.round(epsSum / sampleCount);
		}
	}

	// ---------------- 시작 / 정지 ----------------

	public void start(JobLaunchContext context) {
		Long jobId = context.jobId();
		if (runs.containsKey(jobId)) {
			throw TesterException.state("이미 실행 중인 작업입니다: " + context.jobName());
		}
		int maxJobs = properties.getEngine().getMaxConcurrentJobs();
		if (runs.size() >= maxJobs) {
			throw TesterException.state("동시에 실행할 수 있는 작업 수(" + maxJobs + "개)를 초과했습니다");
		}
		int maxTotalEps = properties.getEngine().getMaxTotalEps();
		if (maxTotalEps > 0) {
			int currentTotal = runs.values().stream().mapToInt(run -> run.generator.configuredEps()).sum();
			if (currentTotal + context.eps() > maxTotalEps) {
				throw TesterException.state("전체 EPS 상한(" + String.format("%,d", maxTotalEps)
						+ ")을 초과합니다. 현재 합계 " + String.format("%,d", currentTotal));
			}
		}

		RunSpec spec = new RunSpec(jobId, context.jobName(), context.target(), context.templates(),
				context.selection(), context.eps(), context.workers(),
				context.durationSec(), context.totalLimit(), context.overflow(), context.encoding());

		LoadGenerator generator = new LoadGenerator(spec, nettyRuntime, transportRegistry);
		try {
			generator.start();
		}
		catch (Exception e) {
			throw TesterException.state("전송을 시작할 수 없습니다: " + e.getMessage());
		}

		Long runId = historyService.begin(jobId, context.eps(), generator.endpointDescription());
		runs.put(jobId, new ActiveRun(generator, runId, context.ownerId(), context.jobName(), context.categoryId()));
		metricsService.clearHistory(jobId);
		log.info("작업 시작: {} ({})", context.jobName(), generator.endpointDescription());
	}

	public void stop(Long jobId) {
		ActiveRun run = runs.get(jobId);
		if (run == null) {
			throw TesterException.state("실행 중인 작업이 아닙니다");
		}
		run.generator.stop();
		// 정지한 상태를 한 번 더 내보낸다. 이게 없으면 finalizeRun 이 runs 에서 빼는 순간
		// 주기 발행 대상에서도 빠져, 구독 중인 화면은 마지막 RUNNING 샘플에 멈춘 채
		// 계속 "전송 중"으로 남는다(스스로 끝난 작업은 발행 후 정리되므로 문제가 없다).
		metricsService.publish(run.ownerId, List.of(toStatus(jobId, run, run.generator.sample())));
		finalizeRun(jobId, run);
	}

	/** 실행 중 목표 속도를 바꾼다. 재시작이 필요 없다. */
	public void updateRate(Long jobId, int eps) {
		ActiveRun run = runs.get(jobId);
		if (run == null) {
			throw TesterException.state("실행 중인 작업이 아닙니다");
		}
		run.generator.updateRate(eps);
		log.info("속도 변경: {} → {} EPS", run.jobName, eps == 0 ? "무제한" : eps);
	}

	/** 템플릿이 수정되면 그 카테고리를 쓰는 실행 중 작업에 즉시 반영한다. */
	public void refreshTemplates(Long categoryId) {
		for (ActiveRun run : runs.values()) {
			if (!categoryId.equals(run.categoryId)) {
				continue;
			}
			List<TemplateSource> sources = templateLoader == null
					? null : templateLoader.load(run.generator.jobId());
			if (sources != null && !sources.isEmpty()) {
				run.generator.updateTemplates(sources);
				run.warning = null;
				log.info("실행 중 템플릿 반영: {} ({}종)", run.jobName, sources.size());
			}
			else {
				// 쓸 수 있는 로그 종류가 하나도 남지 않았다. 진행 중인 부하 시험을 갑자기
				// 끊는 것보다 직전 구성으로 계속 보내는 편이 안전하지만, 화면에는
				// 설정과 다르게 동작 중이라는 사실이 반드시 보여야 한다.
				run.warning = "사용 중인 로그 종류가 없어 직전 구성으로 계속 전송합니다. "
						+ "변경을 반영하려면 정지 후 다시 시작하세요.";
				log.warn("실행 중 템플릿이 모두 비활성화됨: {} — 직전 구성 유지", run.jobName);
			}
		}
	}

	/**
	 * 템플릿 재조회 콜백.
	 * <p>{@link com.tms.tester.catalog.TemplateService} → RunManager → JobService 로 이어지는
	 * 순환 참조를 피하기 위해 JobService 가 기동 시 자신을 등록한다.
	 */
	public interface TemplateLoader {

		List<TemplateSource> load(Long jobId);
	}

	private volatile TemplateLoader templateLoader;

	public void setTemplateLoader(TemplateLoader loader) {
		this.templateLoader = loader;
	}

	// ---------------- 상태 조회 ----------------

	public boolean isRunning(Long jobId) {
		ActiveRun run = runs.get(jobId);
		return run != null && run.generator.isActive();
	}

	public JobMetricsDtos.JobStatus statusOf(Long jobId) {
		ActiveRun run = runs.get(jobId);
		return run == null ? null : toStatus(jobId, run, run.generator.sample());
	}

	public List<Long> runningJobIds(Long ownerId) {
		List<Long> ids = new ArrayList<>();
		runs.forEach((jobId, run) -> {
			if (run.ownerId.equals(ownerId)) {
				ids.add(jobId);
			}
		});
		return ids;
	}

	// ---------------- 주기 작업 ----------------

	/**
	 * 1초마다 전 작업의 메트릭을 수집해 사용자별로 밀어낸다.
	 * <p>워커 핫패스와 분리된 단일 스레드에서만 {@code sample()} 을 호출한다.
	 */
	@Scheduled(fixedDelayString = "${tester.engine.metrics-interval:PT1S}")
	public void sampleAndPublish() {
		if (runs.isEmpty()) {
			return;
		}
		Map<Long, List<JobMetricsDtos.JobStatus>> byUser = new LinkedHashMap<>();
		List<Long> finished = new ArrayList<>();

		runs.forEach((jobId, run) -> {
			MetricSample sample = run.generator.sample();
			run.record(sample.eps());
			byUser.computeIfAbsent(run.ownerId, key -> new ArrayList<>()).add(toStatus(jobId, run, sample));
			if (!run.generator.isActive()) {
				finished.add(jobId);
			}
		});

		byUser.forEach(metricsService::publish);

		// 스스로 끝난 작업(시간/건수 제한, 오류) 이력 마감
		for (Long jobId : finished) {
			ActiveRun run = runs.get(jobId);
			if (run != null) {
				finalizeRun(jobId, run);
			}
		}
	}

	private void finalizeRun(Long jobId, ActiveRun run) {
		if (runs.remove(jobId) == null) {
			return;
		}
		var stats = run.generator.statistics();
		historyService.finish(run.runId, run.generator.state(), stats.sent(), stats.bytes(),
				stats.dropped(), stats.errors(), run.avgEps(), run.peakEps, run.generator.completionReason());
		log.info("작업 종료: {} 상태={} 전송={}건 평균={} EPS",
				run.jobName, run.generator.state(), stats.sent(), run.avgEps());
	}

	@PreDestroy
	public void shutdown() {
		if (runs.isEmpty()) {
			return;
		}
		log.info("종료 중: 실행 작업 {}개 정지", runs.size());
		new ArrayList<>(runs.keySet()).forEach(jobId -> {
			ActiveRun run = runs.get(jobId);
			if (run != null) {
				run.generator.stop();
				finalizeRun(jobId, run);
			}
		});
	}

	private JobMetricsDtos.JobStatus toStatus(Long jobId, ActiveRun run, MetricSample sample) {
		String message = sample.lastError();
		if (message == null) {
			message = run.warning;
		}
		if (message == null) {
			message = run.generator.completionReason();
		}
		return new JobMetricsDtos.JobStatus(
				jobId,
				run.jobName,
				run.runId,
				sample.timestamp(),
				sample.elapsedSec(),
				Math.round(sample.eps() * 10) / 10.0,
				sample.targetEps(),
				Math.round(sample.mbps() * 100) / 100.0,
				sample.sent(),
				sample.bytes(),
				sample.dropped(),
				sample.errors(),
				sample.state() == null ? RunState.STOPPED : sample.state(),
				sample.connection().name(),
				sample.workers(),
				sample.endpoint(),
				message);
	}

	/** 작업 시작에 필요한 값 묶음. 엔티티 → 런타임 변환은 JobService 가 담당한다. */
	public record JobLaunchContext(
			Long jobId,
			String jobName,
			Long ownerId,
			Long categoryId,
			com.tms.tester.transport.TargetSpec target,
			List<TemplateSource> templates,
			com.tms.tester.common.SelectionStrategy selection,
			int eps,
			int workers,
			Integer durationSec,
			Long totalLimit,
			com.tms.tester.common.OverflowPolicy overflow,
			com.tms.tester.common.WireEncoding encoding) {
	}
}
