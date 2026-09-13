package com.tms.tester.generator.writers;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.tms.tester.generator.ByteBufs;
import com.tms.tester.generator.Dictionaries;
import com.tms.tester.generator.FunctionArgs;
import com.tms.tester.generator.FunctionSpec;
import com.tms.tester.generator.RenderContext;
import com.tms.tester.generator.TemplateSyntaxException;
import com.tms.tester.generator.ValueWriter;
import com.tms.tester.generator.ValueWriterFactory;
import com.tms.tester.generator.XorShiftRandom;

import io.netty.buffer.ByteBuf;

/**
 * 기본 제공 플레이스홀더 함수 모음.
 * <p>모든 writer 는 {@link ByteBuf} 에 직접 기록하며 렌더링 중 객체를 만들지 않는 것을 원칙으로 한다.
 * 새 함수는 이 클래스를 건드리지 않고 {@link ValueWriterFactory} 구현을 추가해 등록하면 된다.
 */
public final class StandardFunctions {

	private static final byte[] HEX_LOWER = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

	private StandardFunctions() {
	}

	public static List<ValueWriterFactory> all() {
		List<ValueWriterFactory> factories = new ArrayList<>();

		// ---------- 네트워크 ----------
		factories.add(factory(
				new FunctionSpec("ip", "ip(cidr?)", "임의의 IPv4 주소. CIDR 를 주면 그 대역 안에서 생성", "${ip(10.0.0.0/8)}", "10.42.7.3", "네트워크"),
				args -> args.size() == 0 ? new PublicIpWriter() : new CidrIpWriter(args.require(0, "CIDR"))));
		factories.add(factory(
				new FunctionSpec("ipv4", "ipv4(cidr?)", "ip 와 동일(별칭)", "${ipv4}", "203.0.113.47", "네트워크"),
				args -> args.size() == 0 ? new PublicIpWriter() : new CidrIpWriter(args.require(0, "CIDR"))));
		factories.add(factory(
				new FunctionSpec("ipv6", "ipv6()", "임의의 IPv6 주소", "${ipv6}", "2001:db8:4a2c::8f1e", "네트워크"),
				args -> new Ipv6Writer()));
		factories.add(factory(
				new FunctionSpec("port", "port(min?,max?)", "포트 번호", "${port(1024,65535)}", "48123", "네트워크"),
				args -> new IntWriter(args.intAt(0, 1), args.intAt(1, 65535), 1)));
		factories.add(factory(
				new FunctionSpec("mac", "mac()", "MAC 주소", "${mac}", "3c:22:fb:19:0a:7d", "네트워크"),
				args -> new MacWriter()));
		factories.add(factory(
				new FunctionSpec("host", "host(prefix?,count?)", "호스트명. prefix-번호 형태", "${host(web,20)}", "web-07", "네트워크"),
				args -> new HostWriter(args.str(0, "host"), args.intAt(1, 10), args.charset())));

		// ---------- 숫자 ----------
		factories.add(factory(
				new FunctionSpec("int", "int(min,max,step=n)", "정수 균등분포(양 끝 포함)", "${int(100,599)}", "404", "숫자"),
				args -> new IntWriter(args.intAt(0, 0), args.intAt(1, 100), args.named("step", 1))));
		factories.add(factory(
				new FunctionSpec("long", "long(min,max)", "64비트 정수", "${long(1,9999999999)}", "8821037461", "숫자"),
				args -> new LongWriter(args.longAt(0, 0), args.longAt(1, Long.MAX_VALUE / 2))));
		factories.add(factory(
				new FunctionSpec("float", "float(min,max,scale?)", "실수. scale 은 소수점 자리수(기본 2)", "${float(0,1,3)}", "0.472", "숫자"),
				args -> new FloatWriter(args.doubleAt(0, 0), args.doubleAt(1, 1), args.intAt(2, 2))));
		factories.add(factory(
				new FunctionSpec("gauss", "gauss(mean,stddev,scale?)", "정규분포. 응답시간처럼 치우친 값에 사용", "${gauss(120,40)}", "137", "숫자"),
				args -> new GaussWriter(args.doubleAt(0, 100), args.doubleAt(1, 15), args.intAt(2, 0))));
		factories.add(factory(
				new FunctionSpec("seq", "seq(start?)", "전송 순번. 워커가 여러 개여도 중복되지 않는다", "${seq}", "1042", "숫자"),
				args -> new SeqWriter(args.longAt(0, 0))));
		factories.add(factory(
				new FunctionSpec("bool", "bool(truePercent?)", "true/false", "${bool(30)}", "false", "숫자"),
				args -> new BoolWriter(args.intAt(0, 50))));

		// ---------- 문자열 ----------
		factories.add(factory(
				new FunctionSpec("str", "str(len) 또는 str(min,max,charset?)", "랜덤 문자열. charset: alnum|alpha|lower|upper|digit|hex", "${str(8)}", "a7Kd9x2P", "문자열"),
				StandardFunctions::createStringWriter));
		factories.add(factory(
				new FunctionSpec("hex", "hex(len?)", "16진 문자열", "${hex(16)}", "9f2c41ab77d0e315", "문자열"),
				args -> new HexWriter(args.intAt(0, 8))));
		factories.add(factory(
				new FunctionSpec("uuid", "uuid()", "UUID v4", "${uuid}", "7c9e4b12-...", "문자열"),
				args -> new UuidWriter()));
		factories.add(factory(
				new FunctionSpec("choice", "choice(a,b,c) 또는 choice(200:70,404:30)", "목록에서 선택. 콜론 뒤 숫자로 가중치 지정", "${choice(GET:70,POST:30)}", "GET", "문자열"),
				args -> new ChoiceWriter(args.positional(), args.charset())));
		factories.add(factory(
				new FunctionSpec("oneof", "oneof(a,b,c)", "choice 와 동일(별칭)", "${oneof(A,B)}", "A", "문자열"),
				args -> new ChoiceWriter(args.positional(), args.charset())));
		factories.add(factory(
				new FunctionSpec("dict", "dict(name)", "내장/사용자 사전에서 선택", "${dict(user_agents)}", "curl/8.4.0", "문자열"),
				args -> new ChoiceWriter(List.of(Dictionaries.get(args.require(0, "사전 이름"))), args.charset())));
		factories.add(factory(
				new FunctionSpec("word", "word()", "임의 단어", "${word}", "connection", "문자열"),
				args -> new ChoiceWriter(List.of(Dictionaries.get("words")), args.charset())));
		factories.add(factory(
				new FunctionSpec("sentence", "sentence(wordCount?)", "임의 단어를 이어붙인 문장", "${sentence(5)}", "cache retry worker pool shard", "문자열"),
				args -> new SentenceWriter(args.intAt(0, 6), args.charset())));

		// ---------- 시간 ----------
		factories.add(factory(
				new FunctionSpec("ts", "ts(pattern?,locale?)", "현재 시각. 기본 ISO8601", "${ts(yyyy-MM-dd HH:mm:ss.SSS)}", "2026-09-11 12:00:00.123", "시간"),
				args -> new TimestampWriter(args.str(0, null), args.str(1, null), args.named("zone", null), args.charset())));
		factories.add(factory(
				new FunctionSpec("epoch", "epoch()", "유닉스 초", "${epoch}", "1789123456", "시간"),
				args -> new EpochWriter(false)));
		factories.add(factory(
				new FunctionSpec("epochmillis", "epochmillis()", "유닉스 밀리초", "${epochmillis}", "1789123456789", "시간"),
				args -> new EpochWriter(true)));

		return factories;
	}

