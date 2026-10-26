package com.tms.tester.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
			this.key = Keys.hmacShaKeyFor(loadOrCreateSecret(Path.of(properties.getJwt().getSecretFile())));
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

	/**
	 * 서명키 파일을 읽는다. 없으면 무작위 키를 만들어 저장한다(소유자만 읽을 수 있게).
	 * 저장할 수 없는 환경이면 이번 기동에만 쓰는 임시 키로 동작한다 — 기동을 막을 일은 아니다.
	 */
	private static byte[] loadOrCreateSecret(Path file) {
		try {
			if (Files.isRegularFile(file)) {
				byte[] stored = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).trim());
				if (stored.length >= 32) {
					log.info("JWT 서명키를 파일에서 읽었습니다: {}", file.toAbsolutePath().normalize());
					return stored;
				}
				log.warn("서명키 파일이 너무 짧아 새로 만듭니다: {}", file);
			}
			byte[] random = new byte[48];
			new SecureRandom().nextBytes(random);
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			Files.writeString(file, Base64.getEncoder().encodeToString(random) + "\n", StandardCharsets.US_ASCII);
			try {
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
			}
			catch (UnsupportedOperationException e) {
				// POSIX 권한이 없는 파일시스템(Windows 등). 파일은 그대로 쓴다.
			}
			log.info("JWT 서명키를 만들어 저장했습니다: {} (재시작해도 로그인이 유지됩니다)", file.toAbsolutePath().normalize());
			return random;
		}
		catch (IOException | IllegalArgumentException e) {
			byte[] random = new byte[32];
			new SecureRandom().nextBytes(random);
			log.warn("서명키 파일({})을 쓸 수 없어 임시 키를 씁니다 — 재시작하면 모두 다시 로그인해야 합니다: {}",
					file, e.getMessage());
			return random;
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
