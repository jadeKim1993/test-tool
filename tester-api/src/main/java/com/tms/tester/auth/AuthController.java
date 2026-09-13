package com.tms.tester.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tms.tester.security.AuthUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	/** 아이디 중복 확인. 로그인 화면에서 가입/로그인 분기에 사용한다. */
	@GetMapping("/exists")
	public AuthDtos.ExistsResponse exists(@RequestParam String username) {
		return new AuthDtos.ExistsResponse(username, authService.exists(username));
	}

	@PostMapping("/signup")
	public ResponseEntity<AuthDtos.TokenResponse> signup(@Valid @RequestBody AuthDtos.UsernameRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(authService.signup(request.username(), request.displayName()));
	}

	@PostMapping("/login")
	public AuthDtos.TokenResponse login(@Valid @RequestBody AuthDtos.UsernameRequest request) {
		return authService.login(request.username());
	}

	@GetMapping("/me")
	public AuthDtos.UserResponse me(@AuthenticationPrincipal AuthUser user) {
		return authService.me(user.id());
	}
}
