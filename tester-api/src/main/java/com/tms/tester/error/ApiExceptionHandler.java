package com.tms.tester.error;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.tms.tester.common.ErrorCode;
import com.tms.tester.common.TesterException;
import com.tms.tester.generator.TemplateSyntaxException;

/** 예외를 RFC7807 problem+json 으로 변환한다. */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(TesterException.class)
	public ProblemDetail handleTesterException(TesterException e) {
		HttpStatus status = switch (e.getCode()) {
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case CONFLICT -> HttpStatus.CONFLICT;
			case FORBIDDEN -> HttpStatus.FORBIDDEN;
			case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
			case INVALID, ILLEGAL_STATE -> HttpStatus.BAD_REQUEST;
			case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
		return problem(status, e.getMessage(), e.getCode().name());
	}

	@ExceptionHandler(TemplateSyntaxException.class)
	public ProblemDetail handleTemplateSyntax(TemplateSyntaxException e) {
		ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, e.getMessage(), "TEMPLATE_SYNTAX");
		detail.setProperty("position", e.getPosition());
		return detail;
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.reduce((a, b) -> a + ", " + b)
				.orElse("입력값이 올바르지 않습니다");
		return problem(HttpStatus.BAD_REQUEST, message, ErrorCode.INVALID.name());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
		return problem(HttpStatus.BAD_REQUEST, e.getMessage(), ErrorCode.INVALID.name());
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception e) {
		log.error("처리되지 않은 오류", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR,
				"서버 오류가 발생했습니다: " + e.getMessage(), ErrorCode.INTERNAL.name());
	}

	private ProblemDetail problem(HttpStatus status, String message, String code) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
		detail.setType(URI.create("urn:tester:error:" + code.toLowerCase()));
		detail.setProperty("code", code);
		return detail;
	}
}
