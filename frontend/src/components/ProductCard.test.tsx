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

  it("성공: 할인율, 판매가, 취소선 정가를 보여주고 카드는 여전히 링크 하나다", () => {
    render(<ProductCard {...base} price={10320} originalPrice={12900} discountRate={20} />);
    expect(screen.getByText("20%")).toBeInTheDocument();
    expect(screen.getByText("10,320원")).toBeInTheDocument();
    expect(screen.getByText("12,900원").tagName).toBe("DEL");
    // Screen readers get labels in this order: rate, sale price, original price.
    expect(screen.getByText("20%").parentElement).toHaveTextContent("할인율 20%10,320원정가 12,900원");
    expect(screen.getByText("할인율")).toHaveClass("sr-only");
    expect(screen.getByText("정가")).toHaveClass("sr-only");
    expect(screen.getAllByRole("link")).toHaveLength(1);
  });

  it("실패: 할인이 없으면(null 또는 필드 없음) 할인율과 정가가 없다", () => {
    const { unmount } = render(<ProductCard {...base} originalPrice={null} discountRate={null} hashtags={[]} />);
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
    expect(document.querySelector("del")).toBeNull();
    unmount();
    render(<ProductCard {...base} />);
    expect(screen.getByText("12,900원")).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it("성공: 해시태그를 #tag로 보여준다 (최대 3개)", () => {
    render(<ProductCard {...base} hashtags={["여름", "린넨", "데일리", "넷째"]} />);
    expect(screen.getByText("#여름")).toBeInTheDocument();
    expect(screen.getByText("#린넨")).toBeInTheDocument();
    expect(screen.getByText("#데일리")).toBeInTheDocument();
    expect(screen.queryByText("#넷째")).not.toBeInTheDocument();
    expect(screen.getAllByRole("link")).toHaveLength(1);
  });

  it("성공: 20자 긴 태그도 그대로 렌더링하고 줄임(truncate) 스타일을 가진다", () => {
    const long = "가".repeat(20);
    render(<ProductCard {...base} hashtags={[long]} />);
    expect(screen.getByText(`#${long}`)).toHaveClass("truncate", "max-w-full");
  });

  it("실패: 옛 백엔드처럼 hashtags가 없거나 배열이 아니어도 깨지지 않는다", () => {
    const { unmount } = render(<ProductCard {...base} hashtags={undefined} />);
    expect(screen.queryByText(/^#/)).not.toBeInTheDocument();
    unmount();
    // Malformed payload from the network: not typed as string[] at runtime.
    render(<ProductCard {...base} hashtags={null as unknown as string[]} />);
    expect(screen.getAllByRole("link")).toHaveLength(1);
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
