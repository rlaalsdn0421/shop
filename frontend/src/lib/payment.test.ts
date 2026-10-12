import { describe, it, expect, vi, beforeEach } from "vitest";
import { FAKE_PAYMENT_ENV, clearPaymentEnv, stubPaymentEnv } from "@/test-utils/paymentEnv";

vi.mock("@portone/browser-sdk/v2", () => ({ requestPayment: vi.fn() }));

import * as PortOne from "@portone/browser-sdk/v2";
import { getPaymentConfig, orderNameFor, payOrder, remainingLabel, statusLabel, type PayMethodId } from "./payment";

const requestPayment = vi.mocked(PortOne.requestPayment);

describe("payOrder", () => {
  const pay = (id: PayMethodId, phone = "010-0000-0000") =>
    payOrder({
      orderId: "o1",
      orderName: "셔츠",
      totalAmount: 1000,
      method: { id, label: id, channelKey: "channel-test" },
      storeId: "store-test-0000",
      customer: { name: "홍길동", phone },
      token: "fake-token",
    });
  const sent = () => requestPayment.mock.calls[0][0] as unknown as Record<string, unknown>;

  beforeEach(() => {
    requestPayment.mockReset();
  });

  it("성공: 연락처는 숫자만 보낸다", async () => {
    requestPayment.mockResolvedValue(undefined);
    await pay("CARD", "+82 (10) 0000-0000");
    expect(sent().customer).toEqual({ fullName: "홍길동", phoneNumber: "821000000000" });
  });

  it("실패: 숫자가 없는 연락처는 phoneNumber를 빼고 이름만 보낸다", async () => {
    requestPayment.mockResolvedValue(undefined);
    await pay("CARD", "asd");
    expect(sent().customer).toEqual({ fullName: "홍길동" });
  });

  it.each(["NAVERPAY", "TOSSPAY"] as const)("성공: %s 는 availablePayMethods CARD를 보낸다", async (id) => {
    requestPayment.mockResolvedValue(undefined);
    await pay(id);
    expect(sent().easyPay).toEqual({ easyPayProvider: id, availablePayMethods: ["CARD"] });
  });

  it("실패: 카카오페이는 availablePayMethods를 보내지 않고 카드는 easyPay가 없다", async () => {
    requestPayment.mockResolvedValue(undefined);
    await pay("KAKAOPAY");
    expect(sent().easyPay).toEqual({ easyPayProvider: "KAKAOPAY" });
    requestPayment.mockClear();
    await pay("CARD");
    expect(sent()).not.toHaveProperty("easyPay");
  });

  it("실패: SDK가 던지면 오류를 콘솔에 남기고 일반 문구를 돌려준다", async () => {
    const thrown = new Error("phoneNumber 필드가 NON_EMPTY_STRING 조건을 만족하지 않습니다");
    requestPayment.mockRejectedValue(thrown);
    const log = vi.spyOn(console, "error").mockImplementation(() => {});
    const result = await pay("CARD");
    expect(result).toEqual({ kind: "failed", message: "결제창을 열지 못했어요. 잠시 후 다시 시도해 주세요." });
    expect(log).toHaveBeenCalledWith("[payment] requestPayment threw", thrown);
    expect(JSON.stringify(log.mock.calls)).not.toContain("fake-token");
    log.mockRestore();
  });
});

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

  it("실패: 모르는 상태나 없는 상태는 빈 문자열이다", () => {
    expect(statusLabel("CANCELLED")).toBe("");
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
