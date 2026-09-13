package com.tms.tester.job;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tms.tester.common.RunState;
import com.tms.tester.domain.entity.SendJob;
import com.tms.tester.domain.entity.SendJobRun;
import com.tms.tester.domain.repository.SendJobRepository;
import com.tms.tester.domain.repository.SendJobRunRepository;

/** 실행 이력 기록. 트랜잭션 경계를 RunManager 밖으로 분리하기 위한 얇은 서비스. */
@Service
public class RunHistoryService {

	private final SendJobRunRepository runRepository;
	private final SendJobRepository jobRepository;

	public RunHistoryService(SendJobRunRepository runRepository, SendJobRepository jobRepository) {
		this.runRepository = runRepository;
		this.jobRepository = jobRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long begin(Long jobId, int targetEps, String endpoint) {
		SendJob job = jobRepository.getReferenceById(jobId);
		return runRepository.save(new SendJobRun(job, targetEps, endpoint)).getId();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finish(Long runId, RunState state, long sent, long bytes, long dropped, long errors,
			int avgEps, int peakEps, String message) {
		runRepository.findById(runId).ifPresent(run ->
				run.complete(state, sent, bytes, dropped, errors, avgEps, peakEps, message));
	}

	@Transactional(readOnly = true)
	public List<SendJobRun> recentRuns(Long jobId, int limit) {
		return runRepository.findByJobIdOrderByStartedAtDesc(jobId, Limit.of(limit));
	}

	/** 작업 삭제 트랜잭션 안에서 호출한다. 이력을 먼저 비워야 FK 제약에 걸리지 않는다. */
	@Transactional
	public int deleteRuns(Long jobId) {
		return runRepository.deleteByJobId(jobId);
	}
}
