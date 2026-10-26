package com.tms.tester.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.tms.tester.generator.TemplateCompiler;
import com.tms.tester.generator.ValueWriterRegistry;
import com.tms.tester.transport.NettyRuntime;
import com.tms.tester.transport.TransportFactory;
import com.tms.tester.transport.TransportRegistry;

/**
 * 부하 엔진 관련 빈.
 * <p>Netty 자원은 애플리케이션당 하나만 두고 모든 전송 작업이 공유한다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(TesterProperties.class)
public class EngineConfig {

	/** 애플리케이션 종료 시 close() 가 호출되도록 Bean 으로 등록한다. */
	@Bean(destroyMethod = "close")
	public NettyRuntime nettyRuntime(TesterProperties properties) {
		int ioThreads = properties.getEngine().getIoThreads();
		return ioThreads > 0 ? new NettyRuntime(ioThreads) : new NettyRuntime();
	}

	/**
	 * 프로토콜 레지스트리.
	 * <p>확장 지점: {@link TransportFactory} 구현을 빈으로 등록하기만 하면
	 * 여기서 자동으로 수집되어 대상 등록 화면의 프로토콜 목록에 나타난다.
	 */
	@Bean
	public TransportRegistry transportRegistry(java.util.List<TransportFactory> customFactories) {
		TransportRegistry registry = TransportRegistry.standard();
		customFactories.forEach(registry::register);
		return registry;
	}

	/**
	 * 플레이스홀더 함수 레지스트리.
	 * <p>확장 지점: {@link com.tms.tester.generator.ValueWriterFactory} 를 빈으로 등록하면
	 * 템플릿 문법과 UI 도움말에 즉시 반영된다.
	 */
	@Bean
	public ValueWriterRegistry valueWriterRegistry(
			java.util.List<com.tms.tester.generator.ValueWriterFactory> customFactories) {
		ValueWriterRegistry registry = ValueWriterRegistry.standard();
		customFactories.forEach(registry::register);
		return registry;
	}

	/** 저장·미리보기용. 중첩 플레이스홀더처럼 조용히 잘못 렌더되는 문법을 오류로 막는다. */
	@Bean
	public TemplateCompiler templateCompiler(ValueWriterRegistry registry) {
		return new TemplateCompiler(registry, true);
	}
}
