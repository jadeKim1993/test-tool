package com.tms.tester.job;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tms.tester.common.OverflowPolicy;
import com.tms.tester.common.SelectionStrategy;
import com.tms.tester.common.TesterException;
import com.tms.tester.domain.entity.Category;
import com.tms.tester.domain.entity.LogTemplate;
import com.tms.tester.domain.entity.SendJob;
import com.tms.tester.domain.entity.SendJobRun;
import com.tms.tester.domain.entity.SendTarget;
import com.tms.tester.domain.entity.User;
import com.tms.tester.domain.repository.LogTemplateRepository;
import com.tms.tester.domain.repository.SendJobRepository;
import com.tms.tester.domain.repository.UserRepository;
import com.tms.tester.catalog.CategoryService;
import com.tms.tester.target.TargetService;
import com.tms.tester.transport.TemplateSource;

import jakarta.annotation.PostConstruct;

@Service
public class JobService {

	private final SendJobRepository jobRepository;
	private final LogTemplateRepository templateRepository;
	private final UserRepository userRepository;
	private final CategoryService categoryService;
	private final TargetService targetService;
	private final RunManager runManager;
	private final RunHistoryService historyService;

	public JobService(SendJobRepository jobRepository, LogTemplateRepository templateRepository,
			UserRepository userRepository, CategoryService categoryService, TargetService targetService,
			RunManager runManager, RunHistoryService historyService) {
		this.jobRepository = jobRepository;
		this.templateRepository = templateRepository;
		this.userRepository = userRepository;
		this.categoryService = categoryService;
		this.targetService = targetService;
		this.runManager = runManager;
		this.historyService = historyService;
	}

	/** 템플릿 수정 시 실행 중 작업에 반영할 수 있도록 재조회 경로를 등록한다. */
	@PostConstruct
	void registerTemplateLoader() {
		runManager.setTemplateLoader(this::loadTemplateSources);
	}

	// ---------------- CRUD ----------------

	@Transactional(readOnly = true)
	public List<JobDtos.JobResponse> list(Long userId) {
		List<JobDtos.JobResponse> result = new ArrayList<>();
		for (SendJob job : jobRepository.findByOwnerIdOrderByNameAsc(userId)) {
			result.add(toResponse(job));
		}
		return result;
	}

	@Transactional
	public JobDtos.JobResponse create(Long userId, JobDtos.JobRequest request) {
		Category category = categoryService.requireVisible(userId, request.categoryId());
		SendTarget target = targetService.requireOwned(userId, request.targetId());
		User owner = userRepository.getReferenceById(userId);

		SendJob job = new SendJob(owner, resolveName(request.name(), category, target), category, target);
		apply(job, request);
		return toResponse(jobRepository.save(job));
	}

	@Transactional
	public JobDtos.JobResponse update(Long userId, Long jobId, JobDtos.JobRequest request) {
		SendJob job = requireOwned(userId, jobId);
		Category category = categoryService.requireVisible(userId, request.categoryId());
		SendTarget target = targetService.requireOwned(userId, request.targetId());
		job.setName(resolveName(request.name(), category, target));
		job.setCategory(category);
		job.setTarget(target);
		apply(job, request);
		return toResponse(job);
	}

	@Transactional
	public void delete(Long userId, Long jobId) {
		SendJob job = requireOwned(userId, jobId);
		if (runManager.isRunning(jobId)) {
			throw TesterException.state("실행 중인 작업은 삭제할 수 없습니다. 먼저 정지하세요.");
		}
		// 이력이 작업을 참조하므로 순서를 지켜야 한다. 한 번이라도 실행한 작업은
		// 이 줄이 없으면 FK 위반으로 삭제 자체가 실패한다.
		historyService.deleteRuns(jobId);
		jobRepository.delete(job);
	}

	// ---------------- 실행 ----------------

	@Transactional(readOnly = true)
	public void start(Long userId, Long jobId, JobDtos.StartRequest request) {
		SendJob job = requireOwned(userId, jobId);
		List<TemplateSource> templates = loadTemplateSources(jobId);
		if (templates.isEmpty()) {
			throw TesterException.state(
					"카테고리 '" + job.getCategory().getName() + "' 에 사용할 수 있는 로그 템플릿이 없습니다");
		}
		int eps = (request != null && request.eps() != null) ? request.eps() : job.getEps();
		Integer duration = (request != null && request.durationSec() != null)
				? request.durationSec() : job.getDurationSec();
		Long limit = (request != null && request.totalLimit() != null)
				? request.totalLimit() : job.getTotalLimit();

		runManager.start(new RunManager.JobLaunchContext(
				job.getId(),
				job.getName(),
				userId,
				job.getCategory().getId(),
				targetService.toSpec(job.getTarget()),
				templates,
				job.getSelection(),
				eps,
				job.getWorkerThreads(),
				duration,
				limit,
				job.getOverflowPolicy(),
				job.getCategory().getEncoding()));
	}

