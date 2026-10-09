import { describe, it, expect } from "vitest";
import {
  SORT_OPTIONS,
  bestHref,
  parseBestPeriod,
  parseCategory,
  parseSort,
  sortHref,
} from "./storefront";

describe("parseSort / parseBestPeriod / parseCategory", () => {
  it("성공: 허용된 값은 그대로 쓴다 (배열이면 첫 값)", () => {
    expect(parseSort("price_asc")).toBe("price_asc");
    expect(parseSort(["rating", "sales"])).toBe("rating");
    expect(parseSort("discount")).toBe("discount");
    expect(parseBestPeriod("monthly")).toBe("monthly");
    expect(parseCategory(["상의", "바지"])).toBe("상의");
  });

  it("실패: 없거나 알 수 없는 값은 기본값으로 돌아간다", () => {
    expect(parseSort(undefined)).toBe("popular");
    expect(parseSort("discounts")).toBe("popular");
    expect(parseSort("DISCOUNT")).toBe("popular");
    expect(parseBestPeriod("yearly")).toBe("realtime");
    expect(parseCategory("")).toBeUndefined();
  });
});

describe("href 만들기", () => {
  it("성공: 정렬 링크는 category를 유지하고 #all로 끝난다", () => {
    expect(sortHref("newest")).toBe("/?sort=newest#all");
    expect(sortHref("price_desc", { category: "원피스/스커트" })).toBe(
      `/?category=${encodeURIComponent("원피스/스커트")}&sort=price_desc#all`
    );
  });

  it("성공: 베스트 탭 링크는 기간과 #best를 가진다", () => {
    expect(bestHref("weekly")).toBe("/?best=weekly#best");
  });

  it("성공: 정렬 탭은 8개이고 기준 쇼핑몰 순서(할인율순 포함)다", () => {
    expect(SORT_OPTIONS.map((o) => o.label)).toEqual([
      "인기도순",
      "최신 등록순",
      "낮은 가격순",
      "높은 가격순",
      "할인율순",
      "누적 판매순",
      "리뷰 많은순",
      "평점 높은순",
    ]);
    expect(sortHref("discount", { category: "상의", best: "weekly" })).toBe(
      `/?category=${encodeURIComponent("상의")}&sort=discount&best=weekly#all`
    );
  });

  it("성공: 정렬 링크는 best를, 베스트 링크는 sort를 유지한다", () => {
    expect(sortHref("price_asc", { best: "weekly" })).toBe("/?sort=price_asc&best=weekly#all");
    expect(bestHref("weekly", { sort: "price_asc" })).toBe("/?sort=price_asc&best=weekly#best");
    expect(sortHref("sales", { category: "상의", best: "monthly" })).toBe(
      `/?category=${encodeURIComponent("상의")}&sort=sales&best=monthly#all`
    );
  });

  it("실패: 유지할 값이 없으면 기본 링크와 같다", () => {
    expect(sortHref("newest", {})).toBe("/?sort=newest#all");
    expect(bestHref("monthly", {})).toBe("/?best=monthly#best");
  });

  it("실패: 목록에 없는 category는 없는 것으로 본다", () => {
    expect(parseCategory("상의")).toBe("상의");
    expect(parseCategory("<script>alert(1)</script>")).toBeUndefined();
    expect(parseCategory(["없는분류", "상의"])).toBeUndefined();
  });
});
