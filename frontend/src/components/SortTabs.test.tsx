import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { SortTabs } from "./SortTabs";

afterEach(() => {
  // jsdom has no scrollIntoView, so tests add it on the prototype and remove it again.
  Reflect.deleteProperty(Element.prototype, "scrollIntoView");
});

describe("SortTabs", () => {
  it("성공: 선택된 탭을 가로 스크롤 영역 가운데로 가져온다 (세로 스크롤은 건드리지 않는다)", () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    render(<SortTabs selected="discount" />);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView).toHaveBeenCalledWith({ inline: "center", block: "nearest" });
    expect(scrollIntoView.mock.contexts[0]).toBe(screen.getByRole("link", { name: "할인율순" }));
  });

  it("성공: 선택이 바뀌면 새로 선택된 탭으로 다시 스크롤한다", () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const { rerender } = render(<SortTabs selected="popular" />);
    rerender(<SortTabs selected="rating" />);
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
    expect(scrollIntoView.mock.contexts[1]).toBe(screen.getByRole("link", { name: "평점 높은순" }));
  });

  it("실패: scrollIntoView가 없는 환경에서도 깨지지 않는다", () => {
    expect(() => render(<SortTabs selected="discount" />)).not.toThrow();
  });

  it("성공: 8개 정렬 링크가 ?sort=... 이고 순서가 기준 쇼핑몰과 같다", () => {
    render(<SortTabs selected="popular" />);
    expect(screen.getAllByRole("link").map((l) => l.textContent)).toEqual([
      "인기도순",
      "최신 등록순",
      "낮은 가격순",
      "높은 가격순",
      "할인율순",
      "누적 판매순",
      "리뷰 많은순",
      "평점 높은순",
    ]);
    expect(screen.getByRole("link", { name: "할인율순" })).toHaveAttribute("href", "/?sort=discount#all");
    expect(screen.getByRole("link", { name: "인기도순" })).toHaveAttribute("href", "/?sort=popular#all");
    expect(screen.getByRole("link", { name: "최신 등록순" })).toHaveAttribute("href", "/?sort=newest#all");
    expect(screen.getByRole("link", { name: "낮은 가격순" })).toHaveAttribute("href", "/?sort=price_asc#all");
    expect(screen.getByRole("link", { name: "높은 가격순" })).toHaveAttribute("href", "/?sort=price_desc#all");
    expect(screen.getByRole("link", { name: "리뷰 많은순" })).toHaveAttribute("href", "/?sort=reviews#all");
    expect(screen.getByRole("link", { name: "평점 높은순" })).toHaveAttribute("href", "/?sort=rating#all");
    expect(screen.getByRole("link", { name: "누적 판매순" })).toHaveAttribute("href", "/?sort=sales#all");
  });

  it("성공: 할인율순을 고르면 그 탭만 aria-current를 가진다", () => {
    render(<SortTabs selected="discount" />);
    expect(screen.getByRole("link", { name: "할인율순" })).toHaveAttribute("aria-current", "true");
    expect(screen.getAllByRole("link").filter((l) => l.hasAttribute("aria-current"))).toHaveLength(1);
  });

  it("성공: 선택된 정렬만 aria-current를 가진다", () => {
    render(<SortTabs selected="rating" />);
    expect(screen.getByRole("link", { name: "평점 높은순" })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("link", { name: "인기도순" })).not.toHaveAttribute("aria-current");
  });

  it("성공: category가 있으면 모든 링크가 category를 유지한다", () => {
    render(<SortTabs selected="popular" category="상의" />);
    for (const link of screen.getAllByRole("link")) {
      expect(link.getAttribute("href")).toContain(`category=${encodeURIComponent("상의")}`);
    }
  });

  it("실패: category가 없으면 링크에 category가 붙지 않는다", () => {
    render(<SortTabs selected="popular" />);
    for (const link of screen.getAllByRole("link")) {
      expect(link.getAttribute("href")).not.toContain("category");
    }
  });

  it("성공: best를 넘기면 정렬 탭 링크가 베스트 기간 선택을 유지한다", () => {
    render(<SortTabs selected="popular" best="weekly" />);
    expect(screen.getByRole("link", { name: "낮은 가격순" })).toHaveAttribute(
      "href",
      "/?sort=price_asc&best=weekly#all"
    );
  });
});

