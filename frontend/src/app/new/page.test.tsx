import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ReactElement } from "react";
import NewProductsPage from "./page";

// The heart on each card is covered by WishButton.test.tsx.
vi.mock("@/components/WishButton", () => ({ WishButton: () => null }));

const item = (id: string, name: string) => ({
  id,
  name,
  price: 10000,
  imageUrl: "https://example.com/x.jpg",
});

type Reply = { status: number; body: unknown } | Promise<never>;

function mockList(reply: Reply | ((url: URL) => Reply)) {
  const calls: URL[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string) => {
      const url = new URL(String(input));
      calls.push(url);
      const { status, body } = await (typeof reply === "function" ? reply(url) : reply);
      return { ok: status < 300, status, json: async () => body };
    })
  );
  return calls;
}

const page = (items: ReturnType<typeof item>[], total?: number, hasMore = false): Reply => ({
  status: 200,
  body: { items, hasMore, ...(total === undefined ? {} : { total }) },
});

async function renderPage() {
  render(await NewProductsPage());
}

beforeEach(() => {
  vi.unstubAllGlobals();
  vi.stubGlobal(
    "IntersectionObserver",
    class {
      observe() {}
      disconnect() {}
    }
  );
});

describe("/new 신상품 페이지", () => {
  it("성공: 최신 등록순 첫 페이지(page 0, size 20)를 category 없이 한 번만 요청한다", async () => {
    const calls = mockList(page([item("a", "신상품A")], 1));
    await renderPage();
    expect(calls).toHaveLength(1);
    expect(calls[0].pathname).toBe("/api/products");
    expect(calls[0].searchParams.get("sort")).toBe("newest");
    expect(calls[0].searchParams.get("page")).toBe("0");
    expect(calls[0].searchParams.get("size")).toBe("20");
    expect(calls[0].searchParams.has("category")).toBe(false);
    expect(screen.getByText("신상품A")).toBeInTheDocument();
  });

  it("성공: section 은 all-title 로 이름 붙고 pt-2 를 가지며 머리는 h1 '신상품' 과 총 개수다", async () => {
    mockList(page([item("a", "신상품A")], 1234));
    await renderPage();
    const section = document.querySelector("section") as HTMLElement;
    expect(document.querySelectorAll("section")).toHaveLength(1);
    expect(section).toHaveAttribute("aria-labelledby", "all-title");
    expect(section.className).toContain("pt-2");
    expect(screen.getByRole("region", { name: "신상품" })).toBe(section);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(/^신상품$/);
    const lis = within(screen.getByRole("navigation", { name: "현재 위치" })).getAllByRole("listitem", { hidden: true });
    expect(lis[2].textContent).toBe("신상품(총 1,234개)");
  });

  it("실패: 서버 응답에 total 이 없으면 '(총' 만 빠지고 격자는 그려진다", async () => {
    mockList(page([item("a", "신상품A")]));
    await renderPage();
    expect(document.body.textContent).not.toContain("(총");
    expect(screen.getByText("신상품A")).toBeInTheDocument();
  });

  it("실패: 정렬 줄이 없다 (최신 등록순 고정)", async () => {
    mockList(page([item("a", "신상품A")], 1));
    await renderPage();
    expect(screen.queryByRole("navigation", { name: "정렬" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /인기도순|낮은 가격순/ })).not.toBeInTheDocument();
  });

  it("성공: 격자는 div.mt-8 안의 listing 격자이고 정렬은 newest 로 고정된다", async () => {
    mockList(page([item("a", "신상품A")], 1, true));
    await renderPage();
    const wrap = document.querySelector("section > div.mt-8") as HTMLElement;
    expect(wrap).toBeInTheDocument();
    expect(wrap.querySelector(".grid")).toHaveClass("lg:grid-cols-4", "gap-y-10");

    // section > [ListingHeader, div.mt-8 > InfiniteProductGrid]
    const tree = (await NewProductsPage()) as ReactElement<{
      children: [ReactElement, ReactElement<{ className: string; children: ReactElement<Record<string, unknown>> }>];
    }>;
    const grid = tree.props.children[1].props.children;
    expect(tree.props.children[1].props.className).toBe("mt-8");
    expect(grid.props.sort).toBe("newest");
    expect(grid.props.variant).toBe("listing");
    expect(grid.props.initialHasMore).toBe(true);
    expect(grid.props.category).toBeUndefined();
  });

  it("실패: 상품이 없으면 머리는 남고 '아직 신상품이 없어요' 안내만 보이며 격자는 없다", async () => {
    mockList(page([], 0));
    await renderPage();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("신상품");
    expect(screen.getByRole("status")).toHaveTextContent(/^아직 신상품이 없어요$/);
    expect(document.querySelector(".grid")).toBeNull();
    expect(document.querySelector("div.mt-8")).toBeNull();
  });

  it("실패: 상품이 있으면 빈 목록 안내는 나오지 않는다", async () => {
    mockList(page([item("a", "신상품A")], 1));
    await renderPage();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("실패: 목록 요청이 실패(HTTP 오류)하면 페이지가 오류로 던진다", async () => {
    for (const status of [400, 404, 500]) {
      mockList({ status, body: {} });
      await expect(NewProductsPage()).rejects.toThrow("상품 목록을 불러오지 못했습니다.");
    }
  });

  it("실패: 네트워크 오류도 삼키지 않고 던진다", async () => {
    mockList(() => Promise.reject(new TypeError("network")));
    await expect(NewProductsPage()).rejects.toThrow("network");
  });
});
