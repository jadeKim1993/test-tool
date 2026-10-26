package com.tms.tester.generator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 파싱이 끝난 템플릿. 렌더링 시에는 세그먼트 배열을 순회하며 버퍼에 기록만 한다.
 * <p><b>스레드 안전하지 않다.</b> 워커 스레드마다 컴파일하여 사용한다.
 */
public final class CompiledTemplate {

	/** 조각 출처: 템플릿 본문의 고정 문자열. 0 이상이면 {@link #placeholders()} 의 인덱스다. */
	public static final int ORIGIN_LITERAL = -1;
	/** 조각 출처: 포맷(syslog 등)이 자동으로 붙인 헤더. */
	public static final int ORIGIN_HEADER = -2;

	/**
	 * 본문에 쓰인 플레이스홀더 하나.
	 *
	 * @param start      본문 안 시작 위치({@code $} 의 인덱스)
	 * @param end        끝 위치(닫는 {@code }} 다음 인덱스)
	 * @param expression 원문 그대로 (예: {@code ${int(1,10)}})
	 * @param function   소문자 함수명 (예: {@code int}, {@code ref})
	 */
	public record Placeholder(int start, int end, String expression, String function) {
	}

	/** 렌더 결과의 한 조각과 그 출처({@link #ORIGIN_LITERAL}/{@link #ORIGIN_HEADER}/플레이스홀더 인덱스). */
	public record Part(String text, int source) {
	}

	private final long templateId;
	private final String name;
	private final ValueWriter[] segments;
	private final int slotCount;
	private final int estimatedSize;
	private final int[] origins;
	private final List<Placeholder> placeholders;

	CompiledTemplate(long templateId, String name, ValueWriter[] segments, int slotCount,
			int[] origins, List<Placeholder> placeholders) {
		this.templateId = templateId;
		this.name = name;
		this.segments = segments;
		this.slotCount = slotCount;
		this.origins = origins;
		this.placeholders = placeholders;
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

	public List<Placeholder> placeholders() {
		return placeholders;
	}

	/**
	 * 미리보기용. 로그 1건을 조각별로 렌더해 어느 부분이 어느 플레이스홀더에서 나왔는지 돌려준다.
	 * 연속된 헤더 조각은 하나로 합친다. 핫패스에서 사용하지 않는다.
	 */
	public List<Part> renderParts(RenderContext ctx) {
		ctx.ensureSlots(slotCount);
		ByteBuf buf = Unpooled.buffer(estimatedSize);
		try {
			List<Part> parts = new ArrayList<>(segments.length);
			for (int i = 0; i < segments.length; i++) {
				int start = buf.writerIndex();
				segments[i].write(buf, ctx);
				String text = buf.toString(start, buf.writerIndex() - start, StandardCharsets.UTF_8);
				int last = parts.size() - 1;
				if (origins[i] < 0 && last >= 0 && parts.get(last).source() == origins[i]) {
					parts.set(last, new Part(parts.get(last).text() + text, origins[i]));
				}
				else {
					parts.add(new Part(text, origins[i]));
				}
			}
			return parts;
		}
		finally {
			buf.release();
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
