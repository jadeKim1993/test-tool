package com.tms.tester.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 사용자. 요구사항에 따라 비밀번호를 두지 않고 아이디만으로 식별한다.
 * <p>따라서 <b>내부망 전용</b>이며, 외부 노출 시에는 앞단(nginx 등)에서 별도 접근 통제가 필요하다.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 로그인 ID. 일반 아이디와 이메일을 모두 허용한다. */
	@Column(nullable = false, unique = true, length = 100)
	private String username;

	@Column(length = 100)
	private String displayName;

	@Column(nullable = false, length = 20)
	private String role = "USER";

	private Instant lastLoginAt;

	protected User() {
	}

	public User(String username, String displayName) {
		this.username = username;
		this.displayName = (displayName == null || displayName.isBlank())
				? defaultDisplayName(username) : displayName;
	}

	/**
	 * 표시명 기본값.
	 * <p>이메일로 가입하면 헤더에 주소 전체가 나와 길어지므로 @ 앞부분만 쓴다.
	 * 로그인 ID 자체는 입력한 값 그대로 유지된다.
	 */
	private static String defaultDisplayName(String username) {
		int at = username.indexOf('@');
		return at > 0 ? username.substring(0, at) : username;
	}

	public void markLogin() {
		this.lastLoginAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getRole() {
		return role;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}
}
