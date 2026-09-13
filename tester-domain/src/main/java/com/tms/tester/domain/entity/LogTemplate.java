package com.tms.tester.domain.entity;

import com.tms.tester.common.LogFormat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 카테고리에 속한 개별 로그 종류. 본문에 {@code ${...}} 플레이스홀더를 포함한다.
 */
@Entity
@Table(name = "log_templates")
public class LogTemplate extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "category_id")
	private Category category;

	@Column(nullable = false, length = 100)
	private String name;

	@Lob
	@Column(nullable = false, length = 20000)
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private LogFormat format = LogFormat.RAW;

	/** 선택 가중치. 값이 클수록 자주 선택된다. */
	@Column(nullable = false)
	private int weight = 1;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(nullable = false)
	private int sortOrder;

	/** 낙관적 락 겸 캐시 무효화 키. 수정되면 실행 중인 작업이 다음 틱에 재컴파일한다. */
	@Version
	private long version;

	protected LogTemplate() {
	}

	public LogTemplate(String name, String content) {
		this.name = name;
		this.content = content;
	}

	public Long getId() {
		return id;
	}

	public Category getCategory() {
		return category;
	}

	void setCategory(Category category) {
		this.category = category;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public LogFormat getFormat() {
		return format;
	}

	public void setFormat(LogFormat format) {
		this.format = format;
	}

	public int getWeight() {
		return weight;
	}

	public void setWeight(int weight) {
		this.weight = Math.max(1, weight);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public void setSortOrder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public long getVersion() {
		return version;
	}
}
