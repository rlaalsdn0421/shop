import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { InfiniteProductGrid } from "./InfiniteProductGrid";

const item = (id: string, name = `상품${id}`) => ({
  id,
  name,
  price: 1000,
  imageUrl: "https://example.com/x.jpg",
});

let intersect: () => void;
// When true the fake observer reports the sentinel as visible as soon as it is observed,
// like a real IntersectionObserver whose target is already on screen.
let autoFire = false;

beforeEach(() => {
  vi.unstubAllGlobals();
  autoFire = false;
  vi.stubGlobal(
    "IntersectionObserver",
    class {
      cb: (entries: { isIntersecting: boolean }[]) => void;
      constructor(cb: (entries: { isIntersecting: boolean }[]) => void) {
        this.cb = cb;
        intersect = () => cb([{ isIntersecting: true }]);
      }
      observe() {
        if (autoFire) this.cb([{ isIntersecting: true }]);
      }
      disconnect() {}
    }
  );
});

function mockFetch(status: number, body: unknown) {
  const fn = vi.fn().mockResolvedValue({ ok: status < 300, status, json: async () => body });
  vi.stubGlobal("fetch", fn);
  return fn;
}

describe("InfiniteProductGrid", () => {
  it("성공: 끝에 닿으면 sort와 category를 담아 다음 페이지를 요청하고 이어 붙인다", async () => {
    const fetchMock = mockFetch(200, { items: [item("2")], hasMore: false });
    render(
      <InfiniteProductGrid initialItems={[item("1")]} initialHasMore category="상의" sort="price_asc" />
    );
    act(() => intersect());
    expect(await screen.findByText("상품2")).toBeInTheDocument();
    expect(screen.getByText("상품1")).toBeInTheDocument();

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.pathname).toBe("/api/products");
    expect(url.searchParams.get("sort")).toBe("price_asc");
    expect(url.searchParams.get("category")).toBe("상의");
    expect(url.searchParams.get("page")).toBe("1");
    expect(url.searchParams.get("size")).toBe("20");
  });

  it("성공: key가 바뀌면(정렬 변경) 목록이 새 초기값으로 리셋되고 새 sort로 요청한다", async () => {
    const fetchMock = mockFetch(200, { items: [], hasMore: false });
    const { rerender } = render(
      <InfiniteProductGrid key="|popular" initialItems={[item("1")]} initialHasMore sort="popular" />
    );
    rerender(
      <InfiniteProductGrid key="|newest" initialItems={[item("9")]} initialHasMore sort="newest" />
    );
    expect(screen.queryByText("상품1")).not.toBeInTheDocument();
    expect(screen.getByText("상품9")).toBeInTheDocument();

    act(() => intersect());
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.searchParams.get("sort")).toBe("newest");
    expect(url.searchParams.get("page")).toBe("1");
  });

  it("실패: 더 불러올 게 없으면 요청하지 않는다", () => {
    const fetchMock = mockFetch(200, { items: [], hasMore: false });
    render(<InfiniteProductGrid initialItems={[item("1")]} initialHasMore={false} sort="popular" />);
    act(() => intersect());
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("실패: 요청이 실패해도 기존 목록은 유지된다", async () => {
    const fetchMock = mockFetch(500, {});
    render(<InfiniteProductGrid initialItems={[item("1")]} initialHasMore sort="popular" />);
    act(() => intersect());
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.getByText("상품1")).toBeInTheDocument();
  });

  it("실패: 상품이 하나도 없으면 안내 문구를 보여준다", () => {
    render(<InfiniteProductGrid initialItems={[]} initialHasMore={false} sort="popular" />);
    expect(screen.getByText("상품이 없습니다.")).toBeInTheDocument();
  });

  it("성공: 두 번째 요청은 page=2 이고 중복된 상품은 한 번만 보인다", async () => {
    autoFire = true;
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ items: [item("1"), item("2")], hasMore: true }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ items: [item("3")], hasMore: false }),
      });
    vi.stubGlobal("fetch", fetchMock);
    render(<InfiniteProductGrid initialItems={[item("1")]} initialHasMore sort="popular" />);

    expect(await screen.findByText("상품3")).toBeInTheDocument();
    expect(screen.getAllByText("상품1")).toHaveLength(1);
    expect(screen.getAllByText("상품2")).toHaveLength(1);
    const pages = fetchMock.mock.calls.map((c) => new URL(String(c[0])).searchParams.get("page"));
    expect(pages).toEqual(["1", "2"]);
    expect(new URL(String(fetchMock.mock.calls[1][0])).searchParams.get("size")).toBe("20");
  });

  it("실패: 요청이 실패하면 자동 재시도를 멈추고 안내와 다시 시도 버튼을 보여준다", async () => {
    autoFire = true;
    const fetchMock = mockFetch(500, {});
    render(<InfiniteProductGrid initialItems={[item("1")]} initialHasMore sort="popular" />);

    expect(await screen.findByText("더 불러오지 못했어요")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("성공: 다시 시도를 누르면 같은 페이지를 다시 요청하고 성공하면 안내가 사라진다", async () => {
    autoFire = true;
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({}) })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ items: [item("2")], hasMore: false }),
      });
    vi.stubGlobal("fetch", fetchMock);
    render(<InfiniteProductGrid initialItems={[item("1")]} initialHasMore sort="popular" />);

    await userEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("상품2")).toBeInTheDocument();
    expect(screen.queryByText("더 불러오지 못했어요")).not.toBeInTheDocument();
    const pages = fetchMock.mock.calls.map((c) => new URL(String(c[0])).searchParams.get("page"));
    expect(pages).toEqual(["1", "1"]);
  });
});
