package com.tms.tester.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tms.tester.domain.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	/** 내 카테고리 + 공유 카테고리. */
	@Query("select c from Category c where c.owner.id = :ownerId or c.shared = true order by c.name")
	List<Category> findVisible(@Param("ownerId") Long ownerId);

	Optional<Category> findByOwnerIdAndName(Long ownerId, String name);

	boolean existsByOwnerIdAndName(Long ownerId, String name);

	@Query("select c from Category c left join fetch c.templates where c.id = :id")
	Optional<Category> findWithTemplates(@Param("id") Long id);
}
