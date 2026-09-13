package com.tms.tester.common;

/** 로그 레코드 구분 방식. */
public enum Framing {

	/** 로그 끝에 개행(\n)을 붙인다. 대부분의 수집기 기본값. */
	NEWLINE,
	/** 아무것도 붙이지 않는다. */
	NONE,
	/** 4바이트 big-endian 길이 프리픽스. */
	LENGTH_PREFIX,
	/** RFC6587 octet counting: "<길이><SP><메시지>". */
	OCTET_COUNTING
}