describe("SortTabs variant=listing", () => {
  it("성공: 정렬 nav는 mb-8 mt-4 이고 목록은 줄바꿈되는 flex이며, 링크 8개의 href는 기본 모양과 같다", () => {
    const { unmount } = render(<SortTabs selected="popular" category="상의" best="weekly" />);
    const defaultHrefs = screen.getAllByRole("link").map((l) => l.getAttribute("href"));
    unmount();

    render(<SortTabs variant="listing" selected="popular" category="상의" best="weekly" />);
    const nav = screen.getByRole("navigation", { name: "정렬" });
    expect(nav).toHaveClass("mb-8", "mt-4");
    expect(nav).not.toHaveClass("border-b");
    expect(nav.querySelector("ul")).toHaveClass("flex", "flex-wrap");
    const links = screen.getAllByRole("link");
    expect(links).toHaveLength(8);
    expect(links.map((l) => l.getAttribute("href"))).toEqual(defaultHrefs);
    expect(screen.getByRole("link", { name: "높은 가격순" })).toHaveAttribute(
      "href",
      `/?category=${encodeURIComponent("상의")}&sort=price_desc&best=weekly#all`
    );
  });

  it("성공: 선택한 링크만 aria-current=page, 굵게+진하게, 맨 앞에 숨겨진 ✓가 있고 접근 이름은 라벨뿐이다", () => {
    render(<SortTabs variant="listing" selected="discount" />);
    const selected = screen.getByRole("link", { name: "할인율순" });
    expect(selected).toHaveAttribute("aria-current", "page");
    expect(selected).toHaveClass("font-bold", "text-neutral-900");
    expect(selected.textContent).toBe("✓ 할인율순");
    const mark = selected.firstElementChild as HTMLElement;
    expect(mark.tagName).toBe("SPAN");
    expect(mark).toHaveAttribute("aria-hidden", "true");
    expect(mark.textContent).toBe("✓ ");
    expect(selected.firstChild).toBe(mark);
    expect(screen.getAllByRole("link").filter((l) => l.hasAttribute("aria-current"))).toHaveLength(1);
  });

  it("실패: 선택하지 않은 링크는 text-neutral-500 이고 aria-current와 ✓가 없다", () => {
    render(<SortTabs variant="listing" selected="discount" />);
    const others = screen.getAllByRole("link").filter((l) => l.textContent !== "✓ 할인율순");
    expect(others).toHaveLength(7);
    for (const link of others) {
      expect(link).toHaveClass("text-neutral-500");
      expect(link).not.toHaveClass("font-bold");
      expect(link).not.toHaveAttribute("aria-current");
      expect(link.textContent).not.toContain("✓");
    }
    expect(document.body.textContent?.match(/✓/g)).toHaveLength(1);
  });

  it("성공: 모든 항목 li에 세로 구분선 클래스가 있다 (첫 항목은 선 없이 왼쪽 정렬)", () => {
    render(<SortTabs variant="listing" selected="popular" />);
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(8);
    for (const li of items) {
      expect(li).toHaveClass("border-l", "border-neutral-300", "px-3", "first:border-l-0", "first:pl-0");
    }
  });

  it("성공: 선택이 바뀌면 ✓와 aria-current가 새 링크로 옮겨간다", () => {
    const { rerender } = render(<SortTabs variant="listing" selected="popular" />);
    rerender(<SortTabs variant="listing" selected="rating" />);
    expect(screen.getByRole("link", { name: "평점 높은순" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "인기도순" }).textContent).toBe("인기도순");
    expect(document.body.textContent?.match(/✓/g)).toHaveLength(1);
  });

  it("성공: 선택된 링크를 가운데로 스크롤하고, sales 라벨은 누적 판매순 그대로다", () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    render(<SortTabs variant="listing" selected="sales" />);
    expect(scrollIntoView).toHaveBeenCalledWith({ inline: "center", block: "nearest" });
    expect(scrollIntoView.mock.contexts[0]).toBe(screen.getByRole("link", { name: "누적 판매순" }));
    expect(screen.getByRole("link", { name: "누적 판매순" })).toHaveAttribute("href", "/?sort=sales#all");
  });

  it("실패: variant가 없으면 예전 모양 그대로다 (aria-current=true, ✓ 없음, 밑줄 nav)", () => {
    render(<SortTabs selected="discount" />);
    const nav = screen.getByRole("navigation", { name: "정렬" });
    expect(nav).toHaveClass("mb-6", "border-b");
    expect(nav).not.toHaveClass("mt-4");
    const selected = screen.getByRole("link", { name: "할인율순" });
    expect(selected).toHaveAttribute("aria-current", "true");
    expect(selected).toHaveClass("border-b-2", "border-neutral-900");
    expect(document.body.textContent).not.toContain("✓");
    expect(screen.getAllByRole("listitem")[0]).toHaveClass("shrink-0");
  });
});
