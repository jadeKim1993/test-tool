package com.tms.tester.common;

/** 전송 실행 상태. */
public enum RunState {
	STARTING,
	RUNNING,
	STOPPING,
	STOPPED,
	/** 지정한 시간/건수를 모두 채우고 정상 종료. */
	COMPLETED,
	FAILED
}
