package com.tms.tester.generator;

import io.netty.buffer.ByteBuf;

/**
 * 파싱이 끝난 템플릿. 렌더링 시에는 세그먼트 배열을 순회하며 버퍼에 기록만 한다.
 * <p><b>스레드 안전하지 않다.</b> 워커 스레드마다 컴파일하여 사용한다.
 */
public final class CompiledTemplate {

	private final long templateId;
	private final String name;
	private final ValueWriter[] segments;
	private final int slotCount;
	private final int estimatedSize;

	CompiledTemplate(long templateId, String name, ValueWriter[] segments, int slotCount) {
		this.templateId = templateId;
		this.name = name;
		this.segments = segments;
		this.slotCount = slotCount;
		int size = 16;
		for (ValueWriter segment : segments) {
			size += segment.estimatedSize();
		}
		this.estimatedSize = size;
	}

	public long templateId() {
		return templateId;
	}

	public String name() {
		return name;
	}

	public int estimatedSize() {
		return estimatedSize;
	}

	/** 로그 1건을 버퍼에 덧붙인다. */
	public void render(ByteBuf out, RenderContext ctx) {
		ctx.ensureSlots(slotCount);
		final ValueWriter[] local = segments;
		for (int i = 0; i < local.length; i++) {
			local[i].write(out, ctx);
		}
	}

	/** 디버그/미리보기용. 핫패스에서 사용하지 않는다. */
	public String renderToString(RenderContext ctx) {
		ByteBuf buf = io.netty.buffer.Unpooled.buffer(estimatedSize);
		try {
			render(buf, ctx);
			return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
		}
		finally {
			buf.release();
		}
	}
}
