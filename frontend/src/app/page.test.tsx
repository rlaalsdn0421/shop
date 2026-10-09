import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ReactElement } from "react";
import Home from "./page";

const item = (id: string, name: string) => ({
  id,
  name,
  price: 10000,
  imageUrl: "https://example.com/x.jpg",
});

type Handler = () => { status: number; body: unknown } | Promise<never>;

// Routes backend calls by path so each test decides which endpoint works.
function mockBackend(handlers: { best?: Handler; list?: (url: URL) => ReturnType<Handler> }) {
  const calls: URL[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string) => {
      const url = new URL(String(input));
      calls.push(url);
      const result =
        url.pathname === "/api/products/best"
          ? handlers.best?.()
          : handlers.list?.(url);
      if (!result) throw new Error("unexpected call " + url);
      const { status, body } = await result;
      return { ok: status < 300, status, json: async () => body };
    })
  );
  return calls;
}

const okList = (url: URL) => {
  const sort = url.searchParams.get("sort");
  const category = url.searchParams.get("category");
  const size = url.searchParams.get("size");
  const name = category ? `분류-${category}` : sort === "newest" && size === "10" ? "신상품A" : "전체A";
  return { status: 200, body: { items: [item(`${sort}-${size}`, name)], hasMore: false } };
};

