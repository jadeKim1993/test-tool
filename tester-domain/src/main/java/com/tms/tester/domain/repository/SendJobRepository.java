package com.tms.tester.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tms.tester.domain.entity.SendJob;

public interface SendJobRepository extends JpaRepository<SendJob, Long> {

	List<SendJob> findByOwnerIdOrderByNameAsc(Long ownerId);

	List<SendJob> findByAutostartTrue();

	long countByCategoryId(Long categoryId);

	long countByTargetId(Long targetId);
}
