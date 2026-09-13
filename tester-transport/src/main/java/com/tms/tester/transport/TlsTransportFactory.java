package com.tms.tester.transport;

import com.tms.tester.common.Protocol;

public final class TlsTransportFactory implements TransportFactory {

	@Override
	public String protocol() {
		return Protocol.TLS;
	}

	@Override
	public String description() {
		return "TLS 로 암호화된 TCP 스트림 전송 (자체 서명 인증서 허용, 배치 write, 자동 재연결)";
	}

	@Override
	public LogSender create(TargetSpec spec, int workerCount, NettyRuntime runtime) {
		return new TcpLogSender(spec, workerCount, runtime, true);
	}
}
