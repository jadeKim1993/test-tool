package com.tms.tester.catalog;

import java.time.Instant;
import java.util.List;

import com.tms.tester.common.LogFormat;
import com.tms.tester.common.WireEncoding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {

	private CatalogDtos() {
	}

	public record CategoryRequest(
			@NotBlank(message = "카테고리 이름을 입력하세요")
			@Size(max = 100) String name,
			@Size(max = 500) String description,
			boolean shared,
			/** 비우면 UTF-8. 이 카테고리의 로그를 어떤 바이트로 내보낼지 정한다(§16.14). */
			WireEncoding encoding) {
	}

	public record CategoryResponse(
			Long id,
			String name,
			String description,
			boolean shared,
			boolean owned,
			int templateCount,
			WireEncoding encoding,
			Instant updatedAt) {
	}

	public record TemplateRequest(
			@NotBlank(message = "템플릿 이름을 입력하세요")
			@Size(max = 100) String name,
			@NotBlank(message = "템플릿 내용을 입력하세요")
			@Size(max = 20000) String content,
			LogFormat format,
			@Min(1) @Max(1000) int weight,
			Boolean enabled,
			Integer sortOrder) {
	}

	public record TemplateResponse(
			Long id,
			Long categoryId,
			String name,
			String content,
			LogFormat format,
			int weight,
			boolean enabled,
			int sortOrder,
			Instant updatedAt) {
	}

	/** 저장하지 않고 렌더링 결과만 확인한다. 템플릿 편집 화면에서 타이핑 중 호출된다. */
	public record PreviewRequest(
			@NotBlank(message = "템플릿 내용을 입력하세요") String content,
			LogFormat format,
			@Min(1) @Max(50) Integer count) {
	}

	public record PreviewResponse(List<String> lines, int averageBytes, String error, Integer position) {
	}

	/** 카테고리 통째로 내보내기/가져오기 (팀 간 템플릿 공유용). */
	public record CategoryExport(
			String name,
			String description,
			WireEncoding encoding,
			List<TemplateExport> templates) {
	}

	public record TemplateExport(
			String name,
			String content,
			LogFormat format,
			int weight,
			boolean enabled,
			int sortOrder) {
	}
}
