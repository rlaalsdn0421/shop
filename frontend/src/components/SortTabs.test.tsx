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
