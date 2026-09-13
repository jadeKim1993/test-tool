package com.tms.tester.transport;

import java.util.concurrent.atomic.LongAdder;

/**
 * 전송 카운터.
 * <p>{@link LongAdder} 는 스레드별 셀에 나눠 더하므로 워커가 늘어도 경합이 없다.
 * 핫패스에서 허용되는 유일한 공유 상태다.
 */
public final class SendStats {

	private final LongAdder sent = new LongAdder();
	private final LongAdder bytes = new LongAdder();
	private final LongAdder dropped = new LongAdder();
	private final LongAdder errors = new LongAdder();
	private final LongAdder oversized = new LongAdder();

	public void recordSent(long count, long byteCount) {
		sent.add(count);
		bytes.add(byteCount);
	}

	public void recordDropped(long count) {
		dropped.add(count);
	}

	public void recordError() {
		errors.increment();
	}

	public void recordOversized() {
		oversized.increment();
	}

	public long sent() {
		return sent.sum();
	}

	public long bytes() {
		return bytes.sum();
	}

	public long dropped() {
		return dropped.sum();
	}

	public long errors() {
		return errors.sum();
	}

	public long oversized() {
		return oversized.sum();
	}
}
