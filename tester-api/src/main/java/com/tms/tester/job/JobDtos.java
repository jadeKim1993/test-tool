package com.tms.tester.job;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.tms.tester.common.LogFormat;
import com.tms.tester.common.OverflowPolicy;
import com.tms.tester.common.RunState;
import com.tms.tester.common.SelectionStrategy;
import com.tms.tester.common.WireEncoding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class JobDtos {

	private JobDtos() {
	}

	/**
	 * 작업 생성/수정 요청.
	 * <p>이름은 선택 사항이다. 비워 두면 "카테고리 → 대상" 으로 자동 생성한다.
	 * 워커 스레드 수는 입력받지 않는다(항상 EPS 에 맞춰 자동 산정).
	 */
	public record JobRequest(
			@Size(max = 100) String name,
			@NotNull(message = "카테고리를 선택하세요") Long categoryId,
			@NotNull(message = "전송 대상을 선택하세요") Long targetId,
			/** 비어 있으면 카테고리의 활성 템플릿 전체를 사용한다. */
			Set<Long> templateIds,
			@Min(value = 0, message = "EPS 는 0 이상이어야 합니다")
			@Max(value = 5_000_000, message = "EPS 가 너무 큽니다") int eps,
			SelectionStrategy selection,
			OverflowPolicy overflowPolicy,
			Integer durationSec,
			Long totalLimit,
			Boolean autostart) {
	}

	public record JobResponse(
			Long id,
			String name,
			Long categoryId,
			String categoryName,
			Long targetId,
			String targetName,
			String endpoint,
			Set<Long> templateIds,
			int templateCount,
			int eps,
			SelectionStrategy selection,
			OverflowPolicy overflowPolicy,
			Integer durationSec,
			Long totalLimit,
			int workerThreads,
			boolean autostart,
			boolean running,
			Instant updatedAt) {
	}

	/** 시작 시 일회성으로 설정을 덮어쓸 수 있다(저장된 값은 그대로 둔다). */
	public record StartRequest(Integer eps, Integer durationSec, Long totalLimit) {
	}

	public record RateRequest(
			@Min(value = 0, message = "EPS 는 0 이상이어야 합니다")
			@Max(value = 5_000_000, message = "EPS 가 너무 큽니다") int eps) {
	}

	public record RunResponse(
			Long id,
			Long jobId,
			Instant startedAt,
			Instant endedAt,
			RunState status,
			int targetEps,
			long sentCount,
			long byteCount,
			long droppedCount,
			long errorCount,
			int avgEps,
			int peakEps,
			String endpoint,
			String message) {
	}

	public record RunningSummary(int runningJobs, double totalEps, double totalMbps, List<Long> jobIds) {
	}

	/**
	 * 이 작업이 실제로 보내는 로그 종류. 엔진이 쓰는 목록과 같은 규칙으로 만든다
	 * (카테고리의 사용 중인 종류 ∩ 작업이 고른 종류).
	 *
	 * @param selection 선택 방식. 가중치는 WEIGHTED_RANDOM 일 때만 비율에 반영된다
	 * @param encoding  회선에 실을 때의 변환 방식(카테고리 설정)
	 */
	public record JobContent(SelectionStrategy selection, WireEncoding encoding, List<JobTemplate> templates) {
	}

	/**
	 * @param share       실제 전송 비율(0~1). 라운드로빈/순차는 가중치와 무관하게 균등하다
	 * @param sample      한 번 렌더링한 예시(인코딩 전 문자열). 문법 오류면 null
	 * @param sampleBytes 예시 한 건의 UTF-8 바이트 수
	 */
	public record JobTemplate(
			Long id,
			String name,
			LogFormat format,
			int weight,
			double share,
			String sample,
			int sampleBytes,
			String error) {
	}
}
