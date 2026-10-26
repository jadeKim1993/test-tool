package com.tms.tester.transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 프로토콜 구현 목록.
 * <p>확장 지점: {@link #register(TransportFactory)} 로 추가하면 대상 등록 UI 의
 * 프로토콜 선택지에 자동 반영된다.
 */
public final class TransportRegistry {

	private final Map<String, TransportFactory> factories = new LinkedHashMap<>();

	public static TransportRegistry standard() {
		return new TransportRegistry()
				.register(new TcpTransportFactory())
				.register(new UdpTransportFactory())
				.register(new TlsTransportFactory())
				.register(new HttpTransportFactory())
				.register(new HttpsTransportFactory());
	}

	public TransportRegistry register(TransportFactory factory) {
		factories.put(factory.protocol().toUpperCase(Locale.ROOT), factory);
		return this;
	}

	public boolean supports(String protocol) {
		return protocol != null && factories.containsKey(protocol.toUpperCase(Locale.ROOT));
	}

	public LogSender create(TargetSpec spec, int workerCount, NettyRuntime runtime) {
		TransportFactory factory = factories.get(spec.protocol().toUpperCase(Locale.ROOT));
		if (factory == null) {
			throw new IllegalArgumentException("지원하지 않는 프로토콜입니다: " + spec.protocol()
					+ " (사용 가능: " + String.join(", ", factories.keySet()) + ")");
		}
		return factory.create(spec, workerCount, runtime);
	}

	public List<TransportFactory> all() {
		return new ArrayList<>(factories.values());
	}
}
