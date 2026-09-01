package com.kssasarma.confluencebot.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Centralized exception handling using RFC 9457 ProblemDetail (built into Spring 6+).
 * All errors return a consistent JSON structure.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation Failed");
        problem.setType(URI.create("urn:confluencebot:error:validation"));
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request body");
        problem.setDetail(detail);
        return problem;
    }

    @ExceptionHandler(ConfluenceException.class)
    public ProblemDetail handleConfluence(ConfluenceException ex) {
        log.error("Confluence API error: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setTitle("Confluence API Error");
        problem.setType(URI.create("urn:confluencebot:error:confluence"));
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(IngestionException.class)
    public ProblemDetail handleIngestion(IngestionException ex) {
        log.error("Ingestion error: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Ingestion Failed");
        problem.setType(URI.create("urn:confluencebot:error:ingestion"));
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("urn:confluencebot:error:internal"));
        problem.setDetail("An unexpected error occurred. Please try again later.");
        return problem;
    }
}
