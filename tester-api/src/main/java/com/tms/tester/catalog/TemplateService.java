package com.tms.tester.catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tms.tester.common.LogFormat;
import com.tms.tester.common.TesterException;
import com.tms.tester.domain.entity.Category;
import com.tms.tester.domain.entity.LogTemplate;
import com.tms.tester.domain.repository.LogTemplateRepository;
import com.tms.tester.generator.CompiledTemplate;
import com.tms.tester.generator.RenderContext;
import com.tms.tester.generator.TemplateCompiler;
import com.tms.tester.generator.TemplateSyntaxException;
import com.tms.tester.job.RunManager;

@Service
public class TemplateService {

	private final LogTemplateRepository templateRepository;
	private final CategoryService categoryService;
	private final TemplateCompiler compiler;
	private final RunManager runManager;

	public TemplateService(LogTemplateRepository templateRepository, CategoryService categoryService,
			TemplateCompiler compiler, RunManager runManager) {
		this.templateRepository = templateRepository;
		this.categoryService = categoryService;
		this.compiler = compiler;
		this.runManager = runManager;
	}

	@Transactional(readOnly = true)
	public List<CatalogDtos.TemplateResponse> list(Long userId, Long categoryId) {
		categoryService.requireVisible(userId, categoryId);
		List<CatalogDtos.TemplateResponse> result = new ArrayList<>();
		for (LogTemplate template : templateRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)) {
			result.add(toResponse(template));
		}
		return result;
	}

	@Transactional
	public CatalogDtos.TemplateResponse create(Long userId, Long categoryId, CatalogDtos.TemplateRequest request) {
		Category category = categoryService.requireOwned(userId, categoryId);
		validateSyntax(request.content(), request.format());

		LogTemplate template = new LogTemplate(request.name().trim(), request.content());
		apply(template, request);
		if (request.sortOrder() == null) {
			template.setSortOrder(category.getTemplates().size());
		}
		category.addTemplate(template);
		LogTemplate saved = templateRepository.save(template);
		runManager.refreshTemplates(categoryId);
		return toResponse(saved);
	}

	@Transactional
	public CatalogDtos.TemplateResponse update(Long userId, Long templateId, CatalogDtos.TemplateRequest request) {
		LogTemplate template = requireOwned(userId, templateId);
		validateSyntax(request.content(), request.format());
		template.setName(request.name().trim());
		template.setContent(request.content());
		apply(template, request);
		// 실행 중인 전송 작업도 다음 틱에 새 내용으로 반영된다(무중단).
		runManager.refreshTemplates(template.getCategory().getId());
		return toResponse(template);
	}

	@Transactional
	public void delete(Long userId, Long templateId) {
		LogTemplate template = requireOwned(userId, templateId);
		Long categoryId = template.getCategory().getId();
		template.getCategory().removeTemplate(template);
		templateRepository.delete(template);
		runManager.refreshTemplates(categoryId);
	}

	/**
	 * 카테고리 안의 로그 종류를 한 번에 켜고 끈다.
	 * <p>종류가 많은 카테고리에서 하나씩 토글하는 건 번거롭다.
	 * 실행 중인 전송에도 즉시 반영된다.
	 *
	 * @return 상태가 바뀐 템플릿 수
	 */
	@Transactional
	public int setAllEnabled(Long userId, Long categoryId, boolean enabled) {
		categoryService.requireOwned(userId, categoryId);
		List<LogTemplate> templates = templateRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId);
		if (!enabled && templates.stream().noneMatch(LogTemplate::isEnabled)) {
			return 0;
		}
		int changed = 0;
		for (LogTemplate template : templates) {
			if (template.isEnabled() != enabled) {
				template.setEnabled(enabled);
				changed++;
			}
		}
		if (changed > 0) {
			runManager.refreshTemplates(categoryId);
		}
		return changed;
	}

	/**
	 * 카테고리 안 로그 종류의 순서를 저장한다(끌어서 바꾼 결과). 순차/라운드로빈은 이 순서대로 보내므로
	 * 실행 중인 전송에도 바로 반영한다. 목록에 빠진 종류는 기존 순서대로 뒤에 둔다.
	 */
	@Transactional
	public void reorder(Long userId, Long categoryId, List<Long> ids) {
		categoryService.requireOwned(userId, categoryId);
		List<LogTemplate> templates = templateRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId);
		int index = 0;
		for (Long id : ids) {
			LogTemplate template = templates.stream().filter(t -> t.getId().equals(id)).findFirst()
					.orElseThrow(() -> TesterException.notFound("로그 종류", id));
			template.setSortOrder(index++);
		}
		for (LogTemplate template : templates) {
			if (!ids.contains(template.getId())) {
				template.setSortOrder(index++);
			}
		}
		runManager.refreshTemplates(categoryId);
	}

	/** 저장하지 않고 렌더링 결과만 돌려준다. 편집 화면의 실시간 미리보기가 사용한다. */
	public CatalogDtos.PreviewResponse preview(CatalogDtos.PreviewRequest request) {
		int count = request.count() == null ? 5 : request.count();
		LogFormat format = request.format() == null ? LogFormat.RAW : request.format();
		try {
			CompiledTemplate template = compiler.compile(0L, "preview", format,
					StandardCharsets.UTF_8, request.content());
			RenderContext ctx = RenderContext.standalone();
			List<String> lines = new ArrayList<>(count);
			List<List<CompiledTemplate.Part>> parts = new ArrayList<>(count);
			int totalBytes = 0;
			for (int i = 0; i < count; i++) {
				ctx.tick(System.currentTimeMillis());
				List<CompiledTemplate.Part> lineParts = template.renderParts(ctx);
				StringBuilder line = new StringBuilder();
				lineParts.forEach(part -> line.append(part.text()));
				totalBytes += line.toString().getBytes(StandardCharsets.UTF_8).length;
				lines.add(line.toString());
				parts.add(lineParts);
			}
			return new CatalogDtos.PreviewResponse(lines, totalBytes / Math.max(1, count), null, null,
					template.placeholders(), parts);
		}
		catch (TemplateSyntaxException e) {
			return CatalogDtos.PreviewResponse.failed(e.getMessage(), e.getPosition());
		}
	}

	private void validateSyntax(String content, LogFormat format) {
		compiler.compile(0L, "validate", format == null ? LogFormat.RAW : format,
				StandardCharsets.UTF_8, content);
	}

	private void apply(LogTemplate template, CatalogDtos.TemplateRequest request) {
		template.setFormat(request.format() == null ? LogFormat.RAW : request.format());
		template.setWeight(request.weight() <= 0 ? 1 : request.weight());
		if (request.enabled() != null) {
			template.setEnabled(request.enabled());
		}
		if (request.sortOrder() != null) {
			template.setSortOrder(request.sortOrder());
		}
	}

	@Transactional(readOnly = true)
	public LogTemplate requireOwned(Long userId, Long templateId) {
		LogTemplate template = templateRepository.findById(templateId)
				.orElseThrow(() -> TesterException.notFound("템플릿", templateId));
		if (template.getCategory() == null) {
			throw TesterException.notFound("템플릿", templateId);
		}
		categoryService.requireOwned(userId, template.getCategory().getId());
		return template;
	}

	private CatalogDtos.TemplateResponse toResponse(LogTemplate template) {
		return new CatalogDtos.TemplateResponse(
				template.getId(),
				template.getCategory() == null ? null : template.getCategory().getId(),
				template.getName(),
				template.getContent(),
				template.getFormat(),
				template.getWeight(),
				template.isEnabled(),
				template.getSortOrder(),
				template.getUpdatedAt());
	}
}
