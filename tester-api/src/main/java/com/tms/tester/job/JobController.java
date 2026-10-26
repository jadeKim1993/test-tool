package com.tms.tester.job;

import java.util.ArrayList;
import java.util.List;

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

import com.tms.tester.metrics.JobMetricsDtos;
import com.tms.tester.security.AuthUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

	private final JobService jobService;
	private final RunManager runManager;

	public JobController(JobService jobService, RunManager runManager) {
		this.jobService = jobService;
		this.runManager = runManager;
	}

	@GetMapping
	public List<JobDtos.JobResponse> list(@AuthenticationPrincipal AuthUser user) {
		return jobService.list(user.id());
	}

	@PostMapping
	public ResponseEntity<JobDtos.JobResponse> create(
			@AuthenticationPrincipal AuthUser user,
			@Valid @RequestBody JobDtos.JobRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(jobService.create(user.id(), request));
	}

	@PutMapping("/{id}")
	public JobDtos.JobResponse update(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@Valid @RequestBody JobDtos.JobRequest request) {
		return jobService.update(user.id(), id, request);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		jobService.delete(user.id(), id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/start")
	public ResponseEntity<JobMetricsDtos.JobStatus> start(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@RequestBody(required = false) JobDtos.StartRequest request) {
		jobService.start(user.id(), id, request);
		return ResponseEntity.accepted().body(runManager.statusOf(id));
	}

	@PostMapping("/{id}/stop")
	public ResponseEntity<Void> stop(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		jobService.stop(user.id(), id);
		return ResponseEntity.noContent().build();
	}

	/** 실행 중 속도 변경. 정지/재시작 없이 즉시 반영된다. */
	@PatchMapping("/{id}/rate")
	public ResponseEntity<JobMetricsDtos.JobStatus> updateRate(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@Valid @RequestBody JobDtos.RateRequest request) {
		jobService.updateRate(user.id(), id, request.eps());
		return ResponseEntity.ok(runManager.statusOf(id));
	}

	@GetMapping("/{id}/status")
	public ResponseEntity<JobMetricsDtos.JobStatus> status(
			@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		jobService.requireOwned(user.id(), id);
		JobMetricsDtos.JobStatus status = runManager.statusOf(id);
		return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
	}

	@GetMapping("/{id}/runs")
	public List<JobDtos.RunResponse> runs(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@RequestParam(defaultValue = "20") int limit) {
		return jobService.runs(user.id(), id, Math.min(Math.max(limit, 1), 100));
	}

	/** 이 작업이 실제로 보내는 로그 종류·비율·예시. 전송 콘솔 카드가 쓴다. */
	@GetMapping("/{id}/content")
	public JobDtos.JobContent content(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		return jobService.content(user.id(), id);
	}

	/** 대시보드용 요약. 실행 중 작업 전체의 합계를 준다. */
	@GetMapping("/running")
	public JobDtos.RunningSummary running(@AuthenticationPrincipal AuthUser user) {
		List<Long> ids = runManager.runningJobIds(user.id());
		double totalEps = 0;
		double totalMbps = 0;
		List<Long> active = new ArrayList<>();
		for (Long jobId : ids) {
			JobMetricsDtos.JobStatus status = runManager.statusOf(jobId);
			if (status != null) {
				totalEps += status.eps();
				totalMbps += status.mbps();
				active.add(jobId);
			}
		}
		return new JobDtos.RunningSummary(active.size(),
				Math.round(totalEps * 10) / 10.0, Math.round(totalMbps * 100) / 100.0, active);
	}
}
