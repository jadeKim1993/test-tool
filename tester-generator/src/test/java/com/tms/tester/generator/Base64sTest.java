package com.tms.tester.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 직접 구현한 Base64 인코더를 표준 구현과 대조한다. */
class Base64sTest {

	private String encode(String input) {
		ByteBuf src = Unpooled.copiedBuffer(input, StandardCharsets.UTF_8);
		ByteBuf dst = Unpooled.buffer();
		try {
			Base64s.encodeInto(src, 0, src.readableBytes(), dst);
			return dst.toString(StandardCharsets.US_ASCII);
		}
		finally {
			src.release();
			dst.release();
		}
	}

	private String expected(String input) {
		return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void 표준_구현과_결과가_같다() {
		// 길이 % 3 의 세 경우(패딩 없음 / = / ==)를 모두 지난다
		for (String sample : new String[]{
				"a", "ab", "abc", "abcd", "abcde", "abcdef",
				"가나다", "한글과 English 1234",
				"203.0.113.47 - - [11/Sep/2026:12:00:00 +0900] \"GET /api/v1/users/42 HTTP/1.1\" 200 1234"}) {
			assertThat(encode(sample)).as("입력=%s", sample).isEqualTo(expected(sample));
		}
	}

	@Test
	void 빈_입력은_빈_결과다() {
		assertThat(encode("")).isEmpty();
	}

	@Test
	void 모든_바이트값을_정확히_인코딩한다() {
		byte[] all = new byte[256];
		for (int i = 0; i < 256; i++) {
			all[i] = (byte) i;
		}
		ByteBuf src = Unpooled.copiedBuffer(all);
		ByteBuf dst = Unpooled.buffer();
		try {
			Base64s.encodeInto(src, 0, all.length, dst);
			assertThat(dst.toString(StandardCharsets.US_ASCII))
					.isEqualTo(Base64.getEncoder().encodeToString(all));
		}
		finally {
			src.release();
			dst.release();
		}
	}

	@Test
	void 길이_계산이_맞다() {
		for (int length = 0; length < 100; length++) {
			byte[] data = new byte[length];
			assertThat(Base64s.encodedLength(length))
					.as("length=%d", length)
					.isEqualTo(Base64.getEncoder().encodeToString(data).length());
		}
	}

	@Test
	void 대상_버퍼에_덧붙인다() {
		ByteBuf src = Unpooled.copiedBuffer("abc", StandardCharsets.UTF_8);
		ByteBuf dst = Unpooled.buffer();
		try {
			dst.writeBytes("PRE:".getBytes(StandardCharsets.US_ASCII));
			Base64s.encodeInto(src, 0, src.readableBytes(), dst);
			assertThat(dst.toString(StandardCharsets.US_ASCII)).isEqualTo("PRE:" + expected("abc"));
		}
		finally {
			src.release();
			dst.release();
		}
	}
}
