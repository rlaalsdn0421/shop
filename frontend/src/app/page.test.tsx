import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ReactElement } from "react";
import Home from "./page";

// The heart on each card is covered by WishButton.test.tsx; here it would only need auth/router/wishlist fakes.
vi.mock("@/components/WishButton", () => ({ WishButton: () => null }));

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
    expect(best.getByRole("link", { name: "전체보기 →" })).toHaveAttribute("href", "/best");
    expect(
      within(document.querySelector("section#new") as HTMLElement).getByRole("link", { name: "전체보기 →" })
    ).toHaveAttribute("href", "/new");
    // 전체상품 섹션에는 전체보기 링크가 없다.
    expect(
      within(document.querySelector("section#all") as HTMLElement).queryByRole("link", { name: "전체보기 →" })
    ).not.toBeInTheDocument();
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

    // The category title is the page's <h1>; the section headings of the home page do not appear.
    expect(screen.getAllByRole("heading", { level: 1 }).map((h) => h.textContent)).toEqual(["상의"]);
    expect(screen.queryAllByRole("heading", { level: 2 })).toHaveLength(0);
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
      // children: [withdrawn notice, 베스트, 신상품, 전체상품]
      return tree.props.children[3].props.children[1].key;
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

  it("성공: withdrawn=1이면 탈퇴 완료 안내가 보인다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome({ withdrawn: "1" });
    expect(screen.getByText("탈퇴가 완료되었어요")).toHaveAttribute("role", "status");
  });

  it("실패: withdrawn 값이 없거나 1이 아니면 안내가 없다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome({ withdrawn: "0" });
    expect(screen.queryByText("탈퇴가 완료되었어요")).not.toBeInTheDocument();
  });
});

// 카테고리 목록 페이지(?category=...)의 머리, 정렬 줄, 격자
const listWith = (items: ReturnType<typeof item>[], total?: number) => (url: URL) => {
  void url;
  return { status: 200, body: { items, hasMore: false, ...(total === undefined ? {} : { total }) } };
};

