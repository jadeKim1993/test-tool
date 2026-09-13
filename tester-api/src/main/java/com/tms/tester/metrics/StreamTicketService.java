package com.tms.tester.metrics;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * SSE 인증용 1회용 티켓.
 * <p>브라우저 {@code EventSource} 는 Authorization 헤더를 보낼 수 없다. 그래서
 * 인증된 요청으로 짧은 수명의 티켓을 받고, 그 티켓으로 스트림에 접속한다.
 * 토큰 자체를 URL 에 노출하지 않기 위한 구조다.
 */
@Service
public class StreamTicketService {

	private static final long TTL_MILLIS = 30_000;

	private final SecureRandom random = new SecureRandom();
	private final Map<String, Entry> tickets = new ConcurrentHashMap<>();

	private record Entry(Long userId, long expiresAt) {
	}

	public String issue(Long userId) {
		purgeExpired();
		byte[] bytes = new byte[24];
		random.nextBytes(bytes);
		String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		tickets.put(ticket, new Entry(userId, System.currentTimeMillis() + TTL_MILLIS));
		return ticket;
	}

	/**
	 * 티켓을 소비한다.
	 *
	 * @return 유효하면 사용자 ID, 아니면 null
	 */
	public Long consume(String ticket) {
		if (ticket == null) {
			return null;
		}
		Entry entry = tickets.remove(ticket);
		if (entry == null || entry.expiresAt() < System.currentTimeMillis()) {
			return null;
		}
		return entry.userId();
	}

	private void purgeExpired() {
		long now = System.currentTimeMillis();
		tickets.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
	}
}
