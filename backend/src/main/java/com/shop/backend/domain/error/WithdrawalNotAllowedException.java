package com.shop.backend.domain.error;

/** ADMIN/SELLER accounts cannot withdraw; mapped to HTTP 403 at the presentation layer. */
public class WithdrawalNotAllowedException extends DomainException {
    public WithdrawalNotAllowedException() {
        super("관리자·판매자 계정은 탈퇴할 수 없어요.");
    }
}
