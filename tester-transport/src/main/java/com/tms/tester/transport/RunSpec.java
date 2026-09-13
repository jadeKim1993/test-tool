package com.tms.tester.transport;

import com.tms.tester.common.SelectionStrategy;
import com.tms.tester.common.OverflowPolicy;
import com.tms.tester.common.WireEncoding;

import java.util.List;

/**
 * 전송 실행 1회의 완전한 정의.
 *
 * @param jobId       작업 식별자
 * @param jobName     표시명
 * @param target      전송 대상
 * @param templates   사용할 로그 템플릿 목록
 * @param selection   템플릿 선택 방식
 * @param eps         목표 초당 건수. 0 이면 무제한(최대 속도 측정)
 * @param workers     생성 워커 수. 0 이면 EPS 에 맞춰 자동 산정
 * @param durationSec 실행 시간 제한(초). null 이면 무제한
 * @param totalLimit  총 전송 건수 제한. null 이면 무제한
 * @param overflow    송신 버퍼 포화 시 정책
 * @param encoding    회선에 실을 바이트 변환 방식. 로그 카테고리에 속한 설정이다(§16.14)
 */
public record RunSpec(
		long jobId,
		String jobName,
		TargetSpec target,
		List<TemplateSource> templates,
		SelectionStrategy selection,
		int eps,
		int workers,
		Integer durationSec,
		Long totalLimit,
		OverflowPolicy overflow,
		WireEncoding encoding
) {

	/** 워커 1개가 감당할 것으로 보는 EPS. 이 값 기준으로 워커 수를 자동 산정한다. */
	public static final int EPS_PER_WORKER = 50_000;

	public RunSpec {
		if (templates == null || templates.isEmpty()) {
			throw new IllegalArgumentException("전송할 로그 템플릿이 최소 1개 필요합니다");
		}
		if (selection == null) {
			selection = SelectionStrategy.WEIGHTED_RANDOM;
		}
		if (overflow == null) {
			overflow = OverflowPolicy.DROP;
		}
		if (encoding == null) {
			encoding = WireEncoding.UTF_8;
		}
		if (eps < 0) {
			eps = 0;
		}
	}

	/** 실제 기동할 워커 수. */
	public int resolveWorkers() {
		if (workers > 0) {
			return Math.min(workers, 64);
		}
		int cores = Runtime.getRuntime().availableProcessors();
		if (eps == 0) {
			// 무제한 모드: 코어를 최대한 쓰되 I/O 스레드 몫을 남긴다.
			return Math.max(1, cores - 1);
		}
		int needed = (int) Math.ceil((double) eps / EPS_PER_WORKER);
		return Math.max(1, Math.min(needed, Math.max(1, cores - 1)));
	}
}
