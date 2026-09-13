package com.tms.tester.transport;

import io.netty.buffer.ByteBuf;

/**
 * 워커 1개가 전용으로 사용하는 송신 창구.
 * <p>워커마다 별도 채널을 갖게 하여 스레드 간 경합을 없애는 것이 목적이다.
 */
public interface SendEndpoint {

	/** 송신 버퍼에 여유가 있는지. false 면 수신측이 못 따라오고 있다는 뜻이다. */
	boolean isWritable();

	/**
	 * 버퍼를 전송 큐에 넣는다. <b>성공/실패와 무관하게 버퍼 소유권은 이 메서드가 가져간다.</b>
	 *
	 * @return 큐잉 성공 여부. false 면 호출자가 dropped 로 집계한다
	 */
	boolean send(ByteBuf payload);

	/** 큐에 쌓인 내용을 실제 소켓으로 내보낸다(syscall 발생 지점). */
	void flush();
}
