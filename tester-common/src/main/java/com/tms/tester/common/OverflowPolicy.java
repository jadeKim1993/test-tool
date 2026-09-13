package com.tms.tester.common;

/**
 * 수신측이 못 따라와 송신 버퍼가 가득 찼을 때의 동작.
 */
public enum OverflowPolicy {

	/**
	 * 버리고 계속 진행한다. 목표 EPS 를 유지하면서 수신측 한계를 드러내고 싶을 때 사용한다.
	 * 버린 건수는 dropped 로 집계된다.
	 */
	DROP,

	/**
	 * 전송 가능해질 때까지 대기한다. 실제 처리 가능한 속도로 자동 수렴한다.
	 * "유실 없이 최대 속도"를 재고 싶을 때 사용한다.
	 */
	BLOCK
}
