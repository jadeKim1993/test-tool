package com.tms.tester.common;

/**
 * 로그 출력 포맷. 템플릿 본문 앞에 붙는 헤더를 결정한다.
 * <p>JSON 로그는 별도 포맷을 두지 않고 템플릿 본문에 JSON 을 그대로 작성하면 된다
 * (플레이스홀더가 값 위치에 자유롭게 들어갈 수 있어 더 유연하다).
 */
public enum LogFormat {

	/** 템플릿 본문 그대로. */
	RAW(""),

	/** RFC3164 (BSD syslog) 헤더 자동 부착. */
	SYSLOG_RFC3164("<${choice(134:70,131:20,132:10)}>${ts(MMM d HH:mm:ss,en)} ${host(host,5)} ${dict(services)}[${int(100,9999)}]: "),

	/** RFC5424 헤더 자동 부착. */
	SYSLOG_RFC5424("<${choice(134:70,131:20,132:10)}>1 ${ts} ${host(host,5)} ${dict(services)} ${int(100,9999)} - - ");

	private final String header;

	LogFormat(String header) {
		this.header = header;
	}

	/** 본문 앞에 붙일 헤더 템플릿 소스. */
	public String header() {
		return header;
	}
}
