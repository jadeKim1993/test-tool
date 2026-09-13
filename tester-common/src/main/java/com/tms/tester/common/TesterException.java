package com.tms.tester.common;

/** 애플리케이션 공통 예외. HTTP 상태 매핑은 API 모듈의 예외 핸들러가 담당한다. */
public class TesterException extends RuntimeException {

	private final ErrorCode code;

	public TesterException(ErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public TesterException(ErrorCode code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public ErrorCode getCode() {
		return code;
	}

	public static TesterException notFound(String what, Object id) {
		return new TesterException(ErrorCode.NOT_FOUND, what + "을(를) 찾을 수 없습니다: " + id);
	}

	public static TesterException conflict(String message) {
		return new TesterException(ErrorCode.CONFLICT, message);
	}

	public static TesterException invalid(String message) {
		return new TesterException(ErrorCode.INVALID, message);
	}

	public static TesterException forbidden(String message) {
		return new TesterException(ErrorCode.FORBIDDEN, message);
	}

	public static TesterException state(String message) {
		return new TesterException(ErrorCode.ILLEGAL_STATE, message);
	}
}
