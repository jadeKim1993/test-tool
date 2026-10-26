package com.tms.tester.catalog;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tms.tester.security.AuthUser;

import jakarta.validation.Valid;

/** 카테고리 및 로그 템플릿 관리 API. */
@RestController
@RequestMapping("/api")
public class CatalogController {

	private final CategoryService categoryService;
	private final TemplateService templateService;

	public CatalogController(CategoryService categoryService, TemplateService templateService) {
		this.categoryService = categoryService;
		this.templateService = templateService;
	}

	// ---------------- 카테고리 ----------------

	@GetMapping("/categories")
	public List<CatalogDtos.CategoryResponse> listCategories(@AuthenticationPrincipal AuthUser user) {
		return categoryService.list(user.id());
	}

	@PostMapping("/categories")
	public ResponseEntity<CatalogDtos.CategoryResponse> createCategory(
			@AuthenticationPrincipal AuthUser user,
			@Valid @RequestBody CatalogDtos.CategoryRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(user.id(), request));
	}

	@PutMapping("/categories/{id}")
	public CatalogDtos.CategoryResponse updateCategory(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@Valid @RequestBody CatalogDtos.CategoryRequest request) {
		return categoryService.update(user.id(), id, request);
	}

	@DeleteMapping("/categories/{id}")
	public ResponseEntity<Void> deleteCategory(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		categoryService.delete(user.id(), id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/categories/{id}/duplicate")
	public CatalogDtos.CategoryResponse duplicateCategory(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@RequestParam(required = false) String name) {
		return categoryService.duplicate(user.id(), id, name);
	}

	@GetMapping("/categories/{id}/export")
	public CatalogDtos.CategoryExport exportCategory(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		return categoryService.export(user.id(), id);
	}

	@PostMapping("/categories/import")
	public ResponseEntity<CatalogDtos.CategoryResponse> importCategory(
			@AuthenticationPrincipal AuthUser user,
			@RequestBody CatalogDtos.CategoryExport payload) {
		return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.importCategory(user.id(), payload));
	}

	// ---------------- 템플릿 ----------------

	@GetMapping("/categories/{id}/templates")
	public List<CatalogDtos.TemplateResponse> listTemplates(
			@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		return templateService.list(user.id(), id);
	}

	@PostMapping("/categories/{id}/templates")
	public ResponseEntity<CatalogDtos.TemplateResponse> createTemplate(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@Valid @RequestBody CatalogDtos.TemplateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(user.id(), id, request));
	}

	@PutMapping("/templates/{id}")
	public CatalogDtos.TemplateResponse updateTemplate(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@Valid @RequestBody CatalogDtos.TemplateRequest request) {
		return templateService.update(user.id(), id, request);
	}

	@DeleteMapping("/templates/{id}")
	public ResponseEntity<Void> deleteTemplate(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		templateService.delete(user.id(), id);
		return ResponseEntity.noContent().build();
	}

	/** 내 카테고리 순서 저장(템플릿 화면에서 끌어서 바꾼 결과). */
	@PutMapping("/categories/order")
	public ResponseEntity<Void> reorderCategories(
			@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CatalogDtos.OrderRequest request) {
		categoryService.reorder(user.id(), request.ids());
		return ResponseEntity.noContent().build();
	}

	/** 카테고리 안 로그 종류 순서 저장. */
	@PutMapping("/categories/{id}/templates/order")
	public ResponseEntity<Void> reorderTemplates(
			@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
			@Valid @RequestBody CatalogDtos.OrderRequest request) {
		templateService.reorder(user.id(), id, request.ids());
		return ResponseEntity.noContent().build();
	}

	/** 카테고리 안의 로그 종류를 한 번에 켜거나 끈다. */
	@PatchMapping("/categories/{id}/templates/enabled")
	public Map<String, Object> setAllEnabled(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@RequestParam boolean enabled) {
		int changed = templateService.setAllEnabled(user.id(), id, enabled);
		return Map.of("changed", changed, "enabled", enabled);
	}

	/** 저장 없이 렌더링 결과만 확인한다. */
	@PostMapping("/templates/preview")
	public CatalogDtos.PreviewResponse preview(@Valid @RequestBody CatalogDtos.PreviewRequest request) {
		return templateService.preview(request);
	}
}
