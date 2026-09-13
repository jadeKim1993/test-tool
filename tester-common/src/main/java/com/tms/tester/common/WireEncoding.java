package com.tms.tester.common;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 전송 시 로그를 바이트로 바꾸는 방식.
 * <p>수신 서버가 무엇을 기대하는지에 따라 정해지므로 {@link Framing} 과 함께
 * 전송 대상에 붙는다(템플릿이 아니라).
 */
public enum WireEncoding {

	/** 기본값. 대부분의 수집기가 이것을 기대한다. */
	UTF_8("UTF-8", StandardCharsets.UTF_8, "UTF-8 (기본)"),

	/**
	 * 한글 Windows 코드페이지.
	 * <p><b>주의</b>: {@code Charset.forName("CP949")} 는 JDK 에서 {@code x-IBM949} 로
	 * 해석되는데, 이는 IBM 변형이라 일부 문자 매핑이 다르다. 국내에서 CP949 라고 하면
	 * 사실상 Windows 코드페이지(MS949)를 뜻하므로 {@code x-windows-949} 로 명시해 고정한다.
	 */
	CP949("CP949", Charset.forName("x-windows-949"), "CP949 (MS949, 한글 Windows)"),

	/**
	 * UTF-8 로 만든 뒤 전체를 Base64 로 감싼다.
	 * <p>바이너리 안전한 전송을 요구하는 수집기용. 출력이 ASCII 라 개행 프레이밍과 잘 맞는다.
	 */
	BASE64("Base64", StandardCharsets.UTF_8, "Base64 (UTF-8 → Base64)");

	private final String displayName;
	private final Charset charset;
	private final String label;

	WireEncoding(String displayName, Charset charset, String label) {
		this.displayName = displayName;
		this.charset = charset;
		this.label = label;
	}

	/** 로그 본문 텍스트를 바이트로 바꿀 때 쓰는 문자셋. */
	public Charset charset() {
		return charset;
	}

	/** 렌더링이 끝난 바이트 전체를 Base64 로 다시 감싸야 하는가. */
	public boolean isBase64() {
		return this == BASE64;
	}

	public String displayName() {
		return displayName;
	}

	public String label() {
		return label;
	}
}
