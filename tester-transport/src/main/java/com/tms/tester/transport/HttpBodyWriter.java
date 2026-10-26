package com.tms.tester.transport;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.tms.tester.common.HttpFormat;

import io.netty.buffer.ByteBuf;

/**
 * HTTP 요청 본문 조립기. 로그 한 건씩 받아 형식({@link HttpFormat})에 맞게 이어 붙인다.
 * <p>JSON 계열은 로그 바이트를 그대로 JSON 문자열로 이스케이프한다(따옴표·역슬래시·제어문자만 바꾸고
 * 0x80 이상은 그대로 둔다). 그래서 여러 줄 로그도 한 건으로 가고, UTF-8 로그는 그대로 유효한 JSON 이 된다.
 * CP949 로그를 JSON 형식으로 보내면 JSON 이 요구하는 UTF-8 이 아니게 된다 — 화면이 이 조합을 경고한다.
 * <p>스레드 안전하지 않다. 커넥션마다 하나씩 쓰고, 커넥션의 잠금 안에서만 호출한다.
 */
final class HttpBodyWriter {

	private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] LOKI_PREFIX = "{\"streams\":[{\"stream\":{\"job\":\"tester\"},\"values\":["
			.getBytes(StandardCharsets.US_ASCII);
	private static final byte[] LOKI_SUFFIX = "]}]}".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] MESSAGE_OPEN = "{\"message\":".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] EVENT_OPEN = "{\"event\":".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] BULK_ACTION = "{\"index\":{}}\n{\"@timestamp\":\"".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] BULK_MESSAGE = "\",\"message\":".getBytes(StandardCharsets.US_ASCII);

	private final HttpFormat format;

	/** ES @timestamp 는 건마다 포맷하면 비싸다. 같은 밀리초 안에서는 재사용한다. */
	private long isoMillis = -1;
	private byte[] isoBytes;

	HttpBodyWriter(HttpFormat format) {
		this.format = format;
	}

	HttpFormat format() {
		return format;
	}

	/**
	 * 로그 한 건을 본문에 덧붙인다.
	 *
	 * @param first 이 요청의 첫 건인가(배열 여는 괄호·구분자 처리)
	 */
	void append(ByteBuf body, ByteBuf record, boolean first, long nowMillis) {
		switch (format) {
			case LINES -> {
				body.writeBytes(record, record.readerIndex(), record.readableBytes());
				body.writeByte('\n');
			}
			case NDJSON -> {
				body.writeBytes(MESSAGE_OPEN);
				writeJsonString(record, body);
				body.writeByte('}').writeByte('\n');
			}
			case JSON_ARRAY -> {
				body.writeByte(first ? '[' : ',');
				body.writeBytes(MESSAGE_OPEN);
				writeJsonString(record, body);
				body.writeByte('}');
			}
			case SPLUNK_HEC -> {
				// HEC 는 이벤트 객체를 이어 붙인 본문을 받는다(배열이 아니다).
				body.writeBytes(EVENT_OPEN);
				writeJsonString(record, body);
				body.writeByte('}').writeByte('\n');
			}
			case ES_BULK -> {
				body.writeBytes(BULK_ACTION);
				body.writeBytes(iso(nowMillis));
				body.writeBytes(BULK_MESSAGE);
				writeJsonString(record, body);
				body.writeByte('}').writeByte('\n');
			}
			case LOKI -> {
				if (first) {
					body.writeBytes(LOKI_PREFIX);
				}
				else {
					body.writeByte(',');
				}
				// 값은 [ "<나노초 문자열>", "<로그>" ]
				body.writeByte('[').writeByte('"');
				body.writeCharSequence(Long.toString(nowMillis * 1_000_000L), StandardCharsets.US_ASCII);
				body.writeByte('"').writeByte(',');
				writeJsonString(record, body);
				body.writeByte(']');
			}
		}
	}

	/** 요청을 보내기 직전에 닫는 괄호 등을 붙인다. */
	void finish(ByteBuf body) {
		switch (format) {
			case JSON_ARRAY -> body.writeByte(']');
			case LOKI -> body.writeBytes(LOKI_SUFFIX);
			default -> {
				// 줄 단위 형식은 닫을 것이 없다.
			}
		}
	}

	private byte[] iso(long millis) {
		if (millis != isoMillis) {
			isoMillis = millis;
			isoBytes = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
					.getBytes(StandardCharsets.US_ASCII);
		}
		return isoBytes;
	}

	/** src 의 읽을 수 있는 바이트를 따옴표로 감싼 JSON 문자열로 dst 에 쓴다. src 의 읽기 위치는 바꾸지 않는다. */
	static void writeJsonString(ByteBuf src, ByteBuf dst) {
		dst.writeByte('"');
		int end = src.writerIndex();
		for (int i = src.readerIndex(); i < end; i++) {
			byte b = src.getByte(i);
			switch (b) {
				case '"' -> dst.writeByte('\\').writeByte('"');
				case '\\' -> dst.writeByte('\\').writeByte('\\');
				case '\n' -> dst.writeByte('\\').writeByte('n');
				case '\r' -> dst.writeByte('\\').writeByte('r');
				case '\t' -> dst.writeByte('\\').writeByte('t');
				default -> {
					if ((b & 0xff) < 0x20) {
						dst.writeByte('\\').writeByte('u').writeByte('0').writeByte('0')
								.writeByte(HEX[(b >> 4) & 0x0f]).writeByte(HEX[b & 0x0f]);
					}
					else {
						dst.writeByte(b);
					}
				}
			}
		}
		dst.writeByte('"');
	}
}
