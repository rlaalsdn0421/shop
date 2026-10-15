import { describe, it, expect, vi, beforeEach } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { FREE_SHIPPING_THRESHOLD, SHIPPING_FEE } from "@/lib/constants";
import { formatWon } from "@/lib/price";
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

const noReviews = { averageRating: null, reviewCount: 0, reviews: [] };

function mockBackend(body: unknown, reviewData: unknown = noReviews) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string) => {
      const reviews = String(input).endsWith("/reviews");
      return {
        ok: true,
        status: 200,
        json: async () => (reviews ? reviewData : body),
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

describe("상품 상세 페이지 레이아웃", () => {
  it("성공: 카테고리가 있으면 홈 / 카테고리 경로를 메뉴와 같은 링크로 보여준다", async () => {
    mockBackend({ ...product, category: "원피스/스커트" });
    await renderDetail();
    const crumbs = within(screen.getByRole("navigation", { name: "현재 위치" }));
    expect(crumbs.getByRole("link", { name: "홈" })).toHaveAttribute("href", "/");
    expect(crumbs.getByRole("link", { name: "원피스/스커트" })).toHaveAttribute(
      "href",
      `/?category=${encodeURIComponent("원피스/스커트")}`
    );
  });

  it("실패: 카테고리가 없거나 모르는 이름이면 홈만 보인다", async () => {
    mockBackend({ ...product, category: null });
    await renderDetail();
    const crumbs = within(screen.getByRole("navigation", { name: "현재 위치" }));
    expect(crumbs.getAllByRole("link")).toHaveLength(1);
    cleanup();
    mockBackend({ ...product, category: "없는카테고리" });
    await renderDetail();
    expect(within(screen.getByRole("navigation", { name: "현재 위치" })).getAllByRole("link")).toHaveLength(1);
  });

  it("성공: 리뷰가 있으면 평균 별점과 리뷰 건수를 보여주고 리뷰 영역으로 연결한다", async () => {
    mockBackend(product, {
      averageRating: 4.5,
      reviewCount: 2,
      reviews: [
        { id: "r1", reviewerName: "가", rating: 5, comment: "좋아요", createdAt: "2026-01-01T00:00:00Z" },
        { id: "r2", reviewerName: "나", rating: 4, comment: "괜찮아요", createdAt: "2026-01-02T00:00:00Z" },
      ],
    });
    await renderDetail();
    const link = screen.getByRole("link", { name: "리뷰 2건" });
    expect(link).toHaveAttribute("href", "#reviews");
    expect(link.parentElement).toHaveTextContent("4.5 · 리뷰 2건");
    expect(document.getElementById("reviews")).toContainElement(screen.getByText("좋아요"));
  });

  it("실패: 리뷰가 없으면 0.0과 0건으로 보여준다", async () => {
    mockBackend(product);
    await renderDetail();
    expect(screen.getByRole("link", { name: "리뷰 0건" }).parentElement).toHaveTextContent("0.0 · 리뷰 0건");
    expect(screen.getByRole("link", { name: "리뷰 (0)" })).toHaveAttribute("href", "#reviews");
  });

  it("성공: 배송비 안내를 상수에서 만들고 교환·환불 안내로 연결한다", async () => {
    mockBackend(product);
    await renderDetail();
    const text = `배송비 ${formatWon(SHIPPING_FEE)} (${formatWon(FREE_SHIPPING_THRESHOLD)} 이상 무료배송)`;
    expect(text).toBe("배송비 3,500원 (50,000원 이상 무료배송)");
    // under the price, in the delivery box and in the bottom note
    expect(screen.getAllByText(text, { exact: false }).length).toBeGreaterThanOrEqual(3);
    const links = screen.getAllByRole("link", { name: /교환·환불 안내/ });
    expect(links.length).toBe(2);
    links.forEach((l) => expect(l).toHaveAttribute("href", "/refund"));
  });

  it("성공: 상품 설명 전문이 설명 영역에 있고 탭 링크가 영역으로 이동한다", async () => {
    mockBackend({ ...product, description: "시원한 셔츠\n두 번째 줄" });
    await renderDetail();
    const tabs = within(screen.getByRole("navigation", { name: "상품 정보 이동" }));
    expect(tabs.getByRole("link", { name: "상품 설명" })).toHaveAttribute("href", "#description");
    expect(tabs.getByRole("link", { name: "리뷰 (0)" })).toHaveAttribute("href", "#reviews");
    expect(screen.getByRole("heading", { level: 2, name: "상품 설명" })).toBeInTheDocument();
    expect(document.getElementById("description")).toHaveTextContent("두 번째 줄");
  });

  it("성공: 상품 이미지는 한 장이면 썸네일 없이 큰 이미지만 보여준다", async () => {
    mockBackend(product);
    await renderDetail();
    expect(screen.getByRole("img", { name: "린넨 셔츠" })).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "상품 이미지 목록" })).not.toBeInTheDocument();
  });
});
