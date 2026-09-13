package com.tms.tester.transport;

import com.tms.tester.common.Protocol;

public final class UdpTransportFactory implements TransportFactory {

	@Override
	public String protocol() {
		return Protocol.UDP;
	}

	@Override
	public String description() {
		return "UDP 데이터그램 전송 (connected socket, 1건=1패킷)";
	}

	@Override
	public LogSender create(TargetSpec spec, int workerCount, NettyRuntime runtime) {
		return new UdpLogSender(spec, workerCount, runtime);
	}
}
