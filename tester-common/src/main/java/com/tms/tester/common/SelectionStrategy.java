package com.tms.tester.common;

/** 카테고리 안에서 어떤 로그 종류를 고를지 결정하는 방식. */
public enum SelectionStrategy {

	/** 가중치에 비례해 무작위 선택. 실제 서비스 로그 비율을 흉내낼 때 사용한다. */
	WEIGHTED_RANDOM,

	/** 순서대로 돌아가며 1건씩. 모든 종류를 고르게 보내고 싶을 때. */
	ROUND_ROBIN,

	/** 목록 순서대로 반복. ROUND_ROBIN 과 같지만 워커별로 시작점을 나누지 않는다. */
	SEQUENTIAL
}
