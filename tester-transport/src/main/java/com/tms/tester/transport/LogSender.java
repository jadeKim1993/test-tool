package com.tms.tester.transport;

import io.netty.buffer.ByteBufAllocator;

/**
 * 전송 프로토콜 구현의 공통 인터페이스.
 * <p>확장 지점: 새 프로토콜(TLS, HTTP, Kafka 등)은 이 인터페이스와
 * {@link TransportFactory} 를 구현해 등록하면 기존 코드 수정 없이 동작한다.
 */
public interface LogSender extends AutoCloseable {

	/** 연결을 수립한다. 실패 시 예외를 던진다. */
	void connect() throws Exception;

	/** 렌더링 버퍼를 할당할 allocator (풀드 다이렉트 버퍼). */
	ByteBufAllocator allocator();

	/** 워커별 전용 창구. 워커 기동 시 1회 획득해 재사용한다. */
	SendEndpoint endpoint(int workerIndex);

	SenderState state();

	/** 마지막 오류 메시지(없으면 null). */
	String lastError();

	String describe();

	@Override
	void close();
}
