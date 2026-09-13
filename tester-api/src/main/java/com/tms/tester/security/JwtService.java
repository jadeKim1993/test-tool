package com.tms.tester.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tms.tester.config.TesterProperties;
import com.tms.tester.domain.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** JWT 발급/검증. HS256 대칭키를 사용한다. */
@Service
public class JwtService {

	private static final Logger log = LoggerFactory.getLogger(JwtService.class);

	private final SecretKey key;
	private final Duration ttl;
	private final Duration refreshThreshold;

	public JwtService(TesterProperties properties) {
		this.ttl = properties.getJwt().getTtl();
		this.refreshThreshold = properties.getJwt().getRefreshThreshold();

		String secret = properties.getJwt().getSecret();
		if (secret == null || secret.isBlank()) {
			// 기동할 때마다 새 키를 만든다. 안전하지만 재시작하면 기존 토큰이 무효가 된다.
			byte[] random = new byte[32];
			new SecureRandom().nextBytes(random);
			this.key = Keys.hmacShaKeyFor(random);
			log.warn("tester.jwt.secret 이 설정되지 않아 임시 키를 생성했습니다. "
					+ "재시작하면 기존 로그인 토큰이 모두 무효화됩니다. "
					+ "운영에서는 TESTER_JWT_SECRET 환경변수를 지정하십시오.");
		}
		else {
			byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
			if (bytes.length < 32) {
				// HS256 은 최소 256비트 키를 요구한다. 짧은 값은 패딩하지 않고 거부한다.
				throw new IllegalStateException("tester.jwt.secret 은 최소 32자 이상이어야 합니다 (현재 "
						+ bytes.length + "자)");
			}
			this.key = Keys.hmacShaKeyFor(bytes);
			log.info("JWT 서명키 로드 완료 (유효기간 {}시간)", ttl.toHours());
		}
	}

	public record Token(String value, Instant expiresAt) {
	}

	public Token issue(User user) {
		Instant now = Instant.now();
		Instant expiry = now.plus(ttl);
		String token = Jwts.builder()
				.subject(String.valueOf(user.getId()))
				.claim("username", user.getUsername())
				.id(Base64.getUrlEncoder().withoutPadding()
						.encodeToString(String.valueOf(now.toEpochMilli()).getBytes(StandardCharsets.UTF_8)))
				.issuedAt(java.util.Date.from(now))
				.expiration(java.util.Date.from(expiry))
				.signWith(key)
				.compact();
		return new Token(token, expiry);
	}

	/**
	 * 토큰을 검증하고 사용자 정보를 복원한다.
	 *
	 * @return 유효하면 사용자, 아니면 null
	 */
	public AuthUser verify(String token) {
		try {
			Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
			return new AuthUser(Long.valueOf(claims.getSubject()), claims.get("username", String.class));
		}
		catch (JwtException | IllegalArgumentException e) {
			return null;
		}
	}

	/** 만료가 임박했는지. 임박하면 응답 헤더로 새 토큰을 내려보낸다. */
	public boolean needsRefresh(String token) {
		try {
			Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
			Instant expiry = claims.getExpiration().toInstant();
			return expiry.minus(refreshThreshold).isBefore(Instant.now());
		}
		catch (JwtException | IllegalArgumentException e) {
			return false;
		}
	}
}
