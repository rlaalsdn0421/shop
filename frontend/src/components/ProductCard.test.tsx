import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ProductCard } from "./ProductCard";
import { SHIPPING_FEE } from "@/lib/constants";

const base = { id: "p1", name: "린넨 셔츠", price: 12900, imageUrl: "https://example.com/a.jpg" };

describe("ProductCard", () => {
  it("성공: 이름, 천 단위 가격, 배송비 칩을 보여준다", () => {
    render(<ProductCard {...base} />);
    expect(screen.getByText("린넨 셔츠")).toBeInTheDocument();
    expect(screen.getByText("12,900원")).toBeInTheDocument();
    expect(screen.getByText(`배송비 ${SHIPPING_FEE.toLocaleString("ko-KR")}원`)).toBeInTheDocument();
    expect(screen.getByText("배송비 3,500원")).toBeInTheDocument();
  });

  it("성공: 카드 전체가 상품 페이지로 가는 링크 하나다", () => {
    render(<ProductCard {...base} />);
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(screen.getByRole("link")).toHaveAttribute("href", "/products/p1");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("성공: rank를 주면 순위 배지를 보여준다", () => {
    render(<ProductCard {...base} rank={3} />);
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("실패: rank가 없으면 순위 배지가 없다", () => {
    render(<ProductCard {...base} />);
    expect(screen.queryByText("순위")).not.toBeInTheDocument();
  });
});
