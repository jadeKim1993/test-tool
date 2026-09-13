package com.tms.tester.generator;

/**
 * 플레이스홀더 함수 메타데이터. 프론트엔드의 자동완성/도움말이 이 정보를 그대로 사용한다.
 *
 * @param name        함수명 (예: {@code ip})
 * @param signature   인자 표기 (예: {@code ip(cidr?)})
 * @param description 한글 설명
 * @param example     사용 예시
 * @param sample      예시의 렌더링 결과 예
 * @param group       UI 그룹 (네트워크 / 숫자 / 문자열 / 시간 / 기타)
 */
public record FunctionSpec(
		String name,
		String signature,
		String description,
		String example,
		String sample,
		String group
) {
}
