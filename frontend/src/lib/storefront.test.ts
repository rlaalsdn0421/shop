import { describe, it, expect } from "vitest";
import {
  SORT_OPTIONS,
  bestHref,
  listingHref,
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

describe("listingHref (자기 페이지 목록 링크)", () => {
  it("성공: `${기준 경로}?${쿼리}` 이고 # 앵커가 없다", () => {
    expect(listingHref("/products", { sort: "newest" })).toBe("/products?sort=newest");
    expect(listingHref("/best", { best: "weekly" })).toBe("/best?best=weekly");
    expect(listingHref("/best", { sort: "price_asc", best: "monthly" })).toBe("/best?sort=price_asc&best=monthly");
    expect(listingHref("/products", { sort: "newest" })).not.toContain("#");
  });

  it("실패: undefined 와 빈 문자열 값은 쿼리에서 빠진다", () => {
    expect(listingHref("/products", { category: undefined, best: undefined, sort: "newest" })).toBe(
      "/products?sort=newest"
    );
    expect(listingHref("/products", { category: "", sort: "newest" })).toBe("/products?sort=newest");
    expect(listingHref("/best", { sort: undefined, best: undefined })).toBe("/best?");
  });

  it("성공: 값은 URL 인코딩되고 키 순서는 넘긴 순서 그대로다", () => {
    expect(listingHref("/products", { category: "원피스/스커트", sort: "newest" })).toBe(
      `/products?category=${encodeURIComponent("원피스/스커트")}&sort=newest`
    );
    expect(listingHref("/products", { sort: "a&b=c d" })).toBe("/products?sort=a%26b%3Dc+d");
  });
});
