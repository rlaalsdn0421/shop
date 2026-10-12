import { describe, it, expect, beforeEach } from "vitest";
import { attemptIsFor, readAttempt, saveAttempt } from "./orderAttempt";

beforeEach(() => sessionStorage.clear());

describe("주문 시도 저장", () => {
  it("성공: orderId까지 저장하고 읽는다", () => {
    saveAttempt({ key: "k", fingerprintHash: "h", orderId: "o1" });
    expect(readAttempt()).toEqual({ key: "k", fingerprintHash: "h", orderId: "o1" });
  });

  it("성공: orderId가 없는 예전 항목도 그대로 읽는다", () => {
    sessionStorage.setItem("shop-order-attempt", JSON.stringify({ key: "k", fingerprintHash: "h" }));
    expect(readAttempt()).toEqual({ key: "k", fingerprintHash: "h" });
  });

  it("실패: orderId가 문자열이 아니면 버리고, 알 수 없는 다른 필드도 읽지 않는다", () => {
    sessionStorage.setItem("shop-order-attempt", JSON.stringify({ key: "k", fingerprintHash: "h", orderId: 7, name: "홍길동" }));
    expect(readAttempt()).toEqual({ key: "k", fingerprintHash: "h" });
  });
});

describe("attemptIsFor", () => {
  it("성공: 저장된 orderId와 같은 주문(대소문자 무시)이면 true다", () => {
    saveAttempt({ key: "k", fingerprintHash: "h", orderId: "ABC-1" });
    expect(attemptIsFor("abc-1")).toBe(true);
  });

  it("실패: 다른 주문이거나 orderId를 모르거나 저장된 것이 없으면 false다", () => {
    saveAttempt({ key: "k", fingerprintHash: "h", orderId: "o1" });
    expect(attemptIsFor("o2")).toBe(false);
    saveAttempt({ key: "k", fingerprintHash: "h" });
    expect(attemptIsFor("o1")).toBe(false);
    saveAttempt(null);
    expect(attemptIsFor("o1")).toBe(false);
  });
});
