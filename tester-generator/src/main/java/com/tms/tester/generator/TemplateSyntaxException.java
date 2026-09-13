package com.tms.tester.generator;

/** 템플릿 문법 오류. 메시지에는 사용자가 고칠 수 있는 위치 정보를 담는다. */
public class TemplateSyntaxException extends RuntimeException {

	private final int position;

	public TemplateSyntaxException(String message, int position) {
		super(position >= 0 ? message + " (위치 " + position + ")" : message);
		this.position = position;
	}

	public int getPosition() {
		return position;
	}
}
