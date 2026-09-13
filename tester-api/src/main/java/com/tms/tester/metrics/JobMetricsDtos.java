package com.tms.tester.metrics;

import java.util.List;

import com.tms.tester.common.RunState;

public final class JobMetricsDtos {

	private JobMetricsDtos() {
	}

	/**
	 * 전송 작업 1개의 현재 상태. 화면의 카드/그래프가 이 값을 그대로 그린다.
	 *
	 * @param eps        직전 1초 실측 초당 건수
	 * @param targetEps  설정한 목표 (0 이면 무제한)
	 * @param dropped    송신 버퍼 포화로 버린 건수. 0 이 아니면 수신측이 못 따라오는 것이다
	 * @param connection 연결 상태 (CONNECTED / RECONNECTING / FAILED)
	 */
	public record JobStatus(
			Long jobId,
			String jobName,
			Long runId,
			long timestamp,
			long elapsedSec,
			double eps,
			int targetEps,
			double mbps,
			long sent,
			long bytes,
			long dropped,
			long errors,
			RunState state,
			String connection,
			int workers,
			String endpoint,
			String message) {
	}

	public record MetricsEvent(long timestamp, List<JobStatus> jobs) {
	}
}
