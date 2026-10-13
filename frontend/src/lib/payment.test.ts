import { describe, it, expect, vi, beforeEach } from "vitest";
import { FAKE_PAYMENT_ENV, clearPaymentEnv, stubPaymentEnv } from "@/test-utils/paymentEnv";

vi.mock("@portone/browser-sdk/v2", () => ({ requestPayment: vi.fn() }));

import { getPaymentConfig, orderNameFor, remainingLabel, statusLabel } from "./payment";

beforeEach(() => {
  vi.unstubAllEnvs();
  clearPaymentEnv();
});

describe("getPaymentConfig", () => {
  it("성공: 상점 ID와 채널 키가 모두 있으면 네 결제수단을 순서대로 돌려준다", () => {
    stubPaymentEnv(FAKE_PAYMENT_ENV);
    const config = getPaymentConfig();
    expect(config.ready && config.storeId).toBe("store-test-0000");
    expect(config.ready && config.methods.map((m) => m.id)).toEqual(["CARD", "KAKAOPAY", "NAVERPAY", "TOSSPAY"]);
  });

  it("성공: 채널 키가 있는 수단만 남기고, 값 앞뒤 공백은 지운다", () => {
    stubPaymentEnv({ NEXT_PUBLIC_PORTONE_STORE_ID: " store-test-0000 ", NEXT_PUBLIC_PORTONE_CHANNEL_KEY_TOSSPAY: " channel-x " });
    const config = getPaymentConfig();
    expect(config).toEqual({
      ready: true,
      storeId: "store-test-0000",
      methods: [{ id: "TOSSPAY", label: "토스페이", channelKey: "channel-x" }],
    });
  });

  it("실패: 상점 ID가 없거나 공백뿐이면 준비되지 않은 것이다", () => {
    stubPaymentEnv({ ...FAKE_PAYMENT_ENV, NEXT_PUBLIC_PORTONE_STORE_ID: "  " });
    expect(getPaymentConfig()).toEqual({ ready: false });
  });

  it("실패: 채널 키가 하나도 없으면 상점 ID가 있어도 준비되지 않은 것이다", () => {
    stubPaymentEnv({ NEXT_PUBLIC_PORTONE_STORE_ID: "store-test-0000" });
    expect(getPaymentConfig()).toEqual({ ready: false });
  });
});

describe("statusLabel", () => {
  it("성공: 알려진 상태는 한국어 문구로 바꾼다", () => {
    expect(statusLabel("PENDING_PAYMENT")).toBe("결제 대기");
    expect(statusLabel("PAID")).toBe("결제 완료");
  });

  it("성공: 취소·배송·환불 상태도 한국어 문구로 바꾸고 기존 문구는 그대로다", () => {
    expect(statusLabel("CANCELING")).toBe("취소 처리 중");
    expect(statusLabel("CANCELLED")).toBe("주문 취소됨(환불 완료)");
    expect(statusLabel("SHIPPED")).toBe("배송 중");
    expect(statusLabel("DELIVERED")).toBe("배송 완료");
    expect(statusLabel("REFUNDED")).toBe("환불 완료");
    expect(statusLabel("EXPIRED")).toBe("주문 만료(결제하지 않아 취소됨)");
    expect(statusLabel("PAYMENT_REVIEW")).toBe("확인 중(결제 확인 후 취소·환불을 안내해 드려요)");
  });

  it("실패: 모르는 상태나 없는 상태는 빈 문자열이다", () => {
    expect(statusLabel("SOMETHING_NEW")).toBe("");
    expect(statusLabel(undefined)).toBe("");
  });
});

describe("orderNameFor", () => {
  it("성공: 한 건은 상품명, 여러 건은 '외 N건'이다", () => {
    expect(orderNameFor(["셔츠"])).toBe("셔츠");
    expect(orderNameFor(["셔츠", "바지", "양말"])).toBe("셔츠 외 2건");
  });

  it("실패: 100자를 넘으면 자르고, 품목이 없으면 기본 이름이다", () => {
    expect(orderNameFor(["가".repeat(150)])).toHaveLength(100);
    expect(orderNameFor([])).toBe("주문");
  });
});

describe("remainingLabel", () => {
  const now = Date.parse("2026-10-12T00:00:00Z");

  it("성공: 남은 분을 올림해서 보여주고, 1분 이하는 '1분 이내'다", () => {
    expect(remainingLabel("2026-10-12T00:12:05Z", now)).toBe("13분");
    expect(remainingLabel("2026-10-12T00:20:00Z", now)).toBe("20분");
    expect(remainingLabel("2026-10-12T00:00:30Z", now)).toBe("1분 이내");
  });

  it("실패: 기한이 지났거나 없거나 잘못된 값이면 null이다", () => {
    expect(remainingLabel("2026-10-11T23:59:59Z", now)).toBeNull();
    expect(remainingLabel("2026-10-12T00:00:00Z", now)).toBeNull();
    expect(remainingLabel(null, now)).toBeNull();
    expect(remainingLabel(undefined, now)).toBeNull();
    expect(remainingLabel("not a date", now)).toBeNull();
  });
});
