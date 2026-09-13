package com.tms.tester.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tms.tester.domain.entity.LogTemplate;

public interface LogTemplateRepository extends JpaRepository<LogTemplate, Long> {

	List<LogTemplate> findByCategoryIdOrderBySortOrderAscIdAsc(Long categoryId);

	List<LogTemplate> findByCategoryIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long categoryId);

	long countByCategoryId(Long categoryId);
}
