package com.tms.tester.common;

/**
 * HTTP 전송 시 요청 본문 형식. 수집기마다 받는 모양이 정해져 있어서, 대표적인 것을 고를 수 있게 한다.
 * <p>각 형식은 그 수집기의 기본 경로·포트를 함께 갖고 있어 화면이 처음 값을 채워 준다(바꿀 수 있다).
 * JSON 계열은 로그 한 건을 문자열 필드로 감싸 이스케이프한다 — 여러 줄 로그(스택트레이스)도 한 건으로 간다.
 */
public enum HttpFormat {

	/** 로그를 줄바꿈으로 이어 붙인다. Logstash http(line 코덱)·Vector http_server(텍스트) 등. */
	LINES("텍스트 줄 (text/plain)", "text/plain; charset=utf-8", "/", 8080),

	/** 한 줄에 {"message":"…"} 하나. Vector·Logstash(json_lines)·Fluentd 등. */
	NDJSON("JSON Lines (application/x-ndjson)", "application/x-ndjson", "/", 8080),

	/** [{"message":"…"}, …]. Fluent Bit http 입력의 기본 형식. */
	JSON_ARRAY("JSON 배열 (Fluent Bit http 입력)", "application/json", "/", 9880),

	/** {"event":"…"} 를 이어 붙인다. 토큰은 헤더 "Authorization: Splunk <토큰>" 으로 준다. */
	SPLUNK_HEC("Splunk HEC", "application/json", "/services/collector/event", 8088),

	/** 액션 줄 + 문서 줄. 인덱스는 경로로 정한다(/인덱스/_bulk). OpenSearch 도 같다. */
	ES_BULK("Elasticsearch / OpenSearch _bulk", "application/x-ndjson", "/logs-tester/_bulk", 9200),

	/** Loki push API(JSON). 라벨은 job="tester" 로 고정한다. */
	LOKI("Grafana Loki push", "application/json", "/loki/api/v1/push", 3100);

	private final String label;
	private final String contentType;
	private final String defaultPath;
	private final int defaultPort;

	HttpFormat(String label, String contentType, String defaultPath, int defaultPort) {
		this.label = label;
		this.contentType = contentType;
		this.defaultPath = defaultPath;
		this.defaultPort = defaultPort;
	}

	public String label() {
		return label;
	}

	public String contentType() {
		return contentType;
	}

	public String defaultPath() {
		return defaultPath;
	}

	public int defaultPort() {
		return defaultPort;
	}

	/** JSON 문자열로 감싸 보내는 형식인가(이스케이프 필요, 본문이 UTF-8 이어야 한다). */
	public boolean isJson() {
		return this != LINES;
	}
}
