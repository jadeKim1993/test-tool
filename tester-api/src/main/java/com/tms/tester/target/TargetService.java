package com.tms.tester.target;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tms.tester.common.Framing;
import com.tms.tester.common.Protocol;
import com.tms.tester.common.TesterException;
import com.tms.tester.domain.entity.SendTarget;
import com.tms.tester.domain.entity.User;
import com.tms.tester.domain.repository.SendJobRepository;
import com.tms.tester.domain.repository.SendTargetRepository;
import com.tms.tester.domain.repository.UserRepository;
import com.tms.tester.transport.LogSender;
import com.tms.tester.transport.NettyRuntime;
import com.tms.tester.transport.TargetSpec;
import com.tms.tester.transport.TransportRegistry;

@Service
public class TargetService {

	private final SendTargetRepository targetRepository;
	private final SendJobRepository jobRepository;
	private final UserRepository userRepository;
	private final TransportRegistry transportRegistry;
	private final NettyRuntime nettyRuntime;

	public TargetService(SendTargetRepository targetRepository, SendJobRepository jobRepository,
			UserRepository userRepository, TransportRegistry transportRegistry, NettyRuntime nettyRuntime) {
		this.targetRepository = targetRepository;
		this.jobRepository = jobRepository;
		this.userRepository = userRepository;
		this.transportRegistry = transportRegistry;
		this.nettyRuntime = nettyRuntime;
	}

	@Transactional(readOnly = true)
	public List<TargetDtos.TargetResponse> list(Long userId) {
		List<TargetDtos.TargetResponse> result = new ArrayList<>();
		for (SendTarget target : targetRepository.findByOwnerIdOrderByHostAscPortAsc(userId)) {
			result.add(toResponse(target));
		}
		return result;
	}

	@Transactional
	public TargetDtos.TargetResponse create(Long userId, TargetDtos.TargetRequest request) {
		validate(request);
		String protocol = request.protocol().toUpperCase();
		String host = request.host().trim();
		requireUniqueEndpoint(userId, protocol, host, request.port());

		User owner = userRepository.getReferenceById(userId);
		SendTarget target = new SendTarget(owner, protocol, host, request.port());
		apply(target, request);
		return toResponse(targetRepository.save(target));
	}

	@Transactional
	public TargetDtos.TargetResponse update(Long userId, Long targetId, TargetDtos.TargetRequest request) {
		validate(request);
		SendTarget target = requireOwned(userId, targetId);
		String protocol = request.protocol().toUpperCase();
		String host = request.host().trim();
		boolean endpointChanged = !target.getProtocol().equals(protocol)
				|| !target.getHost().equals(host) || target.getPort() != request.port();
		if (endpointChanged) {
			requireUniqueEndpoint(userId, protocol, host, request.port());
		}
		target.setProtocol(protocol);
		target.setHost(host);
		target.setPort(request.port());
		target.refreshName();
		apply(target, request);
		return toResponse(target);
	}

	private void requireUniqueEndpoint(Long userId, String protocol, String host, int port) {
		if (targetRepository.existsByOwnerIdAndProtocolAndHostAndPort(userId, protocol, host, port)) {
			throw TesterException.conflict("이미 등록된 대상입니다: " + protocol + " " + host + ":" + port);
		}
	}

	@Transactional
	public void delete(Long userId, Long targetId) {
		SendTarget target = requireOwned(userId, targetId);
		long jobCount = jobRepository.countByTargetId(targetId);
		if (jobCount > 0) {
			throw TesterException.conflict(
					"이 대상을 사용하는 전송 작업이 " + jobCount + "개 있습니다. 먼저 작업을 삭제하세요.");
		}
		targetRepository.delete(target);
	}

	/**
	 * 실제로 연결해 본다. 잘못된 호스트/포트를 전송 시작 전에 잡아내기 위한 기능이다.
	 * <p>UDP 는 연결형이 아니므로 소켓 생성 성공 여부만 확인된다(도달 보장 아님).
	 */
	@Transactional(readOnly = true)
	public TargetDtos.TestResult test(Long userId, Long targetId) {
		SendTarget target = requireOwned(userId, targetId);
		TargetSpec spec = toSpec(target);
		long started = System.nanoTime();
		try (LogSender sender = transportRegistry.create(spec, 1, nettyRuntime)) {
			sender.connect();
			long latency = (System.nanoTime() - started) / 1_000_000;
			String message = Protocol.UDP.equalsIgnoreCase(target.getProtocol())
					? "UDP 소켓 생성 성공 (UDP 는 비연결형이라 수신 여부까지는 확인할 수 없습니다)"
					: "연결 성공";
			return new TargetDtos.TestResult(true, latency, message);
		}
		catch (Exception e) {
			return new TargetDtos.TestResult(false, (System.nanoTime() - started) / 1_000_000,
					e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	public TargetSpec toSpec(SendTarget target) {
		return new TargetSpec(
				target.getName(),
				target.getProtocol(),
				target.getHost(),
				target.getPort(),
				target.getConnections(),
				target.getFraming(),
				target.getBatchBytes(),
				target.getSendBufferBytes(),
				target.getConnectTimeoutMs());
	}

	@Transactional(readOnly = true)
	public SendTarget requireOwned(Long userId, Long targetId) {
		SendTarget target = targetRepository.findById(targetId)
				.orElseThrow(() -> TesterException.notFound("전송 대상", targetId));
		if (!target.getOwner().getId().equals(userId)) {
			throw TesterException.forbidden("다른 사용자의 전송 대상입니다");
		}
		return target;
	}

	private void validate(TargetDtos.TargetRequest request) {
		if (!transportRegistry.supports(request.protocol())) {
			throw TesterException.invalid("지원하지 않는 프로토콜입니다: " + request.protocol());
		}
	}

	private void apply(SendTarget target, TargetDtos.TargetRequest request) {
		// 커넥션 수는 입력받지 않는다. 0 이면 송신기가 워커 수에 맞춰 알아서 잡는다.
		target.setConnections(0);
		target.setFraming(request.framing() == null ? Framing.NEWLINE : request.framing());
		if (request.batchBytes() != null && request.batchBytes() > 0) {
			target.setBatchBytes(request.batchBytes());
		}
		if (request.sendBufferBytes() != null && request.sendBufferBytes() > 0) {
			target.setSendBufferBytes(request.sendBufferBytes());
		}
		if (request.connectTimeoutMs() != null && request.connectTimeoutMs() > 0) {
			target.setConnectTimeoutMs(request.connectTimeoutMs());
		}
	}

	private TargetDtos.TargetResponse toResponse(SendTarget target) {
		return new TargetDtos.TargetResponse(
				target.getId(), target.getName(), target.getProtocol(), target.getHost(), target.getPort(),
				target.getConnections(), target.getFraming(), target.getBatchBytes(),
				target.getSendBufferBytes(), target.getConnectTimeoutMs(),
				target.endpoint(), target.getUpdatedAt());
	}
}
