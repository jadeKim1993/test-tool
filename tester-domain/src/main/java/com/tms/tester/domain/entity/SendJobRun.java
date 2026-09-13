package com.tms.tester.domain.entity;

import java.time.Instant;

import com.tms.tester.common.RunState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 전송 작업의 1회 실행 이력.
 * <p>1초 단위 메트릭은 저장하지 않는다(10만 EPS 에서 DB 기록은 그 자체가 병목이다).
 * 실행이 끝날 때 요약만 남긴다.
 */
@Entity
@Table(name = "send_job_runs")
public class SendJobRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_id", nullable = false)
	private SendJob job;

	@Column(nullable = false)
	private Instant startedAt = Instant.now();

	private Instant endedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RunState status = RunState.RUNNING;

	@Column(nullable = false)
	private int targetEps;

	@Column(nullable = false)
	private long sentCount;

	@Column(nullable = false)
	private long byteCount;

	@Column(nullable = false)
	private long droppedCount;

	@Column(nullable = false)
	private long errorCount;

	@Column(nullable = false)
	private int avgEps;

	@Column(nullable = false)
	private int peakEps;

	@Column(length = 255)
	private String endpoint;

	@Column(length = 1000)
	private String message;

	protected SendJobRun() {
	}

	public SendJobRun(SendJob job, int targetEps, String endpoint) {
		this.job = job;
		this.targetEps = targetEps;
		this.endpoint = endpoint;
	}

	public void complete(RunState status, long sent, long bytes, long dropped, long errors,
			int avgEps, int peakEps, String message) {
		this.status = status;
		this.sentCount = sent;
		this.byteCount = bytes;
		this.droppedCount = dropped;
		this.errorCount = errors;
		this.avgEps = avgEps;
		this.peakEps = peakEps;
		this.message = message;
		this.endedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public SendJob getJob() {
		return job;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public RunState getStatus() {
		return status;
	}

	public int getTargetEps() {
		return targetEps;
	}

	public long getSentCount() {
		return sentCount;
	}

	public long getByteCount() {
		return byteCount;
	}

	public long getDroppedCount() {
		return droppedCount;
	}

	public long getErrorCount() {
		return errorCount;
	}

	public int getAvgEps() {
		return avgEps;
	}

	public int getPeakEps() {
		return peakEps;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public String getMessage() {
		return message;
	}
}
