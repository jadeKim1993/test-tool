package com.tms.tester.transport;

import com.tms.tester.common.RunState;

/**
 * 1초 주기 전송 상태 스냅샷. SSE 로 그대로 전달된다.
 *
 * @param timestamp     샘플 시각(epoch millis)
 * @param elapsedSec    실행 경과 초
 * @param eps           직전 구간 실측 초당 건수
 * @param targetEps     목표 초당 건수(0 이면 무제한)
 * @param mbps          직전 구간 실측 Mbps
 * @param sent          누적 전송 건수
 * @param bytes         누적 전송 바이트
 * @param dropped       누적 버림 건수(수신측 지연)
 * @param errors        누적 오류 건수
 * @param state         실행 상태
 * @param connection    연결 상태
 * @param workers       워커 스레드 수
 * @param endpoint      대상 표시 문자열
 * @param lastError     마지막 오류 메시지(없으면 null)
 */
public record MetricSample(
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
		SenderState connection,
		int workers,
		String endpoint,
		String lastError
) {
}