describe("Home 카테고리 목록 페이지", () => {
  it("성공: id=all 섹션이 all-title로 이름 붙고, h1에 카테고리 이름이 있으며 h2는 없다", async () => {
    mockBackend({ list: listWith([item("a", "상품A")], 5) });
    await renderHome({ category: "상의" });

    const sections = document.querySelectorAll("section");
    expect(sections).toHaveLength(1);
    expect(sections[0]).toHaveAttribute("id", "all");
    expect(sections[0]).toHaveAttribute("aria-labelledby", "all-title");
    const h1 = screen.getByRole("heading", { level: 1 });
    expect(h1).toHaveAttribute("id", "all-title");
    expect(h1).toHaveTextContent(/^상의$/);
    expect(screen.queryAllByRole("heading", { level: 2 })).toHaveLength(0);
    expect(screen.getByRole("region", { name: "상의" })).toBe(sections[0]);
  });

  it("성공: 머리 줄은 제목과 경로를 한 줄에 놓고 굵은 검은 선으로 끝난다", async () => {
    mockBackend({ list: listWith([item("a", "상품A")], 5) });
    await renderHome({ category: "상의" });

    const header = screen.getByRole("heading", { level: 1 }).parentElement as HTMLElement;
    expect(header).toHaveClass("flex", "flex-wrap", "items-end", "justify-between", "border-b-2", "border-black", "pb-4");
    expect(header).toContainElement(screen.getByRole("navigation", { name: "현재 위치" }));
  });

  it("성공: 경로는 홈 링크, 숨겨진 ›, '카테고리(총 N개)' 세 항목이고 N은 천 단위 쉼표가 붙는다", async () => {
    mockBackend({ list: listWith([item("a", "상품A")], 1234) });
    await renderHome({ category: "상의" });

    const nav = screen.getByRole("navigation", { name: "현재 위치" });
    const lis = within(nav).getAllByRole("listitem", { hidden: true });
    expect(nav.querySelectorAll("ol")).toHaveLength(1);
    expect(lis).toHaveLength(3);
    expect(within(lis[0]).getByRole("link", { name: "홈" })).toHaveAttribute("href", "/");
    expect(lis[1]).toHaveAttribute("aria-hidden", "true");
    expect(lis[1]).toHaveTextContent(/^›$/);
    expect(lis[2].textContent).toBe("상의(총 1,234개)");
    expect(within(lis[2]).queryByRole("link")).not.toBeInTheDocument();
  });

  it("성공: 총 개수가 999 이하이면 쉼표 없이, 0이면 (총 0개)로 보인다", async () => {
    mockBackend({ list: listWith([item("a", "상품A")], 999) });
    await renderHome({ category: "상의" });
    expect(screen.getByText("상의(총 999개)")).toBeInTheDocument();

    document.body.innerHTML = "";
    mockBackend({ list: listWith([], 0) });
    await renderHome({ category: "상의" });
    expect(screen.getByText("상의(총 0개)")).toBeInTheDocument();
  });

  it("실패: total이 없는 옛 서버 응답이면 카테고리 이름만 있고 (총 은 없지만 격자는 그려진다", async () => {
    mockBackend({ list: listWith([item("a", "상품A")]) });
    await renderHome({ category: "상의" });

    const nav = screen.getByRole("navigation", { name: "현재 위치" });
    const lis = within(nav).getAllByRole("listitem", { hidden: true });
    expect(lis[2].textContent).toBe("상의");
    expect(document.body.textContent).not.toContain("(총");
    expect(screen.getByText("상품A")).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "정렬" })).toBeInTheDocument();
  });

  it("성공: 상품이 있으면 listing 정렬 줄이 격자보다 앞에 오고 링크가 category, sort, #all을 담는다", async () => {
    const calls = mockBackend({ list: listWith([item("a", "상품A")], 1) });
    await renderHome({ category: "상의", sort: "price_asc" });

    const sort = screen.getByRole("navigation", { name: "정렬" });
    expect(sort).toHaveClass("mb-8", "mt-4"); // listing variant
    const grid = document.querySelector("section#all .grid") as HTMLElement;
    expect(grid).toHaveClass("lg:grid-cols-4", "gap-y-10");
    expect(sort.compareDocumentPosition(grid) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    const enc = encodeURIComponent("상의");
    expect(screen.getByRole("link", { name: /최신 등록순/ })).toHaveAttribute("href", `/?category=${enc}&sort=newest#all`);
    expect(screen.getByRole("link", { name: /낮은 가격순/ })).toHaveAttribute("aria-current", "page");
    for (const link of within(sort).getAllByRole("link")) {
      const href = link.getAttribute("href") as string;
      expect(href.startsWith(`/?category=${enc}&sort=`)).toBe(true);
      expect(href.endsWith("#all")).toBe(true);
    }
    expect(calls).toHaveLength(1);
    expect(calls[0].searchParams.get("sort")).toBe("price_asc");
  });

  it("성공: 격자 key는 `${category}|${sort}` 이고 정렬이 바뀌면 달라진다", async () => {
    mockBackend({ list: listWith([item("a", "상품A")], 1) });
    const gridKey = async (query: Record<string, string>) => {
      const tree = (await Home({ searchParams: Promise.resolve(query) })) as ReactElement<{
        children: [ReactElement, ReactElement<{ children: ReactElement[] }>];
      }>;
      // section > [header div, fragment > [SortTabs, InfiniteProductGrid]]
      return tree.props.children[1].props.children[1].key;
    };
    expect(await gridKey({ category: "상의", sort: "newest" })).toBe("상의|newest");
    expect(await gridKey({ category: "상의" })).toBe("상의|popular");
    expect(await gridKey({ category: "신발" })).not.toBe(await gridKey({ category: "상의" }));
  });

  it("실패: 상품이 없는 카테고리는 머리는 남기고 안내 문구만 보이며 정렬 줄과 격자는 없다", async () => {
    mockBackend({ list: listWith([], 0) });
    await renderHome({ category: "상의" });

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("상의");
    expect(screen.getByRole("navigation", { name: "현재 위치" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("이 카테고리에는 아직 상품이 없어요");
    expect(screen.queryByRole("navigation", { name: "정렬" })).not.toBeInTheDocument();
    expect(document.querySelector(".grid")).toBeNull();
    expect(screen.queryByText("상품이 없습니다.")).not.toBeInTheDocument();
  });

  it("실패: 상품이 있으면 빈 카테고리 안내는 나오지 않는다", async () => {
    mockBackend({ list: listWith([item("a", "상품A")], 1) });
    await renderHome({ category: "상의" });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.queryByText("이 카테고리에는 아직 상품이 없어요")).not.toBeInTheDocument();
  });

  it("실패: 목록에 없는 category는 홈 화면으로 돌아가고 목록 페이지 머리가 없다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: listWith([item("a", "상품A")], 9) });
    await renderHome({ category: "없는분류" });
    expect(screen.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "현재 위치" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)).toEqual([
      "베스트 상품",
      "신상품",
      "전체상품",
    ]);
  });

  it("성공: 홈 화면의 전체상품은 예전 정렬 줄과 5열 격자 그대로이고 경로 머리가 없다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: listWith([item("a", "상품A")], 9) });
    await renderHome({ sort: "price_asc" });

    expect(screen.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "현재 위치" })).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain("(총");
    const all = within(document.querySelector("section#all") as HTMLElement);
    const link = all.getByRole("link", { name: "낮은 가격순" });
    expect(link).toHaveAttribute("aria-current", "true");
    expect(all.queryByText("✓", { exact: false })).not.toBeInTheDocument();
    expect(all.getByRole("navigation", { name: "정렬" })).toHaveClass("border-b");
    const grid = document.querySelector("section#all .grid") as HTMLElement;
    expect(grid).toHaveClass("sm:grid-cols-3", "lg:grid-cols-5", "gap-y-8");
    expect(grid).not.toHaveClass("lg:grid-cols-4");
  });
});

