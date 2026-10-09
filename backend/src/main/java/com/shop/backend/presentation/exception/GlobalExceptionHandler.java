package com.shop.backend.presentation.exception;

import com.shop.backend.domain.error.DomainException;
import com.shop.backend.domain.error.DuplicateEmailException;
import com.shop.backend.domain.error.DuplicateReviewException;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.TooManyRequestsException;
import com.shop.backend.presentation.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Business-rule violations map to 400, except a missing product which maps to 404 (Spring
 * picks the most specific handler, so this takes precedence over the DomainException one below).
 * Anything unexpected maps to 500 with a per-area message, matching the original API contract.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateReviewException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateReview(DuplicateReviewException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.getMessage()));
    }

    // Malformed client requests: no stack trace, these are not server errors.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // message and value omitted on purpose: Jackson echoes the offending input, which may be a birth date
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        logger.debug("Unreadable request body: {}", cause.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("요청 본문을 읽을 수 없습니다."));
    }

    // e.g. ?page=abc. The rejected value is never echoed or logged (only the parameter name).
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        logger.debug("Invalid request parameter: {}", ex.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("요청 값의 형식이 올바르지 않아요."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        logger.debug("Method not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ErrorResponse("지원하지 않는 요청 방식입니다."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        logger.debug("Media type not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new ErrorResponse("지원하지 않는 콘텐츠 타입입니다."));
    }

    // PostgreSQL's message embeds "Failing row contains (...)" (birth date, password hash), so never log
    // the message or pass the exception as throwable: only the class, SQLSTATE and constraint name.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(HttpServletRequest request,
                                                             DataIntegrityViolationException ex) {
        String sqlState = null;
        String constraint = null;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                sqlState = cve.getSQLState();
                constraint = cve.getConstraintName();
            } else if (sqlState == null && t instanceof SQLException sql) {
                sqlState = sql.getSQLState();
            }
        }
        logger.error("Data integrity violation while processing {}: {} sqlState={} constraint={}",
                request.getRequestURI(), ex.getClass().getSimpleName(), sqlState, constraint);
        return internalError(request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(HttpServletRequest request, Exception ex) {
        logger.error("Unhandled exception while processing {}", request.getRequestURI(), ex);
        return internalError(request);
    }

    private static ResponseEntity<ErrorResponse> internalError(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String message;
        if (uri.startsWith("/api/orders")) {
            message = "주문 처리 중 오류가 발생했습니다.";
        } else if (uri.startsWith("/api/admin/products")) {
            message = "상품 등록 중 오류가 발생했습니다.";
        } else {
            message = "요청 처리 중 오류가 발생했습니다.";
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(message));
    }
}
