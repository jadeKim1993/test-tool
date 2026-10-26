package com.tms.tester.transport;

import com.tms.tester.common.Protocol;

public final class HttpTransportFactory implements TransportFactory {

	@Override
	public String protocol() {
		return Protocol.HTTP;
	}

	@Override
	public String description() {
		return "HTTP POST 로 묶어 전송 (텍스트 줄·JSON·Splunk HEC·Elasticsearch _bulk·Loki, keep-alive, 자동 재연결)";
	}

	@Override
	public LogSender create(TargetSpec spec, int workerCount, NettyRuntime runtime) {
		return new HttpLogSender(spec, workerCount, runtime, false);
	}
}
