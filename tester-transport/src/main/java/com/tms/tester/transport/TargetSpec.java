package com.tms.tester.transport;

import com.tms.tester.common.Framing;
import com.tms.tester.common.HttpFormat;
import com.tms.tester.common.Protocol;

/**
 * 전송 대상 정의. 도메인 엔티티와 분리해 transport 모듈이 Spring/JPA 에 의존하지 않게 한다.
 *
 * @param name            표시용 이름
 * @param protocol        TCP / UDP (확장 프로토콜은 TransportFactory 로 추가)
 * @param host            대상 호스트
 * @param port            대상 포트
 * @param connections     소켓(커넥션) 수. 0 이면 워커 수와 동일하게 맞춘다
 * @param framing         레코드 구분 방식
 * @param batchBytes      TCP 배치 임계치. 이 크기를 넘으면 flush 한다
 * @param sendBufferBytes SO_SNDBUF
 * @param connectTimeoutMs 연결 타임아웃
 * @param httpPath        HTTP/HTTPS: 요청 경로(쿼리 포함 가능). 그 외 프로토콜은 무시
 * @param httpFormat      HTTP/HTTPS: 요청 본문 형식
 * @param httpHeaders     HTTP/HTTPS: 추가 헤더. 한 줄에 "이름: 값"(빈 줄과 #으로 시작하는 줄은 무시)
 */
public record TargetSpec(
		String name,
		String protocol,
		String host,
		int port,
		int connections,
		Framing framing,
		int batchBytes,
		int sendBufferBytes,
		int connectTimeoutMs,
		String httpPath,
		HttpFormat httpFormat,
		String httpHeaders
) {

	public static final int DEFAULT_BATCH_BYTES = 16 * 1024;
	public static final int DEFAULT_SEND_BUFFER = 4 * 1024 * 1024;

	public TargetSpec {
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("host 는 필수입니다");
		}
		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException("port 범위가 올바르지 않습니다: " + port);
		}
		if (batchBytes <= 0) {
			batchBytes = DEFAULT_BATCH_BYTES;
		}
		if (sendBufferBytes <= 0) {
			sendBufferBytes = DEFAULT_SEND_BUFFER;
		}
		if (connectTimeoutMs <= 0) {
			connectTimeoutMs = 3000;
		}
		if (framing == null) {
			framing = Framing.NEWLINE;
		}
		if (httpFormat == null) {
			httpFormat = HttpFormat.LINES;
		}
		if (httpPath == null || httpPath.isBlank()) {
			httpPath = "/";
		}
		else if (!httpPath.startsWith("/")) {
			httpPath = "/" + httpPath.trim();
		}
		if (httpHeaders == null) {
			httpHeaders = "";
		}
	}

	/** TCP/UDP/TLS 용. HTTP 설정은 기본값으로 둔다. */
	public TargetSpec(String name, String protocol, String host, int port, int connections, Framing framing,
			int batchBytes, int sendBufferBytes, int connectTimeoutMs) {
		this(name, protocol, host, port, connections, framing, batchBytes, sendBufferBytes, connectTimeoutMs,
				null, null, null);
	}

	public boolean isHttp() {
		return Protocol.isHttp(protocol);
	}

	/** 표시용 주소. HTTP 는 URL 그대로(http://host:port/path), 그 외는 PROTOCOL://host:port. */
	public String endpoint() {
		if (isHttp()) {
			return protocol.toLowerCase(java.util.Locale.ROOT) + "://" + host + ":" + port + httpPath;
		}
		return protocol + "://" + host + ":" + port;
	}
}
