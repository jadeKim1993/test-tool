package com.tms.tester.domain.repository;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tms.tester.domain.entity.SendJobRun;

public interface SendJobRunRepository extends JpaRepository<SendJobRun, Long> {

	List<SendJobRun> findByJobIdOrderByStartedAtDesc(Long jobId, Limit limit);

	List<SendJobRun> findByJobOwnerIdOrderByStartedAtDesc(Long ownerId, Limit limit);

	/**
	 * 작업을 지우기 전에 그 작업의 이력을 먼저 지운다.
	 * <p>{@code send_job_runs} 가 {@code send_jobs} 를 참조하므로 이력을 남겨 두면
	 * 작업 삭제가 FK 위반으로 실패한다. 이력은 작업 없이는 의미가 없어 같이 지운다.
	 */
	@Modifying
	@Query("delete from SendJobRun r where r.job.id = :jobId")
	int deleteByJobId(@Param("jobId") Long jobId);
}
