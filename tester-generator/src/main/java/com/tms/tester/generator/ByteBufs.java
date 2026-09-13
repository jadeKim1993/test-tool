package com.tms.tester.generator;

import io.netty.buffer.ByteBuf;

/**
 * 핫패스 전용 버퍼 기록 유틸.
 * <p>{@code String.valueOf()} / {@code String.format()} 이 만들어내는 임시 객체를 피하기 위해
 * 숫자를 직접 ASCII 바이트로 기록한다.
 */
public final class ByteBufs {

	private static final byte[] MIN_LONG = "-9223372036854775808".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
	private static final long[] POW10 = {
			1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L,
			10_000_000L, 100_000_000L, 1_000_000_000L
	};

	private ByteBufs() {
	}

	/** 부호 있는 정수를 ASCII 로 기록한다. */
	public static void writeAsciiLong(ByteBuf out, long value) {
		if (value == Long.MIN_VALUE) {
			out.writeBytes(MIN_LONG);
			return;
		}
		if (value < 0) {
			out.writeByte('-');
			value = -value;
		}
		writeAsciiUnsigned(out, value);
	}

	/** 부호 없는 정수를 ASCII 로 기록한다. */
	public static void writeAsciiUnsigned(ByteBuf out, long value) {
		if (value < 10) {
			out.writeByte((int) ('0' + value));
			return;
		}
		// 역순으로 만든 뒤 뒤집어 기록한다. 최대 19자리.
		byte[] tmp = new byte[20];
		int pos = 20;
		while (value > 0) {
			tmp[--pos] = (byte) ('0' + (value % 10));
			value /= 10;
		}
		out.writeBytes(tmp, pos, 20 - pos);
	}

	/** 정수를 지정한 자리수만큼 0 으로 좌측 패딩하여 기록한다. */
	public static void writeAsciiPadded(ByteBuf out, long value, int width) {
		long div = POW10[Math.min(width - 1, POW10.length - 1)];
		while (div > 0) {
			out.writeByte((int) ('0' + ((value / div) % 10)));
			div /= 10;
		}
	}

	/** 실수를 소수점 {@code scale} 자리까지 반올림하여 기록한다. */
	public static void writeAsciiDouble(ByteBuf out, double value, int scale) {
		if (scale <= 0) {
			writeAsciiLong(out, Math.round(value));
			return;
		}
		if (scale >= POW10.length) {
			scale = POW10.length - 1;
		}
		boolean negative = value < 0;
		if (negative) {
			value = -value;
			out.writeByte('-');
		}
		long factor = POW10[scale];
		long scaled = Math.round(value * factor);
		writeAsciiUnsigned(out, scaled / factor);
		out.writeByte('.');
		writeAsciiPadded(out, scaled % factor, scale);
	}
}