	private static ValueWriter createStringWriter(FunctionArgs args) {
		int min;
		int max;
		String charset;
		if (args.size() <= 1) {
			min = max = args.intAt(0, 8);
			charset = args.named("charset", "alnum");
		}
		else {
			min = args.intAt(0, 4);
			max = args.intAt(1, 12);
			charset = args.str(2, args.named("charset", "alnum"));
		}
		return new StringWriter(min, max, charset);
	}

	private static ValueWriterFactory factory(FunctionSpec spec, java.util.function.Function<FunctionArgs, ValueWriter> creator) {
		return new ValueWriterFactory() {
			@Override
			public FunctionSpec spec() {
				return spec;
			}

			@Override
			public ValueWriter create(FunctionArgs args) {
				return creator.apply(args);
			}
		};
	}

	// ================= writer 구현 =================

	/** 공인 IP 대역처럼 보이는 임의 IPv4. 사설/예약 대역 첫 옥텟은 피한다. */
	static final class PublicIpWriter implements ValueWriter {
		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			XorShiftRandom r = ctx.random();
			int a = r.nextIntInclusive(1, 223);
			if (a == 10 || a == 127 || a == 172 || a == 192) {
				a = 203;
			}
			ByteBufs.writeAsciiUnsigned(out, a);
			out.writeByte('.');
			ByteBufs.writeAsciiUnsigned(out, r.nextInt(256));
			out.writeByte('.');
			ByteBufs.writeAsciiUnsigned(out, r.nextInt(256));
			out.writeByte('.');
			ByteBufs.writeAsciiUnsigned(out, r.nextIntInclusive(1, 254));
		}

