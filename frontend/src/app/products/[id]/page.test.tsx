import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import ProductDetail from "./page";

// The client forms need providers that are not what this page test is about.
vi.mock("@/components/AddToCartForm", () => ({ AddToCartForm: () => null }));
vi.mock("@/components/ReviewForm", () => ({ ReviewForm: () => null }));

const product = {
  id: "p1",
  name: "린넨 셔츠",
  description: "시원한 셔츠",
  price: 8000,
  stock: 5,
  imageUrl: "https://example.com/a.jpg",
};

function mockBackend(body: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string) => {
      const reviews = String(input).endsWith("/reviews");
      return {
        ok: true,
        status: 200,
        json: async () => (reviews ? { averageRating: null, reviewCount: 0, reviews: [] } : body),
      };
    })
  );
}

async function renderDetail() {
  render(await ProductDetail({ params: Promise.resolve({ id: "p1" }) }));
}

beforeEach(() => vi.unstubAllGlobals());

describe("상품 상세 페이지 할인/해시태그", () => {
  it("성공: 할인율, 판매가, 취소선 정가와 해시태그를 보여준다", async () => {
    mockBackend({ ...product, originalPrice: 10000, discountRate: 20, hashtags: ["여름", "린넨"] });
    await renderDetail();
    expect(screen.getByText("20%")).toBeInTheDocument();
    expect(screen.getByText("8,000원")).toBeInTheDocument();
    expect(screen.getByText("10,000원").tagName).toBe("DEL");
    // Screen readers get labels in this order: rate, sale price, original price.
    expect(screen.getByText("20%").parentElement).toHaveTextContent("할인율 20%8,000원정가 10,000원");
    expect(screen.getByText("할인율")).toHaveClass("sr-only");
    expect(screen.getByText("정가")).toHaveClass("sr-only");
    expect(screen.getByRole("list", { name: "해시태그" })).toHaveTextContent("#여름#린넨");
  });

  it("실패: 할인 필드가 null이고 태그가 빈 배열이면 판매가만 보인다", async () => {
    mockBackend({ ...product, originalPrice: null, discountRate: null, hashtags: [] });
    await renderDetail();
    expect(screen.getByText("8,000원")).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
    expect(document.querySelector("del")).toBeNull();
    expect(screen.queryByRole("list", { name: "해시태그" })).not.toBeInTheDocument();
  });

  it("실패: 옛 백엔드처럼 필드가 아예 없어도 깨지지 않는다", async () => {
    mockBackend(product);
    await renderDetail();
    expect(screen.getByRole("heading", { name: "린넨 셔츠" })).toBeInTheDocument();
    expect(screen.getByText("8,000원")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "해시태그" })).not.toBeInTheDocument();
  });
});