	@Transactional(readOnly = true)
	public void stop(Long userId, Long jobId) {
		requireOwned(userId, jobId);
		runManager.stop(jobId);
	}

	@Transactional(readOnly = true)
	public void updateRate(Long userId, Long jobId, int eps) {
		requireOwned(userId, jobId);
		runManager.updateRate(jobId, eps);
	}

	@Transactional(readOnly = true)
	public List<JobDtos.RunResponse> runs(Long userId, Long jobId, int limit) {
		requireOwned(userId, jobId);
		List<JobDtos.RunResponse> result = new ArrayList<>();
		for (SendJobRun run : historyService.recentRuns(jobId, limit)) {
			result.add(new JobDtos.RunResponse(run.getId(), jobId, run.getStartedAt(), run.getEndedAt(),
					run.getStatus(), run.getTargetEps(), run.getSentCount(), run.getByteCount(),
					run.getDroppedCount(), run.getErrorCount(), run.getAvgEps(), run.getPeakEps(),
					run.getEndpoint(), run.getMessage()));
		}
		return result;
	}

	/** 작업이 사용할 템플릿을 실행 시점에 새로 읽는다(수정 즉시 반영의 근거). */
	@Transactional(readOnly = true)
	public List<TemplateSource> loadTemplateSources(Long jobId) {
		SendJob job = jobRepository.findById(jobId).orElse(null);
		if (job == null) {
			return List.of();
		}
		List<LogTemplate> templates =
				templateRepository.findByCategoryIdAndEnabledTrueOrderBySortOrderAscIdAsc(job.getCategory().getId());
		Set<Long> selected = job.getTemplateIds();
		List<TemplateSource> sources = new ArrayList<>();
		for (LogTemplate template : templates) {
			if (!selected.isEmpty() && !selected.contains(template.getId())) {
				continue;
			}
			sources.add(new TemplateSource(template.getId(), template.getName(), template.getFormat(),
					template.getContent(), template.getWeight()));
		}
		return sources;
	}

	/** 기동 시 autostart 로 표시된 작업을 자동으로 재개한다. */
	@EventListener(ApplicationReadyEvent.class)
	@Transactional(readOnly = true)
	public void resumeAutostartJobs() {
		for (SendJob job : jobRepository.findByAutostartTrue()) {
			try {
				start(job.getOwner().getId(), job.getId(), null);
			}
			catch (RuntimeException e) {
				// 대상이 아직 안 떠 있을 수 있다. 기동 자체를 막지는 않는다.
				org.slf4j.LoggerFactory.getLogger(JobService.class)
						.warn("자동 시작 실패: {} ({})", job.getName(), e.getMessage());
			}
		}
	}

	@Transactional(readOnly = true)
	public SendJob requireOwned(Long userId, Long jobId) {
		SendJob job = jobRepository.findById(jobId)
				.orElseThrow(() -> TesterException.notFound("전송 작업", jobId));
		if (!job.getOwner().getId().equals(userId)) {
			throw TesterException.forbidden("다른 사용자의 전송 작업입니다");
		}
		return job;
	}

	/** 이름을 비워 두면 "카테고리 → 대상" 으로 만든다. 대부분의 경우 이걸로 충분하다. */
	private String resolveName(String requested, Category category, SendTarget target) {
		if (requested != null && !requested.isBlank()) {
			return requested.trim();
		}
		return category.getName() + " → " + target.getHost() + ":" + target.getPort();
	}

	private void apply(SendJob job, JobDtos.JobRequest request) {
		job.setEps(request.eps());
		job.setSelection(request.selection() == null ? SelectionStrategy.ROUND_ROBIN : request.selection());
		job.setOverflowPolicy(request.overflowPolicy() == null ? OverflowPolicy.DROP : request.overflowPolicy());
		job.setDurationSec(request.durationSec());
		job.setTotalLimit(request.totalLimit());
		job.setTemplateIds(request.templateIds());
		// 워커 스레드 수는 노출하지 않는다. 0 이면 EPS 에 맞춰 엔진이 산정한다.
		job.setWorkerThreads(0);
		if (request.autostart() != null) {
			job.setAutostart(request.autostart());
		}
	}

	private JobDtos.JobResponse toResponse(SendJob job) {
		Set<Long> templateIds = new LinkedHashSet<>(job.getTemplateIds());
		int templateCount = templateIds.isEmpty()
				? templateRepository.findByCategoryIdAndEnabledTrueOrderBySortOrderAscIdAsc(
						job.getCategory().getId()).size()
				: templateIds.size();
		return new JobDtos.JobResponse(
				job.getId(), job.getName(),
				job.getCategory().getId(), job.getCategory().getName(),
				job.getTarget().getId(), job.getTarget().getName(), job.getTarget().endpoint(),
				templateIds, templateCount,
				job.getEps(), job.getSelection(), job.getOverflowPolicy(),
				job.getDurationSec(), job.getTotalLimit(), job.getWorkerThreads(), job.isAutostart(),
				runManager.isRunning(job.getId()), job.getUpdatedAt());
	}
}
