package com.tms.tester.common;

/** 전송 프로토콜 식별자. 확장 프로토콜은 TransportFactory 구현으로 추가한다. */
public final class Protocol {

	public static final String TCP = "TCP";
	public static final String UDP = "UDP";
	public static final String TLS = "TLS";

	private Protocol() {
	}
}
