package com.tms.tester.common;

/** 전송 프로토콜 식별자. 확장 프로토콜은 TransportFactory 구현으로 추가한다. */
public final class Protocol {

	public static final String TCP = "TCP";
	public static final String UDP = "UDP";
	public static final String TLS = "TLS";
	public static final String HTTP = "HTTP";
	public static final String HTTPS = "HTTPS";

	/** HTTP 계열(요청 단위로 묶어 보내는 프로토콜)인가. */
	public static boolean isHttp(String protocol) {
		return HTTP.equalsIgnoreCase(protocol) || HTTPS.equalsIgnoreCase(protocol);
	}

	private Protocol() {
	}
}
