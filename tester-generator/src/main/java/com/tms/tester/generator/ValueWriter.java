package com.tms.tester.generator;

import io.netty.buffer.ByteBuf;

/**
 * 템플릿의 한 조각을 버퍼에 직접 기록한다.
 * <p>중간 {@code String} 을 만들지 않는 것이 이 인터페이스의 존재 이유다.
 * 구현체는 내부 캐시를 가질 수 있으므로 스레드 안전을 가정하지 않는다
 * ({@link CompiledTemplate} 이 워커마다 따로 컴파일된다).
 */
@FunctionalInterface
public interface ValueWriter {

	void write(ByteBuf out, RenderContext ctx);

	/** 버퍼 초기 크기 산정을 위한 예상 바이트 수. */
	default int estimatedSize() {
		return 8;
	}
}
