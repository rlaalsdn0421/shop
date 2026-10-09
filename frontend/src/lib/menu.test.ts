import { describe, it, expect } from "vitest";
import { MENU_ITEMS, activeMenuKey, fitCount } from "./menu";
import { CATEGORIES } from "./categories";

describe("MENU_ITEMS", () => {
  it("성공: 베스트/전체상품/신상품이 앞에 오고 앵커 링크를 가진다", () => {
    expect(MENU_ITEMS.slice(0, 3).map((i) => [i.label, i.href])).toEqual([
      ["베스트", "/#best"],
      ["전체상품", "/#all"],
      ["신상품", "/#new"],
    ]);
  });

  it("성공: 카테고리가 순서대로 이어지고 이름이 URL 인코딩된다", () => {
    expect(MENU_ITEMS.slice(3).map((i) => i.label)).toEqual(CATEGORIES);
    const skirt = MENU_ITEMS.find((i) => i.label === "원피스/스커트");
    expect(skirt?.href).toBe(`/?category=${encodeURIComponent("원피스/스커트")}`);
  });
});

describe("activeMenuKey", () => {
  it("성공: category 쿼리면 해당 카테고리가 활성이다", () => {
    expect(activeMenuKey("/", new URLSearchParams({ category: "상의" }))).toBe("category:상의");
  });

  it("성공: best / sort 쿼리면 베스트 / 전체상품이 활성이다", () => {
    expect(activeMenuKey("/", new URLSearchParams("best=weekly"))).toBe("best");
    expect(activeMenuKey("/", new URLSearchParams("sort=newest"))).toBe("all");
  });

  it("실패: 홈이 아닌 경로나 알 수 없는 카테고리는 활성 항목이 없다", () => {
    expect(activeMenuKey("/cart", new URLSearchParams({ category: "상의" }))).toBeUndefined();
    expect(activeMenuKey("/", new URLSearchParams({ category: "없는분류" }))).toBeUndefined();
    expect(activeMenuKey("/", new URLSearchParams())).toBeUndefined();
  });
});

describe("fitCount", () => {
  const widths = [50, 50, 50, 50];

  it("성공: 모두 들어가면 전체 개수를 반환한다 (더보기 자리는 필요 없다)", () => {
    expect(fitCount(widths, 230, 10, 70)).toBe(4);
  });

  it("성공: 모자라면 더보기 자리를 빼고 들어가는 만큼만 반환한다", () => {
    // 70(더보기) + 10 + 50 = 130, + 10 + 50 = 190 <= 200, 다음은 250 > 200
    expect(fitCount(widths, 200, 10, 70)).toBe(2);
  });

  it("실패: 아무것도 들어가지 않으면 0이다", () => {
    expect(fitCount(widths, 100, 10, 70)).toBe(0);
  });
});
