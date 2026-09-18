package com.shop.backend.domain;

public class InvalidCredentialsException extends DomainException {
    public InvalidCredentialsException() {
        super("이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
