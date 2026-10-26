package com.tms.tester.transport;

import com.tms.tester.common.Protocol;

public final class HttpsTransportFactory implements TransportFactory {

	@Override
	public String protocol() {
		return Protocol.HTTPS;
	}

	@Override
	public String description() {
		return "HTTPS POST 로 묶어 전송 (자체 서명 인증서 허용, 형식은 HTTP 와 같음)";
	}

	@Override
	public LogSender create(TargetSpec spec, int workerCount, NettyRuntime runtime) {
		return new HttpLogSender(spec, workerCount, runtime, true);
	}
}
