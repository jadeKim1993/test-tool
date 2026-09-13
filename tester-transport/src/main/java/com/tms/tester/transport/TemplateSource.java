package com.tms.tester.transport;

import com.tms.tester.common.LogFormat;

/**
 * 워커에 전달되는 템플릿 원본. 워커는 이것을 각자 컴파일해 사용한다.
 *
 * @param id      템플릿 식별자
 * @param name    표시명
 * @param format  헤더 포맷
 * @param content 템플릿 본문
 * @param weight  선택 가중치(1 이상)
 */
public record TemplateSource(
		long id,
		String name,
		LogFormat format,
		String content,
		int weight
) {

	public TemplateSource {
		if (weight < 1) {
			weight = 1;
		}
		if (format == null) {
			format = LogFormat.RAW;
		}
	}

	public static TemplateSource of(long id, String name, String content) {
		return new TemplateSource(id, name, LogFormat.RAW, content, 1);
	}
}
