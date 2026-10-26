package com.tms.tester.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tms.tester.domain.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	/** 내 카테고리(내가 정한 순서) + 그 뒤에 다른 사람의 공유 카테고리. */
	@Query("select c from Category c where c.owner.id = :ownerId or c.shared = true "
			+ "order by case when c.owner.id = :ownerId then 0 else 1 end, c.sortOrder, c.name")
	List<Category> findVisible(@Param("ownerId") Long ownerId);

	/** 새 카테고리를 맨 뒤에 두기 위한 현재 최댓값. 없으면 -1. */
	@Query("select coalesce(max(c.sortOrder), -1) from Category c where c.owner.id = :ownerId")
	int maxSortOrder(@Param("ownerId") Long ownerId);

	Optional<Category> findByOwnerIdAndName(Long ownerId, String name);

	boolean existsByOwnerIdAndName(Long ownerId, String name);

	@Query("select c from Category c left join fetch c.templates where c.id = :id")
	Optional<Category> findWithTemplates(@Param("id") Long id);
}
