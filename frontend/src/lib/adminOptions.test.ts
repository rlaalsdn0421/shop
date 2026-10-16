import { describe, it, expect } from "vitest";
import { MAX_PRICE } from "@/lib/price";
import { buildOptionLines, cleanOptionText, summarizeOptions, validateOptionRows, type OptionRow } from "./adminOptions";

const row = (key: number, over: Partial<OptionRow> = {}): OptionRow => ({
  key,
  colorName: "화이트",
  colorHex: "",
  sizeLabel: "M",
  price: "10000",
  originalPrice: "",
  stock: "5",
  ...over,
});
const both = { useColor: true, useSize: true };

describe("cleanOptionText", () => {
  it("성공: 앞뒤 공백을 지우고 연속 공백을 하나로, 분해된 한글은 합친다", () => {
    expect(cleanOptionText("  화이트   크림 ")).toBe("화이트 크림");
    expect(cleanOptionText("한")).toBe("한");
  });
});

describe("validateOptionRows", () => {
  it("성공: 올바른 행은 오류가 없다", () => {
    expect(validateOptionRows([row(1), row(2, { sizeLabel: "L", originalPrice: "12000", stock: "0", price: "0" })], both)).toEqual([{}, {}]);
  });

  it("실패: 켠 차원의 이름이 비면 필수 오류, 끈 차원은 검사하지 않는다", () => {
    const [e] = validateOptionRows([row(1, { colorName: " ", sizeLabel: "" })], both);
    expect(e.colorName).toBe("색상 이름을 입력해 주세요.");
    expect(e.sizeLabel).toBe("사이즈를 입력해 주세요.");
    expect(validateOptionRows([row(1, { colorName: "" })], { useColor: false, useSize: true })).toEqual([{}]);
  });

  it("실패: 30자 초과, 쓸 수 없는 문자, 글자·숫자 없음을 막는다", () => {
    expect(validateOptionRows([row(1, { sizeLabel: "가".repeat(31) })], both)[0].sizeLabel).toMatch(/30자 이하/);
    expect(validateOptionRows([row(1, { sizeLabel: "가".repeat(30) })], both)[0].sizeLabel).toBeUndefined();
    for (const bad of ["M\tL", "M​L", "M L", "M\nL", "M\u0000"]) {
      expect(validateOptionRows([row(1, { sizeLabel: bad })], both)[0].sizeLabel).toBe("옵션 이름에 쓸 수 없는 문자가 있어요.");
    }
    expect(validateOptionRows([row(1, { colorName: "---" })], both)[0].colorName).toMatch(/글자나 숫자/);
  });

  it("실패: 가격과 재고는 0 이상의 정수여야 한다", () => {
    const [e] = validateOptionRows([row(1, { price: "-1", stock: "1.5" })], both);
    expect(e.price).toMatch(/0 이상의 정수/);
    expect(e.stock).toMatch(/0 이상의 정수/);
    expect(validateOptionRows([row(1, { price: "", stock: "" })], both)[0]).toMatchObject({ price: expect.any(String), stock: expect.any(String) });
    expect(validateOptionRows([row(1, { price: String(MAX_PRICE + 1) })], both)[0].price).toMatch(/이하로/);
    expect(validateOptionRows([row(1, { price: String(MAX_PRICE), stock: String(MAX_PRICE) })], both)).toEqual([{}]);
  });

  it("실패: 정가는 비어 있거나 판매가보다 커야 한다", () => {
    expect(validateOptionRows([row(1, { originalPrice: "10000" })], both)[0].originalPrice).toBe("정가는 판매가보다 커야 해요.");
    expect(validateOptionRows([row(1, { originalPrice: "9000" })], both)[0].originalPrice).toBe("정가는 판매가보다 커야 해요.");
    expect(validateOptionRows([row(1, { originalPrice: "10001" })], both)).toEqual([{}]);
    expect(validateOptionRows([row(1, { originalPrice: "abc" })], both)[0].originalPrice).toMatch(/정수/);
  });

  it("실패: 같은 조합은 대소문자·공백 차이를 무시하고 뒤 행에 표시한다", () => {
    const errors = validateOptionRows([row(1, { colorName: "White", sizeLabel: "m" }), row(2, { colorName: " white ", sizeLabel: "M" })], both);
    expect(errors[0]).toEqual({});
    expect(errors[1].colorName).toBe("같은 색상과 사이즈 조합의 옵션이 이미 있어요.");
  });

  it("성공: 조합이 하나라도 다르면 중복이 아니고, 끈 차원은 비교하지 않는다", () => {
    expect(validateOptionRows([row(1), row(2, { sizeLabel: "L" })], both)).toEqual([{}, {}]);
    const sizeOnly = validateOptionRows([row(1, { colorName: "a" }), row(2, { colorName: "b" })], { useColor: false, useSize: true });
    expect(sizeOnly[1].sizeLabel).toMatch(/이미 있어요/);
  });
});

describe("summarizeOptions", () => {
  it("성공: 개수, 최저가, 재고 합계를 센다", () => {
    expect(summarizeOptions([row(1, { price: "30000", stock: "2" }), row(2, { price: "20000", stock: "3" })])).toEqual({ count: 2, lowest: 20000, stock: 5 });
  });

  it("실패: 숫자가 아닌 칸은 건너뛰고 가격이 없으면 최저가는 null이다", () => {
    expect(summarizeOptions([row(1, { price: "", stock: "abc" })])).toEqual({ count: 1, lowest: null, stock: 0 });
  });
});

describe("buildOptionLines", () => {
  it("성공: 켠 차원만 넣고 정가는 있을 때만, 이름은 정리해서 보낸다", () => {
    const rows = [row(1, { colorName: " 화이트  크림 ", colorHex: "#ffffff", originalPrice: "12000" }), row(2, { colorHex: "" })];
    expect(buildOptionLines(rows, both)).toEqual([
      { colorName: "화이트 크림", colorHex: "#ffffff", sizeLabel: "M", price: 10000, originalPrice: 12000, stock: 5 },
      { colorName: "화이트", sizeLabel: "M", price: 10000, stock: 5 },
    ]);
    const sizeOnly = buildOptionLines([row(1)], { useColor: false, useSize: true });
    expect(Object.keys(sizeOnly[0])).toEqual(["sizeLabel", "price", "stock"]);
    const colorOnly = buildOptionLines([row(1, { colorHex: "#000000" })], { useColor: true, useSize: false });
    expect(Object.keys(colorOnly[0])).toEqual(["colorName", "colorHex", "price", "stock"]);
  });
});
