package com.tms.tester.generator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tms.tester.generator.writers.StandardFunctions;

/**
 * 플레이스홀더 함수 레지스트리.
 * <p>확장 지점: {@link #register(ValueWriterFactory)} 로 함수를 추가하면
 * 템플릿 문법과 UI 도움말에 즉시 반영된다.
 */
public final class ValueWriterRegistry {

	private final Map<String, ValueWriterFactory> factories = new LinkedHashMap<>();

	public static ValueWriterRegistry standard() {
		ValueWriterRegistry registry = new ValueWriterRegistry();
		StandardFunctions.all().forEach(registry::register);
		return registry;
	}

	public ValueWriterRegistry register(ValueWriterFactory factory) {
		factories.put(factory.name().toLowerCase(Locale.ROOT), factory);
		return this;
	}

	public boolean contains(String name) {
		return factories.containsKey(name.toLowerCase(Locale.ROOT));
	}

	public ValueWriter create(String name, FunctionArgs args) {
		ValueWriterFactory factory = factories.get(name.toLowerCase(Locale.ROOT));
		if (factory == null) {
			throw new TemplateSyntaxException("알 수 없는 함수입니다: " + name + " (사용 가능: " + String.join(", ", factories.keySet()) + ")", -1);
		}
		return factory.create(args);
	}

	/** UI 도움말/자동완성용 함수 목록. */
	public List<FunctionSpec> specs() {
		List<FunctionSpec> specs = new ArrayList<>(factories.size());
		factories.values().forEach(factory -> specs.add(factory.spec()));
		return specs;
	}
}
