package com.tms.tester.transport;

import io.netty.buffer.ByteBufAllocator;

/**
 * 전송 프로토콜 구현의 공통 인터페이스.
 * <p>확장 지점: 새 프로토콜(TLS, HTTP, Kafka 등)은 이 인터페이스와
 * {@link TransportFactory} 를 구현해 등록하면 기존 코드 수정 없이 동작한다.
 */
public interface LogSender extends AutoCloseable {

	/** 연결을 수립한다. 실패 시 예외를 던진다(연결 테스트는 이 결과를 그대로 쓴다). */
	void connect() throws Exception;

	/**
	 * 연결이 안 되거나 끊겨도 스스로 계속 다시 붙는 송신기인가.
	 * <p>true 면 {@link #connect()} 가 실패해도 전송 작업을 시작한다. 수신 서버가 늦게 뜨거나
	 * 잠깐 내려가도 작업이 멈추지 않게 하려는 것이다. 연결이 없는 동안 만든 로그는
	 * 포화 정책대로 버리거나(DROP) 기다린다(BLOCK).
	 */
	default boolean keepsRetrying() {
		return false;
	}

	/** 렌더링 버퍼를 할당할 allocator (풀드 다이렉트 버퍼). */
	ByteBufAllocator allocator();

	/** 워커별 전용 창구. 워커 기동 시 1회 획득해 재사용한다. */
	SendEndpoint endpoint(int workerIndex);

	SenderState state();

	/** 마지막 오류 메시지(없으면 null). */
	String lastError();

	/** 연결 테스트 결과로 보여 줄 추가 설명(없으면 null). 예: HTTP 서버가 돌려준 상태 코드. */
	default String connectMessage() {
		return null;
	}

	/**
	 * 보냈지만 수신측이 받지 않았다고 확인된 건수(예: HTTP 4xx/5xx 응답). 워커는 보낼 때 이미 "전송"으로
	 * 셌으므로 {@link LoadGenerator} 가 이 값만큼 전송에서 빼고 버림으로 옮긴다. 응답이 없는 프로토콜은 0.
	 */
	default long failedRecords() {
		return 0;
	}

	/** {@link #failedRecords()} 에 해당하는 바이트 수. */
	default long failedBytes() {
		return 0;
	}

	String describe();

	@Override
	void close();
}
