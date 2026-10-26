package com.tms.tester.catalog;

import java.time.Instant;
import java.util.List;

import com.tms.tester.common.LogFormat;
import com.tms.tester.common.WireEncoding;
import com.tms.tester.generator.CompiledTemplate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {

	private CatalogDtos() {
	}

	/** 끌어서 바꾼 순서. 화면에 보이는 순서대로 id 를 담는다. */
	public record OrderRequest(@NotNull List<Long> ids) {
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
			/** 켜져 있는 로그 종류 수. 전송 화면은 전체가 아니라 이 수를 보여 준다. */
			int enabledTemplateCount,
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

	/**
	 * 미리보기 결과.
	 * <p>{@code parts} 는 {@code lines} 와 같은 줄을 조각으로 나눈 것으로, 각 조각의 {@code source} 가
	 * {@code placeholders} 인덱스(0 이상)·고정 문자열(-1)·포맷 헤더(-2) 중 무엇에서 나왔는지 알려 준다.
	 * 편집 화면이 렌더 결과와 템플릿 원문을 서로 이어 보여 주는 데 쓴다.
	 */
	public record PreviewResponse(List<String> lines, int averageBytes, String error, Integer position,
			List<CompiledTemplate.Placeholder> placeholders, List<List<CompiledTemplate.Part>> parts) {

		static PreviewResponse failed(String error, int position) {
			return new PreviewResponse(List.of(), 0, error, position < 0 ? null : position, List.of(), List.of());
		}
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
