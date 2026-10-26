package com.tms.tester.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tms.tester.common.Framing;
import com.tms.tester.common.HttpFormat;
import com.tms.tester.common.LogFormat;
import com.tms.tester.common.OverflowPolicy;
import com.tms.tester.common.SelectionStrategy;
import com.tms.tester.common.WireEncoding;
import com.tms.tester.generator.Dictionaries;
import com.tms.tester.generator.FunctionSpec;
import com.tms.tester.generator.ValueWriterRegistry;
import com.tms.tester.transport.NettyRuntime;
import com.tms.tester.transport.TransportFactory;
import com.tms.tester.transport.TransportRegistry;

/**
 * UI 가 선택지를 하드코딩하지 않도록 서버가 가진 목록을 그대로 내려준다.
 * <p>확장 지점에 새 구현을 등록하면 화면의 드롭다운/도움말이 자동으로 늘어난다.
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

	private final ValueWriterRegistry valueWriterRegistry;
	private final TransportRegistry transportRegistry;
	private final NettyRuntime nettyRuntime;

	public MetaController(ValueWriterRegistry valueWriterRegistry, TransportRegistry transportRegistry,
			NettyRuntime nettyRuntime) {
		this.valueWriterRegistry = valueWriterRegistry;
		this.transportRegistry = transportRegistry;
		this.nettyRuntime = nettyRuntime;
	}

	public record ProtocolInfo(String protocol, String description) {
	}

	public record EnumOption(String value, String label) {
	}

	/** 템플릿 편집기의 자동완성/도움말 원천. */
	@GetMapping("/functions")
	public List<FunctionSpec> functions() {
		return valueWriterRegistry.specs();
	}

	@GetMapping("/protocols")
	public List<ProtocolInfo> protocols() {
		List<ProtocolInfo> result = new ArrayList<>();
		for (TransportFactory factory : transportRegistry.all()) {
			result.add(new ProtocolInfo(factory.protocol(), factory.description()));
		}
		return result;
	}

	private List<EnumOption> encodingOptions() {
		List<EnumOption> result = new ArrayList<>();
		for (WireEncoding encoding : WireEncoding.values()) {
			result.add(new EnumOption(encoding.name(), encoding.label()));
		}
		return result;
	}

	/** HTTP 본문 형식. 화면이 형식을 고르면 그 수집기의 기본 경로·포트를 채운다. */
	public record HttpFormatOption(String value, String label, String contentType, String defaultPath,
			int defaultPort) {
	}

	private List<HttpFormatOption> httpFormatOptions() {
		List<HttpFormatOption> result = new ArrayList<>();
		for (HttpFormat format : HttpFormat.values()) {
			result.add(new HttpFormatOption(format.name(), format.label(), format.contentType(),
					format.defaultPath(), format.defaultPort()));
		}
		return result;
	}

	@GetMapping("/options")
	public Map<String, Object> options() {
		return Map.of(
				"formats", List.of(
						new EnumOption(LogFormat.RAW.name(), "원본 그대로"),
						new EnumOption(LogFormat.SYSLOG_RFC3164.name(), "Syslog RFC3164 헤더 부착"),
						new EnumOption(LogFormat.SYSLOG_RFC5424.name(), "Syslog RFC5424 헤더 부착")),
				"framings", List.of(
						new EnumOption(Framing.NEWLINE.name(), "개행 구분 (기본)"),
						new EnumOption(Framing.NONE.name(), "구분자 없음"),
						new EnumOption(Framing.LENGTH_PREFIX.name(), "4바이트 길이 프리픽스"),
						new EnumOption(Framing.OCTET_COUNTING.name(), "RFC6587 octet counting")),
				"selections", List.of(
						new EnumOption(SelectionStrategy.WEIGHTED_RANDOM.name(), "가중치 랜덤 (실제 비율 흉내)"),
						new EnumOption(SelectionStrategy.ROUND_ROBIN.name(), "돌아가며 균등"),
						new EnumOption(SelectionStrategy.SEQUENTIAL.name(), "목록 순서대로")),
				"overflowPolicies", List.of(
						new EnumOption(OverflowPolicy.DROP.name(), "버리고 속도 유지 (수신측 한계 확인)"),
						new EnumOption(OverflowPolicy.BLOCK.name(), "대기 (유실 없이 최대 속도)")),
				"encodings", encodingOptions(),
				"httpFormats", httpFormatOptions(),
				"dictionaries", Dictionaries.names(),
				"transport", nettyRuntime.transportName(),
				"cpuCores", Runtime.getRuntime().availableProcessors());
	}
}
