package com.tms.tester.generator;

import io.netty.buffer.ByteBuf;

/**
 * 핫패스용 Base64 인코더.
 * <p>{@code java.util.Base64} 와 Netty 의 Base64 codec 은 모두 결과 버퍼를 새로 할당한다.
 * 초당 수십만 건을 보내는 경로에서는 그 할당이 그대로 GC 압력이 되므로,
 * <b>대상 버퍼에 직접 기록</b>하는 구현을 둔다.
 */
public final class Base64s {

	private static final byte[] TABLE =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
					.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
	private static final byte PAD = '=';

	private Base64s() {
	}

	/** Base64 로 바꿨을 때의 바이트 수(패딩 포함). */
	public static int encodedLength(int sourceLength) {
		return ((sourceLength + 2) / 3) * 4;
	}

	/**
	 * {@code src} 의 {@code [index, index+length)} 구간을 Base64 로 인코딩해 {@code dst} 에 덧붙인다.
     * 줄바꿈은 넣지 않는다(개행 프레이밍과 충돌하지 않도록).
	 */
	public static void encodeInto(ByteBuf src, int index, int length, ByteBuf dst) {
		dst.ensureWritable(encodedLength(length));

		int end = index + length;
		int i = index;
		// 3바이트씩 4글자로
		for (; i + 2 < end; i += 3) {
			int chunk = ((src.getByte(i) & 0xFF) << 16)
					| ((src.getByte(i + 1) & 0xFF) << 8)
					| (src.getByte(i + 2) & 0xFF);
			dst.writeByte(TABLE[(chunk >>> 18) & 0x3F]);
			dst.writeByte(TABLE[(chunk >>> 12) & 0x3F]);
			dst.writeByte(TABLE[(chunk >>> 6) & 0x3F]);
			dst.writeByte(TABLE[chunk & 0x3F]);
		}

		int remaining = end - i;
		if (remaining == 1) {
			int chunk = (src.getByte(i) & 0xFF) << 16;
			dst.writeByte(TABLE[(chunk >>> 18) & 0x3F]);
			dst.writeByte(TABLE[(chunk >>> 12) & 0x3F]);
			dst.writeByte(PAD);
			dst.writeByte(PAD);
		}
		else if (remaining == 2) {
			int chunk = ((src.getByte(i) & 0xFF) << 16) | ((src.getByte(i + 1) & 0xFF) << 8);
			dst.writeByte(TABLE[(chunk >>> 18) & 0x3F]);
			dst.writeByte(TABLE[(chunk >>> 12) & 0x3F]);
			dst.writeByte(TABLE[(chunk >>> 6) & 0x3F]);
			dst.writeByte(PAD);
		}
	}
}