		@Override
		public int estimatedSize() {
			return 15;
		}
	}

	/** CIDR 대역 내부의 임의 IPv4. */
	static final class CidrIpWriter implements ValueWriter {

		private final int base;
		private final int hostBits;

		CidrIpWriter(String cidr) {
			int slash = cidr.indexOf('/');
			String address = slash < 0 ? cidr : cidr.substring(0, slash);
			int prefix = slash < 0 ? 32 : parsePrefix(cidr.substring(slash + 1));
			String[] octets = address.trim().split("\\.");
			if (octets.length != 4) {
				throw new TemplateSyntaxException("IPv4 CIDR 형식이 아닙니다: " + cidr, -1);
			}
			int value = 0;
			for (String octet : octets) {
				int part;
				try {
					part = Integer.parseInt(octet.trim());
				}
				catch (NumberFormatException e) {
					throw new TemplateSyntaxException("IPv4 CIDR 형식이 아닙니다: " + cidr, -1);
				}
				if (part < 0 || part > 255) {
					throw new TemplateSyntaxException("옥텟 범위를 벗어났습니다: " + cidr, -1);
				}
				value = (value << 8) | part;
			}
			this.hostBits = 32 - prefix;
			this.base = (hostBits >= 32) ? 0 : (value & (0xFFFFFFFF << hostBits));
		}

		private static int parsePrefix(String raw) {
			try {
				int prefix = Integer.parseInt(raw.trim());
				if (prefix < 0 || prefix > 32) {
					throw new TemplateSyntaxException("CIDR 프리픽스는 0~32 여야 합니다: " + raw, -1);
				}
				return prefix;
			}
			catch (NumberFormatException e) {
				throw new TemplateSyntaxException("CIDR 프리픽스가 숫자가 아닙니다: " + raw, -1);
			}
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			int host = (hostBits >= 32) ? ctx.random().nextInt() : ctx.random().nextInt(1 << hostBits);
			int address = base | host;
			ByteBufs.writeAsciiUnsigned(out, (address >>> 24) & 0xFF);
			out.writeByte('.');
			ByteBufs.writeAsciiUnsigned(out, (address >>> 16) & 0xFF);
			out.writeByte('.');
			ByteBufs.writeAsciiUnsigned(out, (address >>> 8) & 0xFF);
			out.writeByte('.');
			ByteBufs.writeAsciiUnsigned(out, address & 0xFF);
		}

		@Override
		public int estimatedSize() {
			return 15;
		}
	}

	static final class Ipv6Writer implements ValueWriter {
		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			XorShiftRandom r = ctx.random();
			for (int group = 0; group < 8; group++) {
				if (group > 0) {
					out.writeByte(':');
				}
				int value = r.nextInt(0x10000);
				out.writeByte(HEX_LOWER[(value >>> 12) & 0xF]);
				out.writeByte(HEX_LOWER[(value >>> 8) & 0xF]);
				out.writeByte(HEX_LOWER[(value >>> 4) & 0xF]);
				out.writeByte(HEX_LOWER[value & 0xF]);
			}
		}

		@Override
		public int estimatedSize() {
			return 39;
		}
	}

	static final class MacWriter implements ValueWriter {
		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			XorShiftRandom r = ctx.random();
			for (int i = 0; i < 6; i++) {
				if (i > 0) {
					out.writeByte(':');
				}
				int value = r.nextInt(256);
				out.writeByte(HEX_LOWER[(value >>> 4) & 0xF]);
				out.writeByte(HEX_LOWER[value & 0xF]);
			}
		}

		@Override
		public int estimatedSize() {
			return 17;
		}
	}

	static final class HostWriter implements ValueWriter {

		private final byte[] prefix;
		private final int count;

		HostWriter(String prefix, int count, Charset charset) {
			this.prefix = (prefix + "-").getBytes(charset);
			this.count = Math.max(1, count);
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			out.writeBytes(prefix);
			ByteBufs.writeAsciiPadded(out, ctx.random().nextInt(count), 2);
		}

		@Override
		public int estimatedSize() {
			return prefix.length + 4;
		}
	}

	static final class IntWriter implements ValueWriter {

		private final int min;
		private final int max;
		private final int step;

		IntWriter(int min, int max, int step) {
			this.min = Math.min(min, max);
			this.max = Math.max(min, max);
			this.step = Math.max(1, step);
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			int value;
			if (step == 1) {
				value = ctx.random().nextIntInclusive(min, max);
			}
			else {
				int steps = (max - min) / step;
				value = min + ctx.random().nextIntInclusive(0, steps) * step;
			}
			ByteBufs.writeAsciiLong(out, value);
		}

		@Override
		public int estimatedSize() {
			return 11;
		}
	}

	static final class LongWriter implements ValueWriter {

		private final long min;
		private final long max;

		LongWriter(long min, long max) {
			this.min = Math.min(min, max);
			this.max = Math.max(min, max);
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			ByteBufs.writeAsciiLong(out, ctx.random().nextLongInclusive(min, max));
		}

		@Override
		public int estimatedSize() {
			return 20;
		}
	}

	static final class FloatWriter implements ValueWriter {

		private final double min;
		private final double range;
		private final int scale;

		FloatWriter(double min, double max, int scale) {
			this.min = Math.min(min, max);
			this.range = Math.abs(max - min);
			this.scale = Math.max(0, Math.min(scale, 9));
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			ByteBufs.writeAsciiDouble(out, min + ctx.random().nextDouble() * range, scale);
		}

		@Override
		public int estimatedSize() {
			return 16;
		}
	}

	static final class GaussWriter implements ValueWriter {

		private final double mean;
		private final double stddev;
		private final int scale;

		GaussWriter(double mean, double stddev, int scale) {
			this.mean = mean;
			this.stddev = stddev;
			this.scale = Math.max(0, Math.min(scale, 9));
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			double value = mean + ctx.random().nextGaussian() * stddev;
			if (value < 0) {
				value = 0;
			}
			if (scale == 0) {
				ByteBufs.writeAsciiLong(out, Math.round(value));
			}
			else {
				ByteBufs.writeAsciiDouble(out, value, scale);
			}
		}

		@Override
		public int estimatedSize() {
			return 12;
		}
	}

	static final class SeqWriter implements ValueWriter {

		private final long offset;

		SeqWriter(long offset) {
			this.offset = offset;
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			ByteBufs.writeAsciiLong(out, offset + ctx.nextSequence());
		}

		@Override
		public int estimatedSize() {
			return 12;
		}
	}

	static final class BoolWriter implements ValueWriter {

		private static final byte[] TRUE = "true".getBytes(StandardCharsets.US_ASCII);
		private static final byte[] FALSE = "false".getBytes(StandardCharsets.US_ASCII);

		private final int truePercent;

		BoolWriter(int truePercent) {
			this.truePercent = Math.max(0, Math.min(100, truePercent));
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			out.writeBytes(ctx.random().nextInt(100) < truePercent ? TRUE : FALSE);
		}

		@Override
		public int estimatedSize() {
			return 5;
		}
	}

	static final class StringWriter implements ValueWriter {

		private static final byte[] ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".getBytes(StandardCharsets.US_ASCII);
		private static final byte[] ALPHA = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.US_ASCII);
		private static final byte[] LOWER = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
		private static final byte[] UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.US_ASCII);
		private static final byte[] DIGIT = "0123456789".getBytes(StandardCharsets.US_ASCII);

		private final int min;
		private final int max;
		private final byte[] alphabet;

		StringWriter(int min, int max, String charset) {
			this.min = Math.max(0, Math.min(min, max));
			this.max = Math.max(min, max);
			this.alphabet = switch (charset == null ? "alnum" : charset.trim().toLowerCase(Locale.ROOT)) {
				case "alpha" -> ALPHA;
				case "lower" -> LOWER;
				case "upper" -> UPPER;
				case "digit", "num" -> DIGIT;
				case "hex" -> HEX_LOWER;
				case "alnum" -> ALNUM;
				default -> throw new TemplateSyntaxException(
						"지원하지 않는 charset 입니다: " + charset + " (alnum|alpha|lower|upper|digit|hex)", -1);
			};
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			XorShiftRandom r = ctx.random();
			int length = (min == max) ? min : r.nextIntInclusive(min, max);
			for (int i = 0; i < length; i++) {
				out.writeByte(alphabet[r.nextInt(alphabet.length)]);
			}
		}

		@Override
		public int estimatedSize() {
			return max;
		}
	}

	static final class HexWriter implements ValueWriter {

		private final int length;

		HexWriter(int length) {
			this.length = Math.max(1, length);
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			XorShiftRandom r = ctx.random();
			for (int i = 0; i < length; i++) {
				out.writeByte(HEX_LOWER[r.nextInt(16)]);
			}
		}

		@Override
		public int estimatedSize() {
			return length;
		}
	}

	static final class UuidWriter implements ValueWriter {
		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			XorShiftRandom r = ctx.random();
			long msb = (r.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L; // version 4
			long lsb = (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variant
			writeHex(out, msb >>> 32, 8);
			out.writeByte('-');
			writeHex(out, (msb >>> 16) & 0xFFFF, 4);
			out.writeByte('-');
			writeHex(out, msb & 0xFFFF, 4);
			out.writeByte('-');
			writeHex(out, lsb >>> 48, 4);
			out.writeByte('-');
			writeHex(out, lsb & 0xFFFFFFFFFFFFL, 12);
		}

		private void writeHex(ByteBuf out, long value, int digits) {
			for (int shift = (digits - 1) * 4; shift >= 0; shift -= 4) {
				out.writeByte(HEX_LOWER[(int) ((value >>> shift) & 0xF)]);
			}
		}

		@Override
		public int estimatedSize() {
			return 36;
		}
	}

	/** 목록 선택. {@code 값:가중치} 표기를 지원한다. */
	static final class ChoiceWriter implements ValueWriter {

		private final byte[][] values;
		private final int[] cumulative;
		private final int total;
		private final int maxLength;

		ChoiceWriter(List<String> raw, Charset charset) {
			if (raw.isEmpty()) {
				throw new TemplateSyntaxException("choice 함수는 최소 1개의 값이 필요합니다", -1);
			}
			this.values = new byte[raw.size()][];
			this.cumulative = new int[raw.size()];
			boolean weighted = false;
			int running = 0;
			int longest = 0;
			for (int i = 0; i < raw.size(); i++) {
				String item = raw.get(i);
				int weight = 1;
				int colon = item.lastIndexOf(':');
				if (colon > 0 && colon < item.length() - 1) {
					String tail = item.substring(colon + 1).trim();
					if (isPositiveInteger(tail)) {
						weight = Integer.parseInt(tail);
						item = item.substring(0, colon);
						weighted = true;
					}
				}
				byte[] encoded = item.getBytes(charset);
				values[i] = encoded;
				longest = Math.max(longest, encoded.length);
				running += weight;
				cumulative[i] = running;
			}
			this.total = running;
			this.maxLength = longest;
			if (!weighted) {
				// 균등 선택이면 누적 배열 조회 없이 인덱스만 뽑는다.
				for (int i = 0; i < cumulative.length; i++) {
					cumulative[i] = i + 1;
				}
			}
		}

		private static boolean isPositiveInteger(String text) {
			if (text.isEmpty()) {
				return false;
			}
			for (int i = 0; i < text.length(); i++) {
				if (text.charAt(i) < '0' || text.charAt(i) > '9') {
					return false;
				}
			}
			return true;
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			if (values.length == 1) {
				out.writeBytes(values[0]);
				return;
			}
			int pick = ctx.random().nextInt(total);
			int index = 0;
			while (pick >= cumulative[index]) {
				index++;
			}
			out.writeBytes(values[index]);
		}

		@Override
		public int estimatedSize() {
			return maxLength;
		}
	}

	static final class SentenceWriter implements ValueWriter {

		private final byte[][] words;
		private final int wordCount;

		SentenceWriter(int wordCount, Charset charset) {
			String[] source = Dictionaries.get("words");
			this.words = new byte[source.length][];
			for (int i = 0; i < source.length; i++) {
				words[i] = source[i].getBytes(charset);
			}
			this.wordCount = Math.max(1, wordCount);
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			for (int i = 0; i < wordCount; i++) {
				if (i > 0) {
					out.writeByte(' ');
				}
				out.writeBytes(words[ctx.random().nextInt(words.length)]);
			}
		}

		@Override
		public int estimatedSize() {
			return wordCount * 10;
		}
	}

	/**
	 * 시각 기록기. 같은 밀리초 안에서는 포맷 결과를 재사용한다.
	 * <p>10만 EPS 구간에서 {@code DateTimeFormatter} 호출은 단일 최대 병목이므로
	 * 이 캐시가 성능상 핵심이다. 워커마다 인스턴스가 분리되어 동기화가 필요 없다.
	 */
	static final class TimestampWriter implements ValueWriter {

		private final DateTimeFormatter formatter;
		private final ZoneId zone;
		private final Charset charset;
		private final int estimate;

		private long cachedMillis = Long.MIN_VALUE;
		private byte[] cached = new byte[0];

		TimestampWriter(String pattern, String locale, String zoneId, Charset charset) {
			DateTimeFormatter base;
			if (pattern == null || pattern.isBlank()) {
				base = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
				this.estimate = 33;
			}
			else {
				try {
					base = DateTimeFormatter.ofPattern(pattern);
				}
				catch (IllegalArgumentException e) {
					throw new TemplateSyntaxException("잘못된 시각 패턴입니다: " + pattern, -1);
				}
				this.estimate = pattern.length() + 8;
			}
			if (locale != null && !locale.isBlank()) {
				base = base.withLocale(Locale.forLanguageTag(locale.trim()));
			}
			this.formatter = base;
			this.charset = charset;
			try {
				this.zone = (zoneId == null || zoneId.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(zoneId.trim());
			}
			catch (DateTimeException e) {
				throw new TemplateSyntaxException("잘못된 타임존입니다: " + zoneId, -1);
			}
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			long now = ctx.now();
			if (now != cachedMillis) {
				cachedMillis = now;
				cached = formatter.format(Instant.ofEpochMilli(now).atZone(zone)).getBytes(charset);
			}
			out.writeBytes(cached);
		}

		@Override
		public int estimatedSize() {
			return estimate;
		}
	}

	static final class EpochWriter implements ValueWriter {

		private final boolean millis;

		EpochWriter(boolean millis) {
			this.millis = millis;
		}

		@Override
		public void write(ByteBuf out, RenderContext ctx) {
			long now = ctx.now();
			ByteBufs.writeAsciiLong(out, millis ? now : now / 1000);
		}

		@Override
		public int estimatedSize() {
			return 14;
		}
	}
}
