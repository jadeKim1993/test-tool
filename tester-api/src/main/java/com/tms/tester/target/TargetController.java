package com.tms.tester.target;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tms.tester.security.AuthUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/targets")
public class TargetController {

	private final TargetService targetService;

	public TargetController(TargetService targetService) {
		this.targetService = targetService;
	}

	@GetMapping
	public List<TargetDtos.TargetResponse> list(@AuthenticationPrincipal AuthUser user) {
		return targetService.list(user.id());
	}

	@PostMapping
	public ResponseEntity<TargetDtos.TargetResponse> create(
			@AuthenticationPrincipal AuthUser user,
			@Valid @RequestBody TargetDtos.TargetRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(targetService.create(user.id(), request));
	}

	@PutMapping("/{id}")
	public TargetDtos.TargetResponse update(
			@AuthenticationPrincipal AuthUser user,
			@PathVariable Long id,
			@Valid @RequestBody TargetDtos.TargetRequest request) {
		return targetService.update(user.id(), id, request);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		targetService.delete(user.id(), id);
		return ResponseEntity.noContent().build();
	}

	/** 전송 시작 전에 대상이 살아있는지 확인한다. */
	@PostMapping("/{id}/test")
	public TargetDtos.TestResult test(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
		return targetService.test(user.id(), id);
	}
}
