package com.kssasarma.confluencebot.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;

/**
 * Centralized exception handling using RFC 9457 ProblemDetail (built into Spring 6+).
 * All errors return a consistent JSON structure.
 *
 * Anything not mapped here becomes a 500 with a generic message, so a handler is added for every
 * failure the API can produce on purpose — a wrong password or an unknown conversation is a
 * client error, and reporting it as "Internal Server Error" hides real faults in the noise.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request body");
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", "validation", detail);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ProblemDetail handleConstraintViolation(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", "validation", ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Malformed request body: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Malformed Request", "malformed-request",
                "The request body could not be parsed.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Rejected request: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Invalid Request", "invalid-request", ex.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        log.debug("Authentication failed: {}", ex.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "Authentication Failed", "authentication",
                ex.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        log.debug("Refresh rejected: {}", ex.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "Authentication Failed", "authentication",
                ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        log.debug("Access denied: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, "Access Denied", "access-denied",
                "You do not have permission to perform this action.");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", "not-found", ex.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ProblemDetail handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.warn("No representation matches the Accept header: {}", ex.getMessage());
        return problem(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable", "not-acceptable",
                "This endpoint cannot produce any of the requested media types.");
    }

    @ExceptionHandler(LlmUnavailableException.class)
    public ProblemDetail handleLlmUnavailable(LlmUnavailableException ex) {
        log.error("LLM unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "AI Service Unavailable", "llm-unavailable",
                "The AI service is temporarily unavailable. Please try again in a moment.");
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ProblemDetail handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        log.warn("Streaming response timed out before completion");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Request Timed Out", "timeout",
                "The answer took too long to generate. Please try again.");
    }

    @ExceptionHandler(ConfluenceException.class)
    public ProblemDetail handleConfluence(ConfluenceException ex) {
        log.error("Confluence API error: {}", ex.getMessage(), ex);
        return problem(HttpStatus.BAD_GATEWAY, "Confluence API Error", "confluence", ex.getMessage());
    }

    @ExceptionHandler(IngestionException.class)
    public ProblemDetail handleIngestion(IngestionException ex) {
        log.error("Ingestion error: {}", ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Ingestion Failed", "ingestion", ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        log.debug("Static resource not found: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Not Found", "not-found", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "internal",
                "An unexpected error occurred. Please try again later.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setType(URI.create("urn:confluencebot:error:" + type));
        problem.setDetail(detail);
        return problem;
    }
}
