package com.tms.tester.generator;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * 플레이스홀더에 전달된 인자.
 * <p>위치 인자({@code int(1,10)})와 이름 인자({@code int(1,10,step=2)})를 함께 지원한다.
 *
 * @param charset 텍스트 값을 바이트로 바꿀 때 쓸 문자셋. 한글이 들어간 {@code choice}/{@code dict}
 *                값이 고정 문자열과 다른 인코딩으로 나가면 안 되므로 writer 까지 전달한다.
 */
public record FunctionArgs(String function, List<String> positional, Map<String, String> named, Charset charset) {

	public int size() {
		return positional.size();
	}

	public String str(int index, String defaultValue) {
		return index < positional.size() ? positional.get(index) : defaultValue;
	}

	public String require(int index, String what) {
		if (index >= positional.size()) {
			throw new TemplateSyntaxException(function + " 함수는 " + what + " 인자가 필요합니다", -1);
		}
		return positional.get(index);
	}

	public int intAt(int index, int defaultValue) {
		if (index >= positional.size()) {
			return defaultValue;
		}
		return parseInt(positional.get(index));
	}

	public long longAt(int index, long defaultValue) {
		if (index >= positional.size()) {
			return defaultValue;
		}
		try {
			return Long.parseLong(positional.get(index).trim());
		}
		catch (NumberFormatException e) {
			throw new TemplateSyntaxException(function + " 함수의 인자가 숫자가 아닙니다: " + positional.get(index), -1);
		}
	}

	public double doubleAt(int index, double defaultValue) {
		if (index >= positional.size()) {
			return defaultValue;
		}
		try {
			return Double.parseDouble(positional.get(index).trim());
		}
		catch (NumberFormatException e) {
			throw new TemplateSyntaxException(function + " 함수의 인자가 숫자가 아닙니다: " + positional.get(index), -1);
		}
	}

	public int named(String key, int defaultValue) {
		String value = named.get(key);
		return value == null ? defaultValue : parseInt(value);
	}

	public String named(String key, String defaultValue) {
		return named.getOrDefault(key, defaultValue);
	}

	private int parseInt(String raw) {
		try {
			return Integer.parseInt(raw.trim());
		}
		catch (NumberFormatException e) {
			throw new TemplateSyntaxException(function + " 함수의 인자가 숫자가 아닙니다: " + raw, -1);
		}
	}
}
