package com.tms.tester.domain.entity;

import java.util.LinkedHashSet;
import java.util.Set;

import com.tms.tester.common.OverflowPolicy;
import com.tms.tester.common.SelectionStrategy;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
 * "어떤 카테고리를 / 어디로 / 초당 몇 건" 보낼지에 대한 저장된 설정.
 * 애플리케이션이 재시작되어도 그대로 남는다.
 */
@Entity
@Table(name = "send_jobs")
public class SendJob extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	@Column(nullable = false, length = 100)
	private String name;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "target_id", nullable = false)
	private SendTarget target;

	/** 비어 있으면 카테고리의 활성 템플릿 전체를 사용한다. */
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "send_job_templates", joinColumns = @JoinColumn(name = "job_id"))
	@Column(name = "template_id")
	private Set<Long> templateIds = new LinkedHashSet<>();

	/** 목표 초당 건수. 0 이면 무제한(최대 속도). */
	@Column(nullable = false)
	private int eps = 1000;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SelectionStrategy selection = SelectionStrategy.ROUND_ROBIN;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OverflowPolicy overflowPolicy = OverflowPolicy.DROP;

	/** 실행 시간 제한(초). null 이면 무제한. */
	private Integer durationSec;

	/** 총 전송 건수 제한. null 이면 무제한. */
	private Long totalLimit;

	/** 0 이면 EPS 에 맞춰 자동 산정. */
	@Column(nullable = false)
	private int workerThreads;

	/** 애플리케이션 기동 시 자동으로 다시 시작할지 여부. */
	@Column(nullable = false)
	private boolean autostart;

	protected SendJob() {
	}

	public SendJob(User owner, String name, Category category, SendTarget target) {
		this.owner = owner;
		this.name = name;
		this.category = category;
		this.target = target;
	}

	public Long getId() {
		return id;
	}

	public User getOwner() {
		return owner;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Category getCategory() {
		return category;
	}

	public void setCategory(Category category) {
		this.category = category;
	}

	public SendTarget getTarget() {
		return target;
	}

	public void setTarget(SendTarget target) {
		this.target = target;
	}

	public Set<Long> getTemplateIds() {
		return templateIds;
	}

	public void setTemplateIds(Set<Long> templateIds) {
		this.templateIds = templateIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(templateIds);
	}

	public int getEps() {
		return eps;
	}

	public void setEps(int eps) {
		this.eps = Math.max(0, eps);
	}

	public SelectionStrategy getSelection() {
		return selection;
	}

	public void setSelection(SelectionStrategy selection) {
		this.selection = selection;
	}

	public OverflowPolicy getOverflowPolicy() {
		return overflowPolicy;
	}

	public void setOverflowPolicy(OverflowPolicy overflowPolicy) {
		this.overflowPolicy = overflowPolicy;
	}

	public Integer getDurationSec() {
		return durationSec;
	}

	public void setDurationSec(Integer durationSec) {
		this.durationSec = durationSec;
	}

	public Long getTotalLimit() {
		return totalLimit;
	}

	public void setTotalLimit(Long totalLimit) {
		this.totalLimit = totalLimit;
	}

	public int getWorkerThreads() {
		return workerThreads;
	}

	public void setWorkerThreads(int workerThreads) {
		this.workerThreads = Math.max(0, workerThreads);
	}

	public boolean isAutostart() {
		return autostart;
	}

	public void setAutostart(boolean autostart) {
		this.autostart = autostart;
	}
}
