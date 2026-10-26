package com.tms.tester.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tms.tester.domain.entity.LogTemplate;

public interface LogTemplateRepository extends JpaRepository<LogTemplate, Long> {

	List<LogTemplate> findByCategoryIdOrderBySortOrderAscIdAsc(Long categoryId);

	List<LogTemplate> findByCategoryIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long categoryId);

	long countByCategoryId(Long categoryId);

	/** 켜져 있어 실제로 전송에 쓰이는 로그 종류 수. */
	long countByCategoryIdAndEnabledTrue(Long categoryId);

	/** 내용에 특정 문자열이 든 템플릿(기동 시 옛 시드 복구용). */
	List<LogTemplate> findByContentContaining(String fragment);
}
