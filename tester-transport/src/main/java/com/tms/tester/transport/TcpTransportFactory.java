package com.tms.tester.transport;

import com.tms.tester.common.Protocol;

public final class TcpTransportFactory implements TransportFactory {

	@Override
	public String protocol() {
		return Protocol.TCP;
	}

	@Override
	public String description() {
		return "TCP 스트림 전송 (배치 write, 자동 재연결)";
	}

	@Override
	public LogSender create(TargetSpec spec, int workerCount, NettyRuntime runtime) {
		return new TcpLogSender(spec, workerCount, runtime, false);
	}
}
