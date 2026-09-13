package com.tms.tester.generator;

/**
 * 플레이스홀더 함수 1개를 정의하는 확장 지점.
 * <p>새 랜덤 값 종류를 추가하려면 이 인터페이스를 구현해
 * {@link ValueWriterRegistry} 에 등록하기만 하면 된다. 기존 코드 수정은 필요 없다.
 */
public interface ValueWriterFactory {

	FunctionSpec spec();

	/** 컴파일 시점에 1회 호출된다. 반환된 writer 는 렌더링마다 재사용된다. */
	ValueWriter create(FunctionArgs args);

	default String name() {
		return spec().name();
	}
}