// 메인 페이지의 베스트·신상품 "전체보기" 링크는 각자의 목록 페이지로 가고, 메인 페이지의 탭은 그대로다
describe("Home 전체보기 링크와 섹션 탭", () => {
  const viewAll = (section: string) =>
    within(document.querySelector(`section#${section}`) as HTMLElement).getByRole("link", { name: "전체보기 →" });

  it("성공: 베스트 전체보기는 /best, 신상품 전체보기는 /new 이고 예전 /?sort=...#all 이 아니다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome();
    expect(viewAll("best")).toHaveAttribute("href", "/best");
    expect(viewAll("new")).toHaveAttribute("href", "/new");
    expect(viewAll("best").getAttribute("href")).not.toContain("#");
    expect(viewAll("new").getAttribute("href")).not.toContain("#");
  });

  it.each([["weekly"], ["monthly"], ["realtime"]])(
    "성공: URL 에 ?best=%s 가 있으면 베스트 전체보기가 그 기간을 /best?best=%s 로 이어간다",
    async (best) => {
      mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
      await renderHome({ best });
      expect(viewAll("best")).toHaveAttribute("href", `/best?best=${best}`);
      expect(viewAll("new")).toHaveAttribute("href", "/new");
    }
  );

  it("실패: 정렬 선택(?sort=)은 전체보기 링크에 섞이지 않는다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome({ sort: "price_asc" });
    expect(viewAll("best")).toHaveAttribute("href", "/best");
    expect(viewAll("new")).toHaveAttribute("href", "/new");
  });

  it("실패: 목록에 없는 best 값은 URL 에서 온 글자가 링크에 그대로 실리지 않는다", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome({ best: "<b>x</b>" });
    expect(viewAll("best").getAttribute("href")).not.toContain("<b>");
    expect(viewAll("best").getAttribute("href")).toMatch(/^\/best(\?best=realtime)?$/);
  });

  it("성공: 메인 페이지의 베스트 탭과 정렬 탭은 예전 앵커 링크 그대로이다 (자기 페이지로 가지 않는다)", async () => {
    mockBackend({ best: () => ({ status: 200, body: { items: [] } }), list: okList });
    await renderHome({ best: "weekly", sort: "price_asc" });
    const bestNav = screen.getByRole("navigation", { name: "베스트 기간" });
    expect(bestNav).not.toHaveClass("mt-4"); // 예전 모양
    expect(within(bestNav).getByRole("link", { name: "월간" })).toHaveAttribute(
      "href",
      "/?sort=price_asc&best=monthly#best"
    );
    const sortNav = screen.getByRole("navigation", { name: "정렬" });
    expect(sortNav).toHaveClass("mb-6", "border-b"); // 예전 모양
    expect(within(sortNav).getByRole("link", { name: "높은 가격순" })).toHaveAttribute(
      "href",
      "/?sort=price_desc&best=weekly#all"
    );
    for (const link of [...within(bestNav).getAllByRole("link"), ...within(sortNav).getAllByRole("link")]) {
      const href = link.getAttribute("href") as string;
      expect(href.startsWith("/?")).toBe(true);
      expect(href).toContain("#");
    }
  });

  it("성공: 카테고리 페이지에는 베스트·신상품 전체보기 링크가 없다", async () => {
    mockBackend({ list: okList });
    await renderHome({ category: "상의" });
    expect(screen.queryByRole("link", { name: "전체보기 →" })).not.toBeInTheDocument();
  });
});
