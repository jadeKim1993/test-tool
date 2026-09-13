package com.tms.tester.transport;

/**
 * 프로토콜 구현 등록 지점.
 * <p>{@link TransportRegistry} 에 등록된 프로토콜은 대상 등록 화면의
 * 프로토콜 목록에 자동으로 나타난다.
 */
public interface TransportFactory {

	/** 프로토콜 식별자 (예: "TCP"). 대소문자를 구분하지 않는다. */
	String protocol();

	/** UI 표시용 설명. */
	String description();

	LogSender create(TargetSpec spec, int workerCount, NettyRuntime runtime);
}
