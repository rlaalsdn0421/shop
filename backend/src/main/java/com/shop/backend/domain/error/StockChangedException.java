package com.shop.backend.domain.error;

/** The stock changed (an order, a cancel) between the admin loading the edit form and saving it; HTTP 409, nothing saved. */
public class StockChangedException extends DomainException {
    public static final String CODE = "STOCK_CHANGED";

    public StockChangedException() {
        super("그 사이 재고가 바뀌었어요. 새로고침한 뒤 다시 입력해 주세요.");
    }

    public String getCode() {
        return CODE;
    }
}
