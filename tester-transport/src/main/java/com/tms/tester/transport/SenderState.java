package com.tms.tester.transport;

/** 전송 채널의 연결 상태. UI 에 그대로 노출된다. */
public enum SenderState {
	IDLE,
	CONNECTING,
	CONNECTED,
	RECONNECTING,
	FAILED,
	CLOSED
}
