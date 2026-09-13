package com.tms.tester.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authorization 헤더의 JWT 를 검증해 SecurityContext 를 채운다.
 * <p>SSE 는 브라우저 EventSource 가 헤더를 보낼 수 없으므로 티켓 방식을 별도로 사용한다
 * ({@code /api/stream/**} 는 이 필터를 통과시키고 컨트롤러에서 티켓을 검증한다).
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final JwtService jwtService;

	public JwtAuthFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith(BEARER)) {
			String token = header.substring(BEARER.length()).trim();
			AuthUser user = jwtService.verify(token);
			if (user != null) {
				var authentication = new UsernamePasswordAuthenticationToken(
						user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
				SecurityContextHolder.getContext().setAuthentication(authentication);
				// 만료가 임박하면 슬라이딩 갱신용 토큰을 함께 내려준다.
				if (jwtService.needsRefresh(token)) {
					response.setHeader("X-Refresh-Token", token);
				}
			}
		}
		chain.doFilter(request, response);
	}
}
