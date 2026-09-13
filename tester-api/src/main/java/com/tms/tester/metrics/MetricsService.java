package com.tms.tester.metrics;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tms.tester.config.TesterProperties;

/**
 * 전송 상태 실시간 전달.
 * <p>1초 샘플은 DB 에 쓰지 않고 메모리 링버퍼에만 보관한다(10만 EPS 에서 DB 기록 자체가 병목).
 * 새로 접속한 화면에는 최근 이력을 먼저 밀어넣어 그래프가 비어 보이지 않게 한다.
 */
@Service
public class MetricsService {

	private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

	private final int historySize;
	/** 사용자별 구독자. 자기 작업의 상태만 받는다. */
	private final Map<Long, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
	/** 작업별 최근 샘플. */
	private final Map<Long, Deque<JobMetricsDtos.JobStatus>> history = new ConcurrentHashMap<>();

	public MetricsService(TesterProperties properties) {
		this.historySize = Math.max(60, properties.getEngine().getHistorySize());
	}

	public SseEmitter subscribe(Long userId, List<Long> jobIds) {
		// 타임아웃 0 = 무제한. nginx 쪽 proxy_read_timeout 도 함께 늘려야 한다.
		SseEmitter emitter = new SseEmitter(0L);
		List<SseEmitter> list = subscribers.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>());
		list.add(emitter);

		emitter.onCompletion(() -> list.remove(emitter));
		emitter.onTimeout(() -> {
			list.remove(emitter);
			emitter.complete();
		});
		emitter.onError(e -> list.remove(emitter));

		// 접속 즉시 최근 이력을 한 번 내려준다.
		try {
			List<JobMetricsDtos.JobStatus> backfill = new ArrayList<>();
			for (Long jobId : jobIds) {
				Deque<JobMetricsDtos.JobStatus> samples = history.get(jobId);
				if (samples != null) {
					backfill.addAll(samples);
				}
			}
			emitter.send(SseEmitter.event().name("backfill")
					.data(new JobMetricsDtos.MetricsEvent(System.currentTimeMillis(), backfill)));
		}
		catch (IOException e) {
			list.remove(emitter);
		}
		return emitter;
	}

	/** 작업별 상태를 기록하고 해당 사용자 구독자에게 밀어낸다. */
	public void publish(Long userId, List<JobMetricsDtos.JobStatus> statuses) {
		for (JobMetricsDtos.JobStatus status : statuses) {
			Deque<JobMetricsDtos.JobStatus> samples =
					history.computeIfAbsent(status.jobId(), key -> new ArrayDeque<>());
			synchronized (samples) {
				samples.addLast(status);
				while (samples.size() > historySize) {
					samples.removeFirst();
				}
			}
		}

		List<SseEmitter> list = subscribers.get(userId);
		if (list == null || list.isEmpty()) {
			return;
		}
		JobMetricsDtos.MetricsEvent event = new JobMetricsDtos.MetricsEvent(System.currentTimeMillis(), statuses);
		for (SseEmitter emitter : list) {
			try {
				emitter.send(SseEmitter.event().name("metrics").data(event));
			}
			catch (Exception e) {
				// 브라우저가 탭을 닫은 경우 등. 조용히 정리한다.
				list.remove(emitter);
				emitter.completeWithError(e);
			}
		}
	}

	public List<JobMetricsDtos.JobStatus> historyOf(Long jobId) {
		Deque<JobMetricsDtos.JobStatus> samples = history.get(jobId);
		if (samples == null) {
			return List.of();
		}
		synchronized (samples) {
			return new ArrayList<>(samples);
		}
	}

	public void clearHistory(Long jobId) {
		history.remove(jobId);
	}

	public int subscriberCount() {
		return subscribers.values().stream().mapToInt(List::size).sum();
	}
}
