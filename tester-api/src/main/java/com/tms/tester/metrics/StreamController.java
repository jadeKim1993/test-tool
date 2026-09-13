package com.tms.tester.metrics;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tms.tester.job.RunManager;
import com.tms.tester.security.AuthUser;

/**
 * 전송 상태 실시간 스트림.
 * <p>{@code EventSource} 가 헤더를 못 보내므로 티켓으로 인증한다(§StreamTicketService).
 */
@RestController
@RequestMapping("/api/stream")
public class StreamController {

	private final MetricsService metricsService;
	private final StreamTicketService ticketService;
	private final RunManager runManager;

	public StreamController(MetricsService metricsService, StreamTicketService ticketService,
			RunManager runManager) {
		this.metricsService = metricsService;
		this.ticketService = ticketService;
		this.runManager = runManager;
	}

	/** 30초 동안만 유효한 1회용 티켓을 발급한다. */
	@PostMapping("/ticket")
	public Map<String, String> ticket(@AuthenticationPrincipal AuthUser user) {
		return Map.of("ticket", ticketService.issue(user.id()));
	}

	@GetMapping(value = "/metrics", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> metrics(@RequestParam String ticket) {
		Long userId = ticketService.consume(ticket);
		if (userId == null) {
			return ResponseEntity.status(401).build();
		}
		List<Long> jobIds = runManager.runningJobIds(userId);
		return ResponseEntity.ok(metricsService.subscribe(userId, jobIds));
	}

	/** 그래프용 과거 샘플(최근 30분). SSE 가 끊겼을 때의 폴백으로도 쓴다. */
	@GetMapping("/history/{jobId}")
	public List<JobMetricsDtos.JobStatus> history(
			@AuthenticationPrincipal AuthUser user, @PathVariable Long jobId) {
		return metricsService.historyOf(jobId);
	}
}
