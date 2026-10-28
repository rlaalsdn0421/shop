import { describe, it, expect } from "vitest";
import { MENU_ITEMS, activeMenuKey, fitCount } from "./menu";
import { CATEGORIES } from "./categories";

describe("MENU_ITEMS", () => {
  it("성공: 베스트/전체상품/신상품이 앞에 오고 각자의 페이지로 간다", () => {
    expect(MENU_ITEMS.slice(0, 3).map((i) => [i.key, i.label, i.href])).toEqual([
      ["best", "베스트", "/best"],
      ["all", "전체상품", "/products"],
      ["new", "신상품", "/new"],
    ]);
  });

  it("실패: 앞의 세 항목은 메인 페이지 앵커(/#...)로 가지 않는다", () => {
    for (const item of MENU_ITEMS.slice(0, 3)) expect(item.href).not.toContain("#");
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

  it("성공: 각자의 페이지 주소면 해당 메뉴가 활성이다 (쿼리는 무시한다)", () => {
    expect(activeMenuKey("/best", new URLSearchParams())).toBe("best");
    expect(activeMenuKey("/best", new URLSearchParams("best=monthly"))).toBe("best");
    expect(activeMenuKey("/products", new URLSearchParams())).toBe("all");
    expect(activeMenuKey("/products", new URLSearchParams("sort=newest"))).toBe("all");
    expect(activeMenuKey("/new", new URLSearchParams())).toBe("new");
    expect(activeMenuKey("/new", new URLSearchParams({ category: "상의" }))).toBe("new");
  });

  it("실패: 메인 페이지의 best / sort 쿼리(섹션 탭)는 어떤 메뉴도 활성으로 만들지 않는다", () => {
    expect(activeMenuKey("/", new URLSearchParams("best=weekly"))).toBeUndefined();
    expect(activeMenuKey("/", new URLSearchParams("sort=newest"))).toBeUndefined();
    expect(activeMenuKey("/", new URLSearchParams("best=weekly&sort=newest"))).toBeUndefined();
  });

  it("성공: 메인 페이지에서는 유효한 category 가 best / sort 와 함께 있어도 카테고리만 활성이다", () => {
    expect(activeMenuKey("/", new URLSearchParams({ category: "상의", best: "weekly", sort: "newest" }))).toBe(
      "category:상의"
    );
  });

  it("실패: 정확히 일치하는 주소만 활성이다 (하위 경로, 끝 슬래시, 대소문자는 아니다)", () => {
    for (const path of ["/products/123", "/products/new", "/products/", "/best/", "/new/x", "/newest", "/Products", "/best2"]) {
      expect(activeMenuKey(path, new URLSearchParams())).toBeUndefined();
    }
  });

  it("실패: 홈이 아닌 경로나 알 수 없는 카테고리는 활성 항목이 없다", () => {
    expect(activeMenuKey("/cart", new URLSearchParams({ category: "상의" }))).toBeUndefined();
    expect(activeMenuKey("/", new URLSearchParams({ category: "없는분류" }))).toBeUndefined();
    expect(activeMenuKey("/", new URLSearchParams())).toBeUndefined();
    expect(activeMenuKey("/cart", new URLSearchParams("best=weekly"))).toBeUndefined();
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
