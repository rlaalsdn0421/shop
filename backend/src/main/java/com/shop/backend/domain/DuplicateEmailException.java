package com.shop.backend.domain;

public class DuplicateEmailException extends DomainException {
    public DuplicateEmailException() {
        super("이미 사용 중인 이메일입니다.");
    }
}