async function renderHome(query: Record<string, string> = {}) {
  render(await Home({ searchParams: Promise.resolve(query) }));
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

describe("Home", () => {
  it("성공: 베스트, 신상품, 전체상품 세 섹션이 순서대로 나오고 앵커 id가 있다", async () => {
    mockBackend({
      best: () => ({ status: 200, body: { items: [1, 2, 3, 4].map((n) => item(`b${n}`, `베스트${n}`)) } }),
      list: okList,
    });
    await renderHome();

    const headings = screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent);
    expect(headings).toEqual(["베스트 상품", "신상품", "전체상품"]);
    expect(document.querySelector("section#best")).toBeInTheDocument();
    expect(document.querySelector("section#new")).toBeInTheDocument();
    expect(document.querySelector("section#all")).toBeInTheDocument();

    const best = within(document.querySelector("section#best") as HTMLElement);
    expect(best.getByText("베스트4")).toBeInTheDocument();
    expect(best.getByText("4")).toBeInTheDocument(); // rank badge
    expect(best.getByRole("link", { name: "전체보기 →" })).toHaveAttribute("href", "/?sort=popular#all");
    expect(
      within(document.querySelector("section#new") as HTMLElement).getByRole("link", { name: "전체보기 →" })
    ).toHaveAttribute("href", "/?sort=newest#all");
  });

  it("성공: 기본값은 실시간 베스트 4개, 신상품 최신순 10개, 전체상품 인기순이다", async () => {
    const calls = mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome();
    const best = calls.find((u) => u.pathname === "/api/products/best")!;
    expect(best.searchParams.get("period")).toBe("realtime");
    expect(best.searchParams.get("size")).toBe("4");
    const lists = calls.filter((u) => u.pathname === "/api/products");
    expect(lists.map((u) => `${u.searchParams.get("sort")}:${u.searchParams.get("size")}`).sort()).toEqual([
      "newest:10",
      "popular:20",
    ]);
  });

  it("성공: ?best=monthly&sort=price_asc 를 요청에 반영하고 선택 상태를 표시한다", async () => {
    const calls = mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome({ best: "monthly", sort: "price_asc" });
    expect(calls.find((u) => u.pathname === "/api/products/best")!.searchParams.get("period")).toBe("monthly");
    expect(calls.some((u) => u.searchParams.get("sort") === "price_asc")).toBe(true);
    expect(screen.getByRole("link", { name: /월간/ })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("link", { name: "낮은 가격순" })).toHaveAttribute("aria-current", "true");
  });

  it("성공: 카테고리가 있으면 베스트/신상품 없이 해당 카테고리 전체상품만 나온다", async () => {
    const calls = mockBackend({ list: okList });
    await renderHome({ category: "상의" });

    expect(screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)).toEqual(["상의"]);
    expect(screen.getByText("분류-상의")).toBeInTheDocument();
    expect(calls).toHaveLength(1);
    expect(calls[0].searchParams.get("category")).toBe("상의");
    expect(calls[0].searchParams.get("sort")).toBe("popular");
    expect(screen.getByRole("link", { name: "최신 등록순" })).toHaveAttribute(
      "href",
      `/?category=${encodeURIComponent("상의")}&sort=newest#all`
    );
  });

  it("실패: 베스트 API가 실패해도 나머지 섹션은 그대로 나온다", async () => {
    for (const status of [400, 404, 500]) {
      document.body.innerHTML = "";
      mockBackend({ best: () => ({ status, body: { error: "x" } }), list: okList });
      await renderHome();

      const best = within(document.querySelector("section#best") as HTMLElement);
      expect(best.getByText("상품을 불러오지 못했어요")).toBeInTheDocument();
      expect(screen.getByText("신상품A")).toBeInTheDocument();
      expect(screen.getByText("전체A")).toBeInTheDocument();
    }
  });

  it("실패: 네트워크 오류로 베스트 요청이 던져져도 페이지는 그려진다", async () => {
    mockBackend({ best: () => Promise.reject(new TypeError("network")), list: okList });
    await renderHome();
    expect(screen.getByText("상품을 불러오지 못했어요")).toBeInTheDocument();
    expect(screen.getByText("전체A")).toBeInTheDocument();
  });

  it("실패: 신상품 요청만 실패하면 그 섹션만 안내 문구가 나온다", async () => {
    mockBackend({
      best: () => ({ status: 200, body: { items: [item("b1", "베스트1")] } }),
      list: (url) =>
        url.searchParams.get("size") === "10" ? { status: 500, body: {} } : okList(url),
    });
    await renderHome();
    const fresh = within(document.querySelector("section#new") as HTMLElement);
    expect(fresh.getByText("상품을 불러오지 못했어요")).toBeInTheDocument();
    expect(screen.getByText("베스트1")).toBeInTheDocument();
    expect(screen.getByText("전체A")).toBeInTheDocument();
  });

  it("실패: 전체상품 목록이 실패하면 페이지 오류로 던진다", async () => {
    mockBackend({
      best: () => ({ status: 200, body: { items: [] } }),
      list: (url) =>
        url.searchParams.get("size") === "20" ? { status: 500, body: {} } : okList(url),
    });
    await expect(Home({ searchParams: Promise.resolve({}) })).rejects.toThrow(
      "상품 목록을 불러오지 못했습니다."
    );
  });

  it("성공: 정렬과 베스트 기간 선택이 서로의 탭 링크에 유지된다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome({ sort: "price_asc", best: "weekly" });
    expect(screen.getByRole("link", { name: "월간" })).toHaveAttribute(
      "href",
      "/?sort=price_asc&best=monthly#best"
    );
    expect(screen.getByRole("link", { name: "높은 가격순" })).toHaveAttribute(
      "href",
      "/?sort=price_desc&best=weekly#all"
    );
  });

  it("성공: 아무것도 고르지 않았으면 탭 링크에 기본값이 붙지 않는다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome();
    expect(screen.getByRole("link", { name: "주간" })).toHaveAttribute("href", "/?best=weekly#best");
    expect(screen.getByRole("link", { name: "높은 가격순" })).toHaveAttribute(
      "href",
      "/?sort=price_desc#all"
    );
  });

  it("성공: 베스트 기간만 바뀌면 전체상품 그리드의 key가 그대로라 상태가 유지된다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    const gridKey = async (query: Record<string, string>) => {
      const tree = (await Home({ searchParams: Promise.resolve(query) })) as ReactElement<{
        children: ReactElement<{ children: ReactElement[] }>[];
      }>;
      return tree.props.children[2].props.children[1].key;
    };
    expect(await gridKey({ sort: "price_asc", best: "realtime" })).toBe(
      await gridKey({ sort: "price_asc", best: "monthly" })
    );
    expect(await gridKey({ sort: "price_asc" })).not.toBe(await gridKey({ sort: "newest" }));
  });

  it("실패: 목록에 없는 category는 무시하고 제목에도 쓰지 않는다", async () => {
    const calls = mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome({ category: "<b>해킹</b>" });
    expect(screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)).toEqual([
      "베스트 상품",
      "신상품",
      "전체상품",
    ]);
    expect(screen.queryByText(/해킹/)).not.toBeInTheDocument();
    expect(calls.every((u) => !u.searchParams.has("category"))).toBe(true);
  });
});
