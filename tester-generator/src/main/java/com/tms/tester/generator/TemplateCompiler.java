package com.tms.tester.generator;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tms.tester.common.LogFormat;

import io.netty.buffer.ByteBuf;

/**
 * 템플릿 소스를 {@link CompiledTemplate} 로 변환한다.
 * <p>파싱은 <b>저장/워커 기동 시점에 1회</b>만 수행하고, 렌더링 경로에는
 * 정규식도 문자열 연결도 남기지 않는 것이 이 설계의 핵심이다.
 */
public final class TemplateCompiler {

	/** {@code key=value} 로 해석할 이름 인자. 그 외 {@code =} 는 값의 일부로 둔다. */
	private static final Set<String> NAMED_ARG_KEYS = Set.of("step", "charset", "zone", "scale", "locale");

	private final ValueWriterRegistry registry;
	private final boolean strict;

	public TemplateCompiler() {
		this(ValueWriterRegistry.standard());
	}

	public TemplateCompiler(ValueWriterRegistry registry) {
		this(registry, false);
	}

	/**
	 * @param strict true 면 플레이스홀더 안의 중첩 {@code ${...}} 를 오류로 거부한다.
	 *               파서는 중첩을 지원하지 않아 안쪽 {@code }} 에서 바깥을 닫아 버리고, 남은 글자는
	 *               리터럴로 섞여 나간다(오류 없이). 저장·미리보기는 strict 로 막고, 엔진은 이미 저장된
	 *               템플릿이 갑자기 기동에 실패하지 않도록 예전처럼 관대하게 둔다.
	 */
	public TemplateCompiler(ValueWriterRegistry registry, boolean strict) {
		this.registry = registry;
		this.strict = strict;
	}

	public ValueWriterRegistry registry() {
		return registry;
	}

	public CompiledTemplate compile(String content) {
		return compile(0L, "preview", LogFormat.RAW, StandardCharsets.UTF_8, content);
	}

	public CompiledTemplate compile(long templateId, String name, LogFormat format, Charset charset, String content) {
		if (content == null || content.isEmpty()) {
			throw new TemplateSyntaxException("템플릿 내용이 비어 있습니다", -1);
		}
		Parser parser = new Parser(charset == null ? StandardCharsets.UTF_8 : charset);
		if (format != null && !format.header().isEmpty()) {
			parser.header = true;
			parser.parse(format.header());
			parser.header = false;
		}
		parser.parse(content);
		int[] origins = parser.origins.stream().mapToInt(Integer::intValue).toArray();
		return new CompiledTemplate(templateId, name, parser.segments.toArray(new ValueWriter[0]), parser.slots.size(),
				origins, List.copyOf(parser.placeholders));
	}

	/** 문법 검증만 수행한다. 오류가 있으면 예외를 던진다. */
	public void validate(String content) {
		compile(content);
	}

	private final class Parser {

		private final Charset charset;
		private final List<ValueWriter> segments = new ArrayList<>();
		/** segments 와 같은 길이. 각 조각의 출처(미리보기 강조용) — {@link CompiledTemplate#renderParts}. */
		private final List<Integer> origins = new ArrayList<>();
		private final List<CompiledTemplate.Placeholder> placeholders = new ArrayList<>();
		private final Map<String, Integer> slots = new LinkedHashMap<>();
		private final StringBuilder literal = new StringBuilder();
		/** 포맷 헤더를 파싱하는 중이면 true. 헤더 조각은 본문 위치가 없다. */
		private boolean header;
		private String lastFunction;

		private Parser(Charset charset) {
			this.charset = charset;
		}

		void parse(String content) {
			int i = 0;
			int length = content.length();
			while (i < length) {
				char c = content.charAt(i);
				// \${ 는 리터럴 ${ 로 해석한다.
				if (c == '\\' && i + 2 < length && content.charAt(i + 1) == '$' && content.charAt(i + 2) == '{') {
					literal.append("${");
					i += 3;
					continue;
				}
				if (c == '$' && i + 1 < length && content.charAt(i + 1) == '{') {
					int close = content.indexOf('}', i + 2);
					if (close < 0) {
						throw new TemplateSyntaxException("닫히지 않은 플레이스홀더입니다", i);
					}
					int nested = content.indexOf("${", i + 2);
					if (strict && nested >= 0 && nested < close) {
						throw new TemplateSyntaxException("플레이스홀더 안에 ${...} 를 넣을 수 없습니다. "
								+ "예: ${choice(-:80,admin:10,guest:10)} 처럼 값을 직접 나열하세요", i);
					}
					flushLiteral();
					try {
						segments.add(placeholder(content.substring(i + 2, close)));
					}
					catch (TemplateSyntaxException e) {
						throw new TemplateSyntaxException(e.getMessage(), i);
					}
					if (header) {
						origins.add(CompiledTemplate.ORIGIN_HEADER);
					}
					else {
						origins.add(placeholders.size());
						placeholders.add(new CompiledTemplate.Placeholder(i, close + 1,
								content.substring(i, close + 1), lastFunction));
					}
					i = close + 1;
					continue;
				}
				literal.append(c);
				i++;
			}
			flushLiteral();
		}

		private void flushLiteral() {
			if (literal.length() > 0) {
				segments.add(new StaticWriter(literal.toString().getBytes(charset)));
				origins.add(header ? CompiledTemplate.ORIGIN_HEADER : CompiledTemplate.ORIGIN_LITERAL);
				literal.setLength(0);
			}
		}

