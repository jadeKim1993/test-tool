package com.tms.tester.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tms.tester.common.TesterException;
import com.tms.tester.config.TesterProperties;
import com.tms.tester.domain.entity.User;
import com.tms.tester.domain.repository.UserRepository;
import com.tms.tester.security.JwtService;
import com.tms.tester.seed.SeedService;

/**
 * 아이디만으로 가입/로그인한다.
 * <p>중복 확인과 가입 사이에 다른 요청이 끼어드는 경우(TOCTOU)는 DB 의 유니크 제약으로
 * 최종 차단하고, 그 예외를 사용자 메시지로 변환한다.
 */
@Service
public class AuthService {

	private final UserRepository userRepository;
	private final JwtService jwtService;
	private final TesterProperties properties;
	private final SeedService seedService;

	public AuthService(UserRepository userRepository, JwtService jwtService,
			TesterProperties properties, SeedService seedService) {
		this.userRepository = userRepository;
		this.jwtService = jwtService;
		this.properties = properties;
		this.seedService = seedService;
	}

	@Transactional(readOnly = true)
	public boolean exists(String username) {
		return userRepository.existsByUsername(normalize(username));
	}

	@Transactional
	public AuthDtos.TokenResponse signup(String username, String displayName) {
		if (!properties.getSecurity().isAllowSignup()) {
			throw TesterException.forbidden("현재 신규 가입이 허용되지 않습니다");
		}
		String normalized = normalize(username);
		if (userRepository.existsByUsername(normalized)) {
			throw TesterException.conflict("이미 사용 중인 아이디입니다: " + normalized);
		}
		User user;
		try {
			user = userRepository.saveAndFlush(new User(normalized, displayName));
		}
		catch (DataIntegrityViolationException e) {
			// 중복 확인 직후 동시에 가입된 경우
			throw TesterException.conflict("이미 사용 중인 아이디입니다: " + normalized);
		}
		// 첫 로그인부터 바로 쓸 수 있도록 기본 카테고리/대상을 넣어준다.
		seedService.seedForNewUser(user);
		user.markLogin();
		return toToken(user);
	}

	@Transactional
	public AuthDtos.TokenResponse login(String username) {
		String normalized = normalize(username);
		User user = userRepository.findByUsername(normalized)
				.orElseThrow(() -> TesterException.notFound("계정", normalized));
		user.markLogin();
		return toToken(user);
	}

	@Transactional(readOnly = true)
	public AuthDtos.UserResponse me(Long userId) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> TesterException.notFound("계정", userId));
		return toUser(user);
	}

	private AuthDtos.TokenResponse toToken(User user) {
		JwtService.Token token = jwtService.issue(user);
		return new AuthDtos.TokenResponse(token.value(), token.expiresAt(), toUser(user));
	}

	private AuthDtos.UserResponse toUser(User user) {
		return new AuthDtos.UserResponse(user.getId(), user.getUsername(), user.getDisplayName());
	}

	private String normalize(String username) {
		if (username == null || username.isBlank()) {
			throw TesterException.invalid("아이디를 입력하세요");
		}
		return username.trim().toLowerCase();
	}
}
