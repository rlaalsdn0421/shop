import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import BestPage from "./page";

// The heart on each card is covered by WishButton.test.tsx.
vi.mock("@/components/WishButton", () => ({ WishButton: () => null }));

const item = (id: string, name: string) => ({
  id,
  name,
  price: 10000,
  imageUrl: "https://example.com/x.jpg",
});

const items = (n: number) => Array.from({ length: n }, (_, i) => item(`b${i + 1}`, `베스트${i + 1}`));

type Reply = { status: number; body: unknown } | Promise<never>;

function mockBest(reply: Reply | (() => Reply)) {
  const calls: URL[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string) => {
      const url = new URL(String(input));
      calls.push(url);
      const { status, body } = await (typeof reply === "function" ? reply() : reply);
      return { ok: status < 300, status, json: async () => body };
    })
  );
  return calls;
}

const ok = (list: ReturnType<typeof item>[]): Reply => ({ status: 200, body: { items: list } });

type Query = Record<string, string | string[] | undefined>;
const getPage = (query: Query = {}) => BestPage({ searchParams: Promise.resolve(query) });
async function renderPage(query: Query = {}) {
  render(await getPage(query));
}

const totalText = () =>
  within(screen.getByRole("navigation", { name: "현재 위치" })).getAllByRole("listitem", { hidden: true })[2].textContent;

beforeEach(() => {
  vi.unstubAllGlobals();
});

describe("/best 베스트 페이지", () => {
  it("성공: 기본은 실시간 기간, 최대 20개를 한 번만 요청한다", async () => {
    const calls = mockBest(ok(items(2)));
    await renderPage();
    expect(calls).toHaveLength(1);
    expect(calls[0].pathname).toBe("/api/products/best");
    expect(calls[0].searchParams.get("period")).toBe("realtime");
    expect(calls[0].searchParams.get("size")).toBe("20");
  });

  it.each([["weekly"], ["monthly"], ["realtime"]])("성공: ?best=%s 를 요청 기간으로 쓴다", async (best) => {
    const calls = mockBest(ok(items(1)));
    await renderPage({ best });
    expect(calls[0].searchParams.get("period")).toBe(best);
  });

  it("실패: 알 수 없는 best 는 실시간으로 돌아가고 요청에도 그 값이 새지 않는다", async () => {
    const calls = mockBest(ok(items(1)));
    await renderPage({ best: "yearly" });
    expect(calls[0].searchParams.get("period")).toBe("realtime");
    expect(screen.getByRole("link", { name: "실시간" })).toHaveAttribute("aria-current", "page");
  });

  it("성공: best 가 여러 번 오면 첫 값만 쓴다", async () => {
    const calls = mockBest(ok(items(1)));
    await renderPage({ best: ["monthly", "weekly"] });
    expect(calls[0].searchParams.get("period")).toBe("monthly");
  });

  it("성공: section 은 all-title 로 이름 붙고 pt-2 를 가지며 머리는 h1 '베스트' 이다", async () => {
    mockBest(ok(items(3)));
    await renderPage();
    const sections = document.querySelectorAll("section");
    expect(sections).toHaveLength(1);
    expect(sections[0]).toHaveAttribute("aria-labelledby", "all-title");
    expect(sections[0].className).toContain("pt-2");
    expect(screen.getByRole("region", { name: "베스트" })).toBe(sections[0]);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(/^베스트$/);
  });

  it.each([[1], [3], [20]])("성공: 총 개수는 보이는 상품 수와 같다 (%i개)", async (n) => {
    mockBest(ok(items(n)));
    await renderPage();
    expect(totalText()).toBe(`베스트(총 ${n}개)`);
    expect(document.querySelectorAll("section .grid > div")).toHaveLength(n);
  });

  it("성공: 기간 탭 링크는 /best?best=<값> 이고 고른 기간만 aria-current=page 이다", async () => {
    mockBest(ok(items(1)));
    await renderPage({ best: "weekly" });
    const nav = screen.getByRole("navigation", { name: "베스트 기간" });
    expect(nav).toHaveClass("mb-8", "mt-4");
    expect(within(nav).getAllByRole("link").map((l) => l.getAttribute("href"))).toEqual([
      "/best?best=realtime",
      "/best?best=weekly",
      "/best?best=monthly",
    ]);
    expect(screen.getByRole("link", { name: "주간" })).toHaveAttribute("aria-current", "page");
    expect(within(nav).getAllByRole("link").filter((l) => l.hasAttribute("aria-current"))).toHaveLength(1);
  });

  it("성공: 격자 클래스와 카드 순위는 1부터 순서대로이다", async () => {
    mockBest(ok(items(5)));
    await renderPage();
    const grid = document.querySelector("section .grid") as HTMLElement;
    expect(grid.className.split(/\s+/).sort()).toEqual(
      "grid grid-cols-2 gap-x-4 gap-y-10 md:grid-cols-3 lg:grid-cols-4".split(" ").sort()
    );
    const cards = Array.from(grid.children);
    expect(cards).toHaveLength(5);
    cards.forEach((card, i) => {
      expect(card).toHaveTextContent(`베스트${i + 1}`);
      const badge = card.querySelector("a > span > span.absolute") as HTMLElement;
      expect(badge.textContent).toBe(`순위 ${i + 1}`);
      expect(card.querySelector("a")).toHaveAttribute("href", `/products/b${i + 1}`);
    });
  });

  it("실패: 베스트가 비어 있으면 머리(총 0개)와 탭은 남고 '아직 베스트 상품이 없어요' 안내만 보인다", async () => {
    mockBest(ok([]));
    await renderPage();
    expect(screen.getByRole("status")).toHaveTextContent(/^아직 베스트 상품이 없어요$/);
    expect(totalText()).toBe("베스트(총 0개)");
    expect(screen.getByRole("navigation", { name: "베스트 기간" })).toBeInTheDocument();
    expect(document.querySelector(".grid")).toBeNull();
  });

  it("실패: 상품이 있으면 안내 문구는 나오지 않는다", async () => {
    mockBest(ok(items(2)));
    await renderPage();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it.each([[400], [404], [500]])("실패: 베스트 요청이 HTTP %i 이면 던지지 않고 그 자리에 오류 안내만 보인다", async (status) => {
    mockBest({ status, body: { error: "x" } });
    await expect(getPage()).resolves.toBeTruthy();
    await renderPage();
    expect(screen.getByRole("status")).toHaveTextContent(/^상품을 불러오지 못했어요$/);
    expect(document.querySelector(".grid")).toBeNull();
    expect(screen.queryByText("아직 베스트 상품이 없어요")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain("(총");
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("베스트");
    expect(screen.getByRole("navigation", { name: "베스트 기간" })).toBeInTheDocument();
    expect(totalText()).toBe("베스트");
  });

  it("실패: 네트워크 오류여도 던지지 않고 오류 안내와 탭이 보인다", async () => {
    mockBest(() => Promise.reject(new TypeError("network")));
    await renderPage({ best: "monthly" });
    expect(screen.getByText("상품을 불러오지 못했어요")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "월간" })).toHaveAttribute("aria-current", "page");
  });

  it("실패: 응답 모양이 잘못돼도(items 없음) 던지지 않고 오류 안내가 보인다", async () => {
    mockBest({ status: 200, body: { nothing: true } });
    await renderPage();
    expect(screen.getByText("상품을 불러오지 못했어요")).toBeInTheDocument();
  });
});
