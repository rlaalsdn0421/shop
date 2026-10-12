import { describe, it, expect } from "vitest";
import { shippingFeeFor } from "./shipping";

describe("shippingFeeFor", () => {
  it("성공: 50,000원 미만이면 3,500원이다", () => {
    expect(shippingFeeFor(49999)).toBe(3500);
    expect(shippingFeeFor(0)).toBe(3500);
  });

  it("성공: 50,000원 이상이면 무료다 (경계 포함)", () => {
    expect(shippingFeeFor(50000)).toBe(0);
    expect(shippingFeeFor(50001)).toBe(0);
  });
});
