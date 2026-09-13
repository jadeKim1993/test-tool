package com.tms.tester.catalog;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tms.tester.common.TesterException;
import com.tms.tester.domain.entity.Category;
import com.tms.tester.domain.entity.LogTemplate;
import com.tms.tester.domain.entity.User;
import com.tms.tester.domain.repository.CategoryRepository;
import com.tms.tester.domain.repository.LogTemplateRepository;
import com.tms.tester.domain.repository.SendJobRepository;
import com.tms.tester.domain.repository.UserRepository;

@Service
public class CategoryService {

	private final CategoryRepository categoryRepository;
	private final LogTemplateRepository templateRepository;
	private final SendJobRepository jobRepository;
	private final UserRepository userRepository;

	public CategoryService(CategoryRepository categoryRepository, LogTemplateRepository templateRepository,
			SendJobRepository jobRepository, UserRepository userRepository) {
		this.categoryRepository = categoryRepository;
		this.templateRepository = templateRepository;
		this.jobRepository = jobRepository;
		this.userRepository = userRepository;
	}

	@Transactional(readOnly = true)
	public List<CatalogDtos.CategoryResponse> list(Long userId) {
		List<CatalogDtos.CategoryResponse> result = new ArrayList<>();
		for (Category category : categoryRepository.findVisible(userId)) {
			result.add(toResponse(category, userId));
		}
		return result;
	}

	@Transactional
	public CatalogDtos.CategoryResponse create(Long userId, CatalogDtos.CategoryRequest request) {
		String name = request.name().trim();
		if (categoryRepository.existsByOwnerIdAndName(userId, name)) {
			throw TesterException.conflict("같은 이름의 카테고리가 이미 있습니다: " + name);
		}
		User owner = userRepository.getReferenceById(userId);
		Category category = new Category(owner, name, request.description());
		category.setShared(request.shared());
		category.setEncoding(request.encoding());
		return toResponse(categoryRepository.save(category), userId);
	}

	@Transactional
	public CatalogDtos.CategoryResponse update(Long userId, Long categoryId, CatalogDtos.CategoryRequest request) {
		Category category = requireOwned(userId, categoryId);
		String name = request.name().trim();
		if (!category.getName().equals(name) && categoryRepository.existsByOwnerIdAndName(userId, name)) {
			throw TesterException.conflict("같은 이름의 카테고리가 이미 있습니다: " + name);
		}
		category.setName(name);
		category.setDescription(request.description());
		category.setShared(request.shared());
		category.setEncoding(request.encoding());
		return toResponse(category, userId);
	}

	@Transactional
	public void delete(Long userId, Long categoryId) {
		Category category = requireOwned(userId, categoryId);
		long jobCount = jobRepository.countByCategoryId(categoryId);
		if (jobCount > 0) {
			throw TesterException.conflict(
					"이 카테고리를 사용하는 전송 작업이 " + jobCount + "개 있습니다. 먼저 작업을 삭제하세요.");
		}
		categoryRepository.delete(category);
	}

	/** 기존 카테고리를 템플릿째 복제한다. 변형본을 빠르게 만들 때 사용한다. */
	@Transactional
	public CatalogDtos.CategoryResponse duplicate(Long userId, Long categoryId, String newName) {
		Category source = requireVisible(userId, categoryId);
		String name = (newName == null || newName.isBlank()) ? source.getName() + "-copy" : newName.trim();
		if (categoryRepository.existsByOwnerIdAndName(userId, name)) {
			throw TesterException.conflict("같은 이름의 카테고리가 이미 있습니다: " + name);
		}
		User owner = userRepository.getReferenceById(userId);
		Category copy = new Category(owner, name, source.getDescription());
		copy.setEncoding(source.getEncoding());
		for (LogTemplate template : templateRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)) {
			LogTemplate cloned = new LogTemplate(template.getName(), template.getContent());
			cloned.setFormat(template.getFormat());
			cloned.setWeight(template.getWeight());
			cloned.setEnabled(template.isEnabled());
			cloned.setSortOrder(template.getSortOrder());
			copy.addTemplate(cloned);
		}
		return toResponse(categoryRepository.save(copy), userId);
	}

	@Transactional(readOnly = true)
	public CatalogDtos.CategoryExport export(Long userId, Long categoryId) {
		Category category = requireVisible(userId, categoryId);
		List<CatalogDtos.TemplateExport> templates = new ArrayList<>();
		for (LogTemplate template : templateRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)) {
			templates.add(new CatalogDtos.TemplateExport(template.getName(), template.getContent(),
					template.getFormat(), template.getWeight(),
					template.isEnabled(), template.getSortOrder()));
		}
		return new CatalogDtos.CategoryExport(category.getName(), category.getDescription(),
				category.getEncoding(), templates);
	}

	@Transactional
	public CatalogDtos.CategoryResponse importCategory(Long userId, CatalogDtos.CategoryExport payload) {
		String name = payload.name() == null ? "imported" : payload.name().trim();
		String finalName = name;
		int suffix = 1;
		while (categoryRepository.existsByOwnerIdAndName(userId, finalName)) {
			finalName = name + "-" + (++suffix);
		}
		User owner = userRepository.getReferenceById(userId);
		Category category = new Category(owner, finalName, payload.description());
		category.setEncoding(payload.encoding());
		if (payload.templates() != null) {
			for (CatalogDtos.TemplateExport source : payload.templates()) {
				LogTemplate template = new LogTemplate(source.name(), source.content());
				template.setFormat(source.format());
				template.setWeight(source.weight());
				template.setEnabled(source.enabled());
				template.setSortOrder(source.sortOrder());
				category.addTemplate(template);
			}
		}
		return toResponse(categoryRepository.save(category), userId);
	}

	/** 소유자만 수정 가능. */
	@Transactional(readOnly = true)
	public Category requireOwned(Long userId, Long categoryId) {
		Category category = categoryRepository.findById(categoryId)
				.orElseThrow(() -> TesterException.notFound("카테고리", categoryId));
		if (!category.getOwner().getId().equals(userId)) {
			throw TesterException.forbidden("다른 사용자의 카테고리는 수정할 수 없습니다");
		}
		return category;
	}

	/** 소유자 또는 공유된 카테고리면 조회 가능. */
	@Transactional(readOnly = true)
	public Category requireVisible(Long userId, Long categoryId) {
		Category category = categoryRepository.findById(categoryId)
				.orElseThrow(() -> TesterException.notFound("카테고리", categoryId));
		if (!category.getOwner().getId().equals(userId) && !category.isShared()) {
			throw TesterException.forbidden("접근할 수 없는 카테고리입니다");
		}
		return category;
	}

	private CatalogDtos.CategoryResponse toResponse(Category category, Long userId) {
		return new CatalogDtos.CategoryResponse(
				category.getId(),
				category.getName(),
				category.getDescription(),
				category.isShared(),
				category.getOwner().getId().equals(userId),
				(int) templateRepository.countByCategoryId(category.getId()),
				category.getEncoding(),
				category.getUpdatedAt());
	}
}
