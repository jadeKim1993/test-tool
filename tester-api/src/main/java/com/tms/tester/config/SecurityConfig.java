package com.tms.tester.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.tms.tester.security.JwtAuthFilter;

/**
 * 보안 설정.
 * <p><b>주의</b>: 요구사항에 따라 비밀번호가 없다. 아이디만 알면 로그인이 가능하므로
 * 반드시 내부망에서만 운영해야 하며, 외부 노출 시 앞단에서 IP 제한 등을 추가해야 한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
		return http
				// 토큰 기반이라 세션/CSRF 를 쓰지 않는다.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.authorizeHttpRequests(auth -> auth
						// 로그인 전에 열려 있어야 하는 것만 연다. /api/auth/me 는 토큰이 필요하다
						// (여기까지 permitAll 이면 principal 이 null 인 채 컨트롤러에 들어가 500 이 된다).
						.requestMatchers("/api/auth/exists", "/api/auth/signup", "/api/auth/login").permitAll()
						// SSE 는 쿼리 티켓으로 컨트롤러에서 인증한다.
						.requestMatchers("/api/stream/metrics").permitAll()
						.requestMatchers("/actuator/health").permitAll()
						.requestMatchers("/api/**").authenticated()
						.anyRequest().permitAll())
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}
