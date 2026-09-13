package com.tms.tester.target;

import java.time.Instant;

import com.tms.tester.common.Framing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class TargetDtos {

	private TargetDtos() {
	}

	/**
	 * 전송 대상은 <b>프로토콜 + 호스트 + 포트</b>로 식별한다.
	 * 이름과 커넥션 수는 입력받지 않는다(이름은 주소에서 파생, 커넥션은 워커 수에 맞춰 자동).
	 */
	public record TargetRequest(
			@NotBlank(message = "프로토콜을 선택하세요") String protocol,
			@NotBlank(message = "호스트를 입력하세요") @Size(max = 255) String host,
			@Min(value = 1, message = "포트는 1~65535 범위여야 합니다")
			@Max(value = 65535, message = "포트는 1~65535 범위여야 합니다") int port,
			Framing framing,
			Integer batchBytes,
			Integer sendBufferBytes,
			Integer connectTimeoutMs) {
	}

	public record TargetResponse(
			Long id,
			String name,
			String protocol,
			String host,
			int port,
			int connections,
			Framing framing,
			int batchBytes,
			int sendBufferBytes,
			int connectTimeoutMs,
			String endpoint,
			Instant updatedAt) {
	}

	public record TestResult(boolean ok, long latencyMs, String message) {
	}
}
