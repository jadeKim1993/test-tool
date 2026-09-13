package com.tms.tester.auth;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 인증 관련 요청/응답. 비밀번호 필드는 존재하지 않는다. */
public final class AuthDtos {

	private AuthDtos() {
	}

	/**
	 * 아이디 형식. 일반 아이디({@code gtkim})와 이메일({@code gtkim@example.com})을 모두 받는다.
	 * <p>프론트엔드({@code Login.tsx})에도 같은 정규식이 있으므로 함께 고쳐야 한다.
	 */
	public static final String USERNAME_PATTERN =
			"^[a-zA-Z0-9._%+-]+(@[a-zA-Z0-9][a-zA-Z0-9.-]*\\.[a-zA-Z]{2,})?$";

	public record UsernameRequest(
			@NotBlank(message = "아이디를 입력하세요")
			@Size(min = 3, max = 100, message = "아이디는 3~100자여야 합니다")
			@Pattern(regexp = USERNAME_PATTERN,
					message = "아이디 또는 이메일 형식으로 입력하세요 (영문/숫자와 . _ % + - 사용 가능)")
			String username,
			String displayName) {
	}

	public record ExistsResponse(String username, boolean exists) {
	}

	public record UserResponse(Long id, String username, String displayName) {
	}

	public record TokenResponse(String accessToken, Instant expiresAt, UserResponse user) {
	}
}
