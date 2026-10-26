import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ProductCard } from "./ProductCard";
import { FREE_SHIPPING_THRESHOLD, SHIPPING_FEE } from "@/lib/constants";

// The heart (WishButton) needs auth, router and wishlist; this file only checks the card, so they are inert fakes.
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/auth/AuthContext", () => ({ useAuth: () => ({ session: null }) }));
vi.mock("@/wishlist/WishlistContext", () => ({
  useWishlist: () => ({
    ready: true,
    loadFailed: false,
    ids: new Set<string>(),
    isWished: () => false,
    toggle: vi.fn(),
    pending: new Set<string>(),
    lastError: null,
  }),
}));

const base = { id: "p1", name: "린넨 셔츠", price: 12900, imageUrl: "https://example.com/a.jpg" };

describe("ProductCard", () => {
  it("성공: 이름, 천 단위 가격, 배송비 칩을 보여준다", () => {
    render(<ProductCard {...base} />);
    expect(screen.getByText("린넨 셔츠")).toBeInTheDocument();
    expect(screen.getByText("12,900원")).toBeInTheDocument();
    expect(
      screen.getByText(
        `배송비 ${SHIPPING_FEE.toLocaleString("ko-KR")}원 (${FREE_SHIPPING_THRESHOLD.toLocaleString("ko-KR")}원 이상 무료)`
      )
    ).toBeInTheDocument();
    expect(screen.getByText("배송비 3,500원 (50,000원 이상 무료)")).toBeInTheDocument();
  });

  it("성공: 배송비 칩은 좁은 칸에서 줄바꿈돼도 답답하지 않게 leading-tight를 쓴다", () => {
    render(<ProductCard {...base} />);
    expect(screen.getByText(/^배송비 /)).toHaveClass("leading-tight");
  });

  it("실패: 예전 문구(무료 조건 없는 '배송비 3,500원')만 있는 칩은 더 이상 없다", () => {
    render(<ProductCard {...base} />);
    expect(screen.queryByText("배송비 3,500원")).not.toBeInTheDocument();
  });

  it("성공: 카드 전체가 상품 페이지로 가는 링크 하나고, 하트 버튼은 그 링크 밖에 있다", () => {
    render(<ProductCard {...base} />);
    expect(screen.getAllByRole("link")).toHaveLength(1);
    const link = screen.getByRole("link");
    expect(link).toHaveAttribute("href", "/products/p1");
    const heart = screen.getByRole("button", { name: "찜하기 - 린넨 셔츠" });
    expect(link).not.toContainElement(heart);
    // Sibling of the link in the card wrapper, so the heart can sit over the image without nesting a button in an anchor.
    expect(link.parentElement).toContainElement(heart);
    expect(screen.getAllByRole("button")).toHaveLength(1);
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

describe("ProductCard 가격 범위", () => {
  it("성공: 옵션 가격이 다르면 가격 뒤에 ~를 붙인다", () => {
    render(<ProductCard {...base} price={23000} priceVaries />);
    expect(screen.getByText("23,000원~")).toBeInTheDocument();
  });

  it("실패: priceVaries가 false이거나 없으면 ~가 없다", () => {
    const { rerender } = render(<ProductCard {...base} price={23000} priceVaries={false} />);
    expect(screen.getByText("23,000원")).toBeInTheDocument();
    rerender(<ProductCard {...base} price={23000} />);
    expect(screen.getByText("23,000원")).toBeInTheDocument();
    expect(screen.queryByText(/~/)).not.toBeInTheDocument();
  });
});
