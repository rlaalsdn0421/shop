import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { SortTabs } from "./SortTabs";

describe("SortTabs", () => {
  it("성공: 7개 정렬 링크가 ?sort=... 이고 할인율순은 없다", () => {
    render(<SortTabs selected="popular" />);
    expect(screen.getAllByRole("link")).toHaveLength(7);
    expect(screen.getByRole("link", { name: "인기도순" })).toHaveAttribute("href", "/?sort=popular#all");
    expect(screen.getByRole("link", { name: "최신 등록순" })).toHaveAttribute("href", "/?sort=newest#all");
    expect(screen.getByRole("link", { name: "낮은 가격순" })).toHaveAttribute("href", "/?sort=price_asc#all");
    expect(screen.getByRole("link", { name: "높은 가격순" })).toHaveAttribute("href", "/?sort=price_desc#all");
    expect(screen.getByRole("link", { name: "리뷰 많은순" })).toHaveAttribute("href", "/?sort=reviews#all");
    expect(screen.getByRole("link", { name: "평점 높은순" })).toHaveAttribute("href", "/?sort=rating#all");
    expect(screen.getByRole("link", { name: "누적 판매순" })).toHaveAttribute("href", "/?sort=sales#all");
    expect(screen.queryByText("할인율순")).not.toBeInTheDocument();
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
