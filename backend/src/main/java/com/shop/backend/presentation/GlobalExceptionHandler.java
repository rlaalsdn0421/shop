package com.shop.backend.presentation;

import com.shop.backend.domain.DomainException;
import com.shop.backend.domain.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Business-rule violations map to 400, except a missing product which maps to 404 (Spring
 * picks the most specific handler, so this takes precedence over the DomainException one below).
 * Anything unexpected maps to 500 with a per-area message, matching the original API contract.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(HttpServletRequest request) {
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
