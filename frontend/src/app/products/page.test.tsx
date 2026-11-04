import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ReactElement } from "react";
import AllProductsPage from "./page";

// The heart on each card is covered by WishButton.test.tsx.
vi.mock("@/components/WishButton", () => ({ WishButton: () => null }));
// ListingSortBar needs the router; navigation itself is covered by ListingSortBar.test.tsx.
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

const item = (id: string, name: string) => ({
  id,
  name,
  price: 10000,
  imageUrl: "https://example.com/x.jpg",
});

type Reply = { status: number; body: unknown } | Promise<never>;

// Every backend call goes through fetch; `calls` records the URLs so tests can check the request.
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

type Query = Record<string, string | string[] | undefined>;
const getPage = (query: Query = {}) => AllProductsPage({ searchParams: Promise.resolve(query) });
async function renderPage(query: Query = {}) {
  render(await getPage(query));
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

describe("/products 전체상품 페이지", () => {
  it("성공: 기본은 인기도순 첫 페이지(page 0, size 20)를 category 없이 한 번만 요청한다", async () => {
    const calls = mockList(page([item("a", "상품A")], 1));
    await renderPage();
    expect(calls).toHaveLength(1);
    expect(calls[0].pathname).toBe("/api/products");
    expect(calls[0].searchParams.get("page")).toBe("0");
    expect(calls[0].searchParams.get("size")).toBe("20");
    expect(calls[0].searchParams.get("sort")).toBe("popular");
    expect(calls[0].searchParams.has("category")).toBe(false);
    expect(screen.getByText("상품A")).toBeInTheDocument();
  });

  it.each([["price_asc"], ["newest"], ["rating"]])("성공: ?sort=%s 를 요청에 쓰고 select 도 그 값으로 선택한다", async (sort) => {
    const calls = mockList(page([item("a", "상품A")], 1));
    await renderPage({ sort });
    expect(calls).toHaveLength(1);
    expect(calls[0].searchParams.get("sort")).toBe(sort);
    expect((screen.getByRole("combobox", { name: "정렬" }) as HTMLSelectElement).value).toBe(sort);
  });

  it("실패: 알 수 없는 sort 는 인기도순으로 돌아가고 요청에도 그 값이 새지 않는다", async () => {
    const calls = mockList(page([item("a", "상품A")], 1));
    await renderPage({ sort: "<b>해킹</b>" });
    expect(calls[0].searchParams.get("sort")).toBe("popular");
    expect((screen.getByRole("combobox", { name: "정렬" }) as HTMLSelectElement).value).toBe("popular");
    expect(document.body.textContent).not.toContain("해킹");
  });

  it("실패: 옛 링크의 ?sort=discount 는 인기도순으로 돌아가고 요청에도 discount 가 새지 않는다", async () => {
    const calls = mockList(page([item("a", "상품A")], 1));
    await renderPage({ sort: "discount" });
    expect(calls[0].searchParams.get("sort")).toBe("popular");
    expect((screen.getByRole("combobox", { name: "정렬" }) as HTMLSelectElement).value).toBe("popular");
  });

  it("성공: sort 가 여러 번 오면 첫 값만 쓴다", async () => {
    const calls = mockList(page([item("a", "상품A")], 1));
    await renderPage({ sort: ["rating", "sales"] });
    expect(calls[0].searchParams.get("sort")).toBe("rating");
  });

  it("성공: section 은 all-title 로 이름 붙고 pt-2 를 가지며 id=all 이 아니다", async () => {
    mockList(page([item("a", "상품A")], 1));
    await renderPage();
    const sections = document.querySelectorAll("section");
    expect(sections).toHaveLength(1);
    expect(sections[0]).toHaveAttribute("aria-labelledby", "all-title");
    expect(sections[0].className).toContain("pt-2");
    expect(sections[0]).not.toHaveAttribute("id");
    expect(screen.getByRole("region", { name: "전체상품" })).toBe(sections[0]);
  });

  it("성공: 머리에 가운데 정렬된 h1 '전체상품'과 '홈 › 전체상품' 경로가 있고 총 개수는 경로에 없다", async () => {
    mockList(page([item("a", "상품A")], 1234));
    await renderPage();
    const h1 = screen.getByRole("heading", { level: 1 });
    expect(h1).toHaveTextContent(/^전체상품$/);
    expect(h1).toHaveClass("text-center");
    expect(screen.queryAllByRole("heading", { level: 2 })).toHaveLength(0);
    const lis = within(screen.getByRole("navigation", { name: "현재 위치" })).getAllByRole("listitem", { hidden: true });
    expect(within(lis[0]).getByRole("link", { name: "홈" })).toHaveAttribute("href", "/");
    expect(lis[2].textContent).toBe("전체상품");
  });

  it("성공: 정렬 줄에 '총 1,234개'가 보인다", async () => {
    mockList(page([item("a", "상품A")], 1234));
    await renderPage();
    expect(document.body.textContent).toContain("총 1,234개");
  });

  it("실패: 서버 응답에 total 이 없어도 머리와 격자는 그려지고 '총' 만 빠진다", async () => {
    mockList(page([item("a", "상품A")]));
    await renderPage();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("전체상품");
    expect(document.body.textContent).not.toContain("총 ");
    expect(screen.getByText("상품A")).toBeInTheDocument();
  });

  it("성공: 정렬 select 는 옵션 7개(할인율순 없음)이고 격자보다 앞에 오며 옛 정렬 링크 줄은 없다", async () => {
    mockList(page([item("a", "상품A")], 1));
    await renderPage({ sort: "price_asc" });
    const select = screen.getByRole("combobox", { name: "정렬" }) as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual([
      "popular",
      "newest",
      "price_asc",
      "price_desc",
      "sales",
      "reviews",
      "rating",
    ]);
    expect(screen.queryByRole("option", { name: "할인율순" })).not.toBeInTheDocument();
    expect(select.value).toBe("price_asc");
    expect(screen.queryByRole("navigation", { name: "정렬" })).not.toBeInTheDocument();
    const grid = document.querySelector("section .grid") as HTMLElement;
    expect(grid).toHaveClass("lg:grid-cols-4", "gap-y-10");
    expect(select.compareDocumentPosition(grid) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("성공: 격자는 listing 모양이고 key 가 sort 라 정렬이 바뀌면 새로 시작한다", async () => {
    mockList(page([item("a", "상품A")], 3, true));
    const gridOf = async (query: Query) => {
      // section > [ListingHeader, fragment > [ListingSortBar, InfiniteProductGrid]]
      const tree = (await getPage(query)) as ReactElement<{
        children: [ReactElement, ReactElement<{ children: ReactElement<Record<string, unknown>>[] }>];
      }>;
      return tree.props.children[1].props.children[1];
    };
    const grid = await gridOf({ sort: "newest" });
    expect(grid.key).toBe("newest");
    expect(grid.props.variant).toBe("listing");
    expect(grid.props.sort).toBe("newest");
    expect(grid.props.initialHasMore).toBe(true);
    expect(grid.props.initialItems).toHaveLength(1);
    expect(grid.props.category).toBeUndefined();
    expect((await gridOf({})).key).toBe("popular");
    expect((await gridOf({ sort: "nope" })).key).toBe("popular");
  });

  it("실패: 상품이 없으면 머리는 남고 '아직 상품이 없어요' 안내만 보이며 정렬 줄과 격자는 없다", async () => {
    mockList(page([], 0));
    await renderPage();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("전체상품");
    expect(screen.getByRole("navigation", { name: "현재 위치" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(/^아직 상품이 없어요$/);
    expect(screen.queryByRole("combobox", { name: "정렬" })).not.toBeInTheDocument();
    expect(document.querySelector(".grid")).toBeNull();
    expect(screen.queryByText("상품이 없습니다.")).not.toBeInTheDocument();
  });

  it("실패: 상품이 있으면 빈 목록 안내는 나오지 않는다", async () => {
    mockList(page([item("a", "상품A")], 1));
    await renderPage();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("실패: 목록 요청이 실패(HTTP 오류)하면 페이지가 오류로 던진다", async () => {
    for (const status of [400, 404, 500]) {
      mockList({ status, body: {} });
      await expect(getPage()).rejects.toThrow("상품 목록을 불러오지 못했습니다.");
    }
  });

  it("실패: 네트워크 오류도 삼키지 않고 던진다", async () => {
    mockList(() => Promise.reject(new TypeError("network")));
    await expect(getPage()).rejects.toThrow("network");
  });
});
