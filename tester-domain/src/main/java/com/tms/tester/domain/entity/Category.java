package com.tms.tester.domain.entity;

import java.util.ArrayList;
import java.util.List;

import com.tms.tester.common.WireEncoding;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 로그의 큰 분류(예: tomcat, nginx). 안에 여러 종류의 로그 템플릿을 담는다.
 */
@Entity
@Table(name = "categories", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "name"}))
public class Category extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 500)
	private String description;

	/** true 면 다른 사용자도 읽을 수 있다(기본 제공 템플릿 배포용). */
	@Column(nullable = false)
	private boolean shared;

	/**
	 * 이 카테고리의 로그를 회선에 실을 때의 바이트 변환 방식.
	 * <p>인코딩은 "이 로그가 어떤 문자셋으로 쓰인 로그인가"의 문제라 로그 종류 쪽에 속한다.
	 * 같은 수집 서버라도 카테고리마다 다른 인코딩으로 보낼 수 있다(§16.14).
	 */
	@Enumerated(EnumType.STRING)
	// ddl-auto=update 는 NOT NULL 컬럼을 그냥 추가하지 못한다(기존 행이 NULL 이 되므로).
	// 기본값을 컬럼 정의에 박아 두어야 이미 데이터가 있는 DB 에서도 ALTER 가 통과한다.
	@Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'UTF_8'")
	private WireEncoding encoding = WireEncoding.UTF_8;

	/**
	 * 화면에 보이는 순서(템플릿 화면에서 끌어서 바꾼다). 같으면 이름순이라, 한 번도 옮기지 않은 기존
	 * 카테고리는 예전처럼 이름순으로 보인다. NOT NULL 컬럼 추가라 기본값을 컬럼 정의에 둔다(encoding 과 같은 이유).
	 */
	@Column(name = "sort_order", nullable = false, columnDefinition = "int default 0")
	private int sortOrder;

	@OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("sortOrder ASC, id ASC")
	private List<LogTemplate> templates = new ArrayList<>();

	protected Category() {
	}

	public Category(User owner, String name, String description) {
		this.owner = owner;
		this.name = name;
		this.description = description;
	}

	public void addTemplate(LogTemplate template) {
		templates.add(template);
		template.setCategory(this);
	}

	public void removeTemplate(LogTemplate template) {
		templates.remove(template);
		template.setCategory(null);
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public void setSortOrder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public WireEncoding getEncoding() {
		return encoding;
	}

	public void setEncoding(WireEncoding encoding) {
		this.encoding = encoding == null ? WireEncoding.UTF_8 : encoding;
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

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isShared() {
		return shared;
	}

	public void setShared(boolean shared) {
		this.shared = shared;
	}

	public List<LogTemplate> getTemplates() {
		return templates;
	}
}
