package com.magjong.shop.order_service.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	static final String TRACE_ID_KEY = "traceId";

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
		problem.setTitle("Invalid request");
		problem.setProperty("errors", fieldErrors(ex));
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		logHandled(ex, statusCode, describe(request));
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem) {
			addTraceId(problem);
		}
		return response;
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
				"An unexpected error occurred");
		problem.setTitle("Internal server error");
		addTraceId(problem);
		return problem;
	}

	private static void logHandled(Exception ex, HttpStatusCode statusCode, String request) {
		if (statusCode.is4xxClientError()) {
			log.info("Rejected {} with {}: {}", request, statusCode.value(), rejectionReason(ex));
		}
		else {
			log.warn("Failed {} with {}", request, statusCode.value(), ex);
		}
	}

	/** Names the problem without echoing request content, which may hold arbitrary client input. */
	private static String rejectionReason(Exception ex) {
		if (ex instanceof MethodArgumentNotValidException invalid) {
			return "invalid fields " + invalid.getBindingResult().getFieldErrors().stream()
					.map(FieldError::getField)
					.distinct()
					.toList();
		}
		return ex.getClass().getSimpleName();
	}

	private static String describe(WebRequest request) {
		if (request instanceof ServletWebRequest servletRequest) {
			return servletRequest.getRequest().getMethod() + " " + servletRequest.getRequest().getRequestURI();
		}
		return request.getDescription(false);
	}

	private static void addTraceId(ProblemDetail problem) {
		String traceId = MDC.get(TRACE_ID_KEY);
		if (traceId != null) {
			problem.setProperty(TRACE_ID_KEY, traceId);
		}
	}

	private static List<Map<String, String>> fieldErrors(MethodArgumentNotValidException ex) {
		return ex.getBindingResult().getFieldErrors().stream()
				.map(error -> Map.of(
						"field", error.getField(),
						"message", String.valueOf(error.getDefaultMessage())))
				.toList();
	}
}
