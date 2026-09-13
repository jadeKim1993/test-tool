package com.tms.tester.transport;

import com.tms.tester.common.Framing;

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
		int connectTimeoutMs
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
	}

	public String endpoint() {
		return protocol + "://" + host + ":" + port;
	}
}
