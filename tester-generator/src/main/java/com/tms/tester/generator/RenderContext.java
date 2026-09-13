package com.tms.tester.generator;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 렌더링 1회에 필요한 가변 상태.
 * <p><b>스레드 안전하지 않다.</b> 워커 스레드마다 1개씩 생성해 재사용한다.
 * 시각은 워커 틱마다 한 번만 갱신하여 {@code System.currentTimeMillis()} 호출을
 * 로그 건수가 아닌 틱 횟수로 줄인다.
 */
public final class RenderContext {

	private final XorShiftRandom random;
	/** 여러 워커가 공유한다. 로그 전체에서 순번이 유일해야 하므로 원자적으로 증가시킨다. */
	private final AtomicLong sequence;

	private long now = System.currentTimeMillis();

	/** {@code ${x as name}} 으로 바인딩된 값의 저장소. 인덱스는 컴파일 시점에 확정된다. */
	private byte[][] slots = new byte[4][];
	private int[] slotLengths = new int[4];

	public RenderContext(XorShiftRandom random, AtomicLong sequence) {
		this.random = random;
		this.sequence = sequence;
	}

	public static RenderContext standalone() {
		return new RenderContext(new XorShiftRandom(), new AtomicLong(1));
	}

	public XorShiftRandom random() {
		return random;
	}

	public long now() {
		return now;
	}

	/** 워커 틱 시작 시 1회 호출한다. */
	public void tick(long currentTimeMillis) {
		this.now = currentTimeMillis;
	}

	public long nextSequence() {
		return sequence.getAndIncrement();
	}

	void ensureSlots(int count) {
		if (slots.length < count) {
			byte[][] grownSlots = new byte[count][];
			int[] grownLengths = new int[count];
			System.arraycopy(slots, 0, grownSlots, 0, slots.length);
			System.arraycopy(slotLengths, 0, grownLengths, 0, slotLengths.length);
			slots = grownSlots;
			slotLengths = grownLengths;
		}
	}

	/** 방금 버퍼에 기록된 구간을 슬롯으로 복사한다. */
	void captureSlot(int index, io.netty.buffer.ByteBuf source, int start, int length) {
		byte[] slot = slots[index];
		if (slot == null || slot.length < length) {
			slot = new byte[Math.max(length, 32)];
			slots[index] = slot;
		}
		source.getBytes(start, slot, 0, length);
		slotLengths[index] = length;
	}

	byte[] slot(int index) {
		return slots[index];
	}

	int slotLength(int index) {
		return slotLengths[index];
	}
}
