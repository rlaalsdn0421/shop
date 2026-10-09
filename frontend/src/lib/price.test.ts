import { describe, it, expect } from "vitest";
import { discountView, formatWon, visibleHashtags } from "./price";

describe("formatWon", () => {
  it("성공: 천 단위 쉼표와 원을 붙인다", () => {
    expect(formatWon(12900)).toBe("12,900원");
    expect(formatWon(0)).toBe("0원");
  });
});

describe("discountView", () => {
  it("성공: 할인율과 정가가 있으면 둘 다 돌려준다", () => {
    expect(discountView({ price: 8000, originalPrice: 10000, discountRate: 20 })).toEqual({
      rate: 20,
      original: 10000,
    });
  });

  it("실패: 필드가 없거나 null이면(옛 백엔드) 할인 없음이다", () => {
    expect(discountView({ price: 8000 })).toEqual({ rate: null, original: null });
    expect(discountView({ price: 8000, originalPrice: null, discountRate: null })).toEqual({
      rate: null,
      original: null,
    });
  });

  it("실패: 할인율이 1 미만이면 보여주지 않고 정가도 숨긴다", () => {
    expect(discountView({ price: 9990, originalPrice: 10000, discountRate: 0 })).toEqual({
      rate: null,
      original: null,
    });
  });

  it("성공: 할인율 경계값 1과 100은 보여준다", () => {
    expect(discountView({ price: 100, originalPrice: 101, discountRate: 1 }).rate).toBe(1);
    expect(discountView({ price: 1, originalPrice: 2, discountRate: 100 }).rate).toBe(100);
  });

  it("실패: 정가가 판매가 이하이면 할인율도 숨긴다 (정가 없는 빨간 %는 없다)", () => {
    const none = { rate: null, original: null };
    expect(discountView({ price: 8000, originalPrice: 8000, discountRate: 5 })).toEqual(none);
    expect(discountView({ price: 8000, originalPrice: 7000, discountRate: 5 })).toEqual(none);
    expect(discountView({ price: 8000, discountRate: 5 })).toEqual(none);
    expect(discountView({ price: 8000, originalPrice: null, discountRate: 5 })).toEqual(none);
  });

  it("실패: 이상한 할인율(NaN, Infinity, 음수, 문자열, 100 초과)은 숨긴다", () => {
    const none = { rate: null, original: null };
    for (const rate of [NaN, Infinity, -5, 0, 150, 100.5, "20"]) {
      const bad = discountView({ price: 8000, originalPrice: 10000, discountRate: rate as number });
      expect(bad, String(rate)).toEqual(none);
    }
  });

  it("실패: 이상한 정가(NaN, Infinity, 문자열)는 숨긴다", () => {
    for (const original of [NaN, Infinity, "10000"]) {
      const bad = discountView({ price: 8000, originalPrice: original as number, discountRate: 20 });
      expect(bad, String(original)).toEqual({ rate: null, original: null });
    }
  });
});

describe("visibleHashtags", () => {
  it("성공: 배열이면 최대 3개까지만 돌려준다", () => {
    expect(visibleHashtags(["a", "b", "c", "d"])).toEqual(["a", "b", "c"]);
  });

  it("실패: undefined, null, 배열이 아닌 값, 빈 문자열은 무시한다", () => {
    expect(visibleHashtags(undefined)).toEqual([]);
    expect(visibleHashtags(null)).toEqual([]);
    expect(visibleHashtags("tag")).toEqual([]);
    expect(visibleHashtags(["", 3, "ok"])).toEqual(["ok"]);
  });
});
