import { describe, it, expect } from "vitest";
import type { ProductOption } from "@/lib/api";
import { distinctValues, lowestPrice, optionLabel, optionsOf, resolveOption, safeHex } from "./options";

const o = (id: string, colorName: string | null, sizeLabel: string | null, price = 1000, stock = 1): ProductOption => ({
  id,
  colorName,
  colorHex: null,
  sizeLabel,
  price,
  stock,
});

describe("optionsOf", () => {
  it("성공: 배열은 그대로 돌려준다", () => {
    const list = [o("a", "화이트", "M")];
    expect(optionsOf(list)).toBe(list);
  });

  it.each([[undefined], [null], ["x"], [{}]])("실패: %j 같은 값(구버전 응답)은 빈 목록이다", (value) => {
    expect(optionsOf(value)).toEqual([]);
  });
});

describe("optionLabel", () => {
  it.each([
    [{ colorName: "화이트", sizeLabel: "M" }, "화이트 / M"],
    [{ colorName: "화이트", sizeLabel: null }, "화이트"],
    [{ colorName: null, sizeLabel: "M" }, "M"],
    [{ colorName: null, sizeLabel: null }, ""],
  ])("성공: %j 의 이름", (input, expected) => {
    expect(optionLabel(input)).toBe(expected);
  });
});

describe("resolveOption", () => {
  const list = [o("w-s", "화이트", "S"), o("w-m", "화이트", "M"), o("b-m", "블랙", "M")];

  it("성공: 색상과 사이즈를 모두 고르면 그 옵션 하나가 정해진다", () => {
    expect(resolveOption(list, "화이트", "M")?.id).toBe("w-m");
    expect(resolveOption(list, "블랙", "M")?.id).toBe("b-m");
  });

  it("실패: 하나라도 안 골랐거나 없는 조합이면 정해지지 않는다", () => {
    expect(resolveOption(list, "화이트", null)).toBeNull();
    expect(resolveOption(list, null, "M")).toBeNull();
    expect(resolveOption(list, "블랙", "S")).toBeNull();
  });

  it("성공: 상품에 없는 차원은 무시한다", () => {
    expect(resolveOption([o("a", null, "S"), o("b", null, "M")], null, "M")?.id).toBe("b");
    expect(resolveOption([o("a", "화이트", null)], "화이트", null)?.id).toBe("a");
  });

  it("실패: 옵션이 없으면 아무것도 정해지지 않는다", () => {
    expect(resolveOption([], null, null)).toBeNull();
  });
});

describe("lowestPrice, distinctValues, safeHex", () => {
  it("성공: 최저가는 옵션 중 가장 싼 값이고 옵션이 없으면 기본값이다", () => {
    expect(lowestPrice([o("a", null, "S", 5000), o("b", null, "M", 3000)], 9999)).toBe(3000);
    expect(lowestPrice([], 9999)).toBe(9999);
  });

  it("성공: 중복 없이 처음 나온 순서로 모은다", () => {
    expect(distinctValues([o("1", "화이트", "S"), o("2", "블랙", "S"), o("3", "화이트", "M")], "colorName")).toEqual(["화이트", "블랙"]);
    expect(distinctValues([o("1", null, "S")], "colorName")).toEqual([]);
  });

  it("성공: #RRGGBB만 통과하고 나머지는 null이다", () => {
    expect(safeHex("#a1B2c3")).toBe("#a1B2c3");
    for (const bad of ["red", "#fff", "#12345g", "url(x)", "", null, undefined, "#1234567"]) expect(safeHex(bad)).toBeNull();
  });
});