		private ValueWriter placeholder(String body) {
			String expression = body.trim();
			if (expression.isEmpty()) {
				throw new TemplateSyntaxException("빈 플레이스홀더입니다", -1);
			}

			// "... as 이름" 바인딩 분리 (괄호 밖에서만 인식)
			String binding = null;
			int asIndex = indexOfAs(expression);
			if (asIndex >= 0) {
				binding = expression.substring(asIndex + 4).trim();
				expression = expression.substring(0, asIndex).trim();
				if (binding.isEmpty()) {
					throw new TemplateSyntaxException("as 뒤에 변수 이름이 필요합니다", -1);
				}
			}

			String function;
			List<String> positional = new ArrayList<>();
			Map<String, String> named = new HashMap<>();
			int paren = expression.indexOf('(');
			if (paren < 0) {
				function = expression;
			}
			else {
				if (!expression.endsWith(")")) {
					throw new TemplateSyntaxException("괄호가 닫히지 않았습니다: " + expression, -1);
				}
				function = expression.substring(0, paren).trim();
				splitArgs(expression.substring(paren + 1, expression.length() - 1), positional, named);
			}

			if (function.isEmpty()) {
				throw new TemplateSyntaxException("함수 이름이 없습니다", -1);
			}
			lastFunction = function.toLowerCase(java.util.Locale.ROOT);

			ValueWriter writer;
			if ("ref".equalsIgnoreCase(function)) {
				if (positional.isEmpty()) {
					throw new TemplateSyntaxException("ref 함수는 변수 이름이 필요합니다", -1);
				}
				String target = positional.get(0).trim();
				Integer slot = slots.get(target);
				if (slot == null) {
					throw new TemplateSyntaxException("먼저 정의되지 않은 변수입니다: " + target
							+ " (앞쪽에 ${...  as " + target + "} 이 있어야 합니다)", -1);
				}
				writer = new RefWriter(slot);
			}
			else {
				writer = registry.create(function, new FunctionArgs(function, positional, named, charset));
			}

			if (binding != null) {
				int slot = slots.computeIfAbsent(binding, key -> slots.size());
				writer = new BindingWriter(slot, writer);
			}
			return writer;
		}

		/** 괄호 밖의 " as " 위치를 찾는다. */
		private int indexOfAs(String expression) {
			int depth = 0;
			boolean quoted = false;
			for (int i = 0; i < expression.length() - 3; i++) {
				char c = expression.charAt(i);
				if (c == '\'') {
					quoted = !quoted;
				}
				else if (!quoted && c == '(') {
					depth++;
				}
				else if (!quoted && c == ')') {
					depth--;
				}
				else if (!quoted && depth == 0 && c == ' '
						&& expression.startsWith("as ", i + 1)) {
					return i;
				}
			}
			return -1;
		}

		/** 최상위 콤마로 인자를 분리한다. 작은따옴표로 감싸면 콤마를 값에 포함할 수 있다. */
		private void splitArgs(String raw, List<String> positional, Map<String, String> named) {
			if (raw.isBlank()) {
				return;
			}
			StringBuilder current = new StringBuilder();
			int depth = 0;
			boolean quoted = false;
			for (int i = 0; i < raw.length(); i++) {
				char c = raw.charAt(i);
				if (c == '\'') {
					quoted = !quoted;
					continue;
				}
				if (!quoted) {
					if (c == '(') {
						depth++;
					}
					else if (c == ')') {
						depth--;
					}
					else if (c == ',' && depth == 0) {
						addArg(current.toString(), positional, named);
						current.setLength(0);
						continue;
					}
				}
				current.append(c);
			}
			addArg(current.toString(), positional, named);
		}

		private void addArg(String raw, List<String> positional, Map<String, String> named) {
			String value = raw.trim();
			int equals = value.indexOf('=');
			if (equals > 0) {
				String key = value.substring(0, equals).trim().toLowerCase(java.util.Locale.ROOT);
				if (NAMED_ARG_KEYS.contains(key)) {
					named.put(key, value.substring(equals + 1).trim());
					return;
				}
			}
			positional.add(value);
		}
	}

	/** 고정 문자열. 사전 인코딩된 바이트를 그대로 복사한다. */
	static final class StaticWriter implements ValueWriter {

		private final byte[] bytes;

		StaticWriter(byte[] bytes) {
			this.bytes = bytes;
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			out.writeBytes(bytes);
		}

		@Override
		public int estimatedSize() {
			return bytes.length;
		}
	}

	/** 생성한 값을 슬롯에 보관해 같은 로그 안에서 재사용할 수 있게 한다. */
	static final class BindingWriter implements ValueWriter {

		private final int slot;
		private final ValueWriter delegate;

		BindingWriter(int slot, ValueWriter delegate) {
			this.slot = slot;
			this.delegate = delegate;
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			int start = out.writerIndex();
			delegate.write(out, ctx);
			ctx.captureSlot(slot, out, start, out.writerIndex() - start);
		}

		@Override
		public int estimatedSize() {
			return delegate.estimatedSize();
		}
	}

	/** 앞서 바인딩된 값을 다시 기록한다. */
	static final class RefWriter implements ValueWriter {

		private final int slot;

		RefWriter(int slot) {
			this.slot = slot;
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			byte[] value = ctx.slot(slot);
			if (value != null) {
				out.writeBytes(value, 0, ctx.slotLength(slot));
			}
		}

		@Override
		public int estimatedSize() {
			return 16;
		}
	}
}
