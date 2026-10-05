package com.shop.backend.domain.error;

public class DuplicateUsernameException extends DomainException {
    public DuplicateUsernameException() {
        super("이미 사용 중인 아이디입니다.");
    }
}
