import { describe, it, expect, vi, afterEach } from "vitest";
import type { CartItem } from "@/cart/CartContext";
import type { ProductDetail } from "@/lib/api";
import { fetchCurrentPrices, findPriceChanges } from "./cartPrices";

const line = (over: Partial<CartItem> = {}): CartItem => ({
  productId: "p1",
  name: "린넨 셔츠",
  price: 8000,
  imageUrl: "https://example.com/a.jpg",
  quantity: 1,
  ...over,
});
const detail = (over: Partial<ProductDetail> = {}) => ({ id: "p1", name: "린넨 셔츠", price: 8000, ...over }) as ProductDetail;
const opt = (id: string, price: number) => ({ id, colorName: null, colorHex: null, sizeLabel: null, price, stock: 5 });

describe("findPriceChanges", () => {
  it("성공: 상품 가격이 달라진 옵션 없는 줄을 옛 가격과 새 가격으로 돌려준다", () => {
    expect(findPriceChanges([line()], new Map([["p1", detail({ price: 9000 })]]))).toEqual([
      { productId: "p1", name: "린넨 셔츠", oldPrice: 8000, newPrice: 9000 },
    ]);
  });

  it("실패: 가격이 같으면 아무것도 돌려주지 않는다", () => {
    expect(findPriceChanges([line()], new Map([["p1", detail()]]))).toEqual([]);
  });

  it("성공: 옵션 줄은 상품 가격이 아니라 optionId가 같은 옵션의 가격을 쓰고, 옵션 정보를 담는다", () => {
    const items = [line({ price: 9000, optionId: "o2", optionLabel: "화이트 / L" })];
    const details = new Map([["p1", detail({ price: 1, options: [opt("o1", 9999), opt("o2", 9500)] })]]);
    expect(findPriceChanges(items, details)).toEqual([
      { productId: "p1", optionId: "o2", name: "린넨 셔츠", optionLabel: "화이트 / L", oldPrice: 9000, newPrice: 9500 },
    ]);
  });

  it("성공: 옵션 줄의 가격이 같은 옵션 가격과 같으면, 상품 가격이 달라도 바뀐 것이 아니다", () => {
    const items = [line({ price: 9000, optionId: "o2", optionLabel: "화이트 / L" })];
    const details = new Map([["p1", detail({ price: 1, options: [opt("o2", 9000)] })]]);
    expect(findPriceChanges(items, details)).toEqual([]);
  });

  it("실패: 옵션을 찾지 못하면 그 줄은 건드리지 않는다", () => {
    const items = [line({ optionId: "gone", optionLabel: "블랙 / S" })];
    expect(findPriceChanges(items, new Map([["p1", detail({ price: 1, options: [opt("o1", 5)] })]]))).toEqual([]);
  });

  it("실패: 옵션 줄인데 응답에 options 배열이 없으면 상품 가격으로 대신하지 않고 건드리지 않는다", () => {
    const items = [line({ optionId: "o1", optionLabel: "화이트 / M" })];
    expect(findPriceChanges(items, new Map([["p1", detail({ price: 12345 })]]))).toEqual([]);
  });

  it("실패: 옵션이 있는 상품인데 옵션 없이 담긴 줄은 바뀐 것으로 보지 않는다(서버가 자기 문구로 거절)", () => {
    const details = new Map([["p1", detail({ price: 9999, options: [opt("o1", 9999)] })]]);
    expect(findPriceChanges([line()], details)).toEqual([]);
  });

  it("성공: options가 빈 배열인 상품의 옵션 없는 줄은 상품 가격과 비교한다", () => {
    expect(findPriceChanges([line()], new Map([["p1", detail({ price: 9000, options: [] })]]))).toHaveLength(1);
  });

  it("실패: 상품 정보를 못 받은 줄(맵에 없음)은 건드리지 않는다", () => {
    expect(findPriceChanges([line(), line({ productId: "p2" })], new Map([["p1", detail()]]))).toEqual([]);
  });

  it("성공: 여러 줄 중 바뀐 줄만, 같은 상품의 옵션 줄은 각각 돌려준다", () => {
    const items = [
      line({ productId: "p2", name: "양말", price: 1000 }),
      line({ price: 9000, optionId: "o1", optionLabel: "A" }),
      line({ price: 9000, optionId: "o2", optionLabel: "B" }),
    ];
    const details = new Map([
      ["p1", detail({ options: [opt("o1", 9000), opt("o2", 9100)] })],
      ["p2", detail({ id: "p2", price: 1000 })],
    ]);
    expect(findPriceChanges(items, details).map((c) => [c.productId, c.optionId, c.newPrice])).toEqual([["p1", "o2", 9100]]);
  });

  it("성공: 새 가격이 0원이어도 바뀐 것으로 본다(숫자 0을 없는 값으로 취급하지 않음)", () => {
    expect(findPriceChanges([line()], new Map([["p1", detail({ price: 0 })]]))[0]?.newPrice).toBe(0);
  });
});

function jsonReply(status: number, body?: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

describe("fetchCurrentPrices", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("성공: 서로 다른 상품 id마다 한 번씩만 조회하고 id로 모아 돌려준다", async () => {
    const fetchMock = vi.fn(async (url: string) => jsonReply(200, detail({ id: url.split("/").pop()!, price: 1 })));
    vi.stubGlobal("fetch", fetchMock);
    const result = await fetchCurrentPrices([line(), line({ optionId: "o1" }), line({ productId: "p2" })]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls.map(([url]) => url).sort()).toEqual([
      "http://localhost:8080/api/products/p1",
      "http://localhost:8080/api/products/p2",
    ]);
    expect([...result.keys()].sort()).toEqual(["p1", "p2"]);
  });

  it("실패: 실패한 상품과 404 상품은 결과에서 빠지고 나머지는 남는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url.endsWith("/p2")) throw new TypeError("Failed to fetch");
        if (url.endsWith("/p3")) return jsonReply(404);
        if (url.endsWith("/p4")) return jsonReply(500);
        return jsonReply(200, detail({ id: "p1", price: 7 }));
      })
    );
    const result = await fetchCurrentPrices(["p1", "p2", "p3", "p4"].map((productId) => line({ productId })));
    expect([...result.keys()]).toEqual(["p1"]);
    expect(result.get("p1")?.price).toBe(7);
  });

  it("실패: 5초가 지나도 답이 없는 상품은 빠지고, 5초 안에 온 상품은 남는다", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => (url.endsWith("/slow") ? new Promise(() => {}) : Promise.resolve(jsonReply(200, detail({ id: "fast" })))))
    );
    const pending = fetchCurrentPrices([line({ productId: "fast" }), line({ productId: "slow" })]);
    await vi.advanceTimersByTimeAsync(4_999);
    let settled = false;
    void pending.then(() => (settled = true));
    await vi.advanceTimersByTimeAsync(0);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect([...(await pending).keys()]).toEqual(["fast"]);
  });

  it("실패: 이미 중단된 signal이면 대기 중인 조회를 기다리지 않고 비어 있는 결과를 돌려준다", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const controller = new AbortController();
    const pending = fetchCurrentPrices([line()], controller.signal);
    controller.abort();
    expect((await pending).size).toBe(0);
  });

  it("성공: 요청마다 자기 signal을 fetch에 넘기고, 5초가 지나면 그 signal이 중단된다(요청 자체를 취소)", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<unknown>>(() => new Promise(() => {}));
    vi.stubGlobal("fetch", fetchMock);
    const pending = fetchCurrentPrices([line(), line({ productId: "p2" })]);
    const signals = fetchMock.mock.calls.map(([, init]) => init?.signal as AbortSignal);
    expect(signals).toHaveLength(2);
    expect(signals[0]).not.toBe(signals[1]);
    await vi.advanceTimersByTimeAsync(4_999);
    expect(signals.map((s) => s.aborted)).toEqual([false, false]);
    await vi.advanceTimersByTimeAsync(1);
    expect(signals.map((s) => s.aborted)).toEqual([true, true]);
    expect((await pending).size).toBe(0);
  });

  it("성공: 호출한 쪽이 중단하면 진행 중인 요청의 signal도 바로 중단된다", async () => {
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<unknown>>(() => new Promise(() => {}));
    vi.stubGlobal("fetch", fetchMock);
    const caller = new AbortController();
    const pending = fetchCurrentPrices([line()], caller.signal);
    const sent = fetchMock.mock.calls[0][1]?.signal as AbortSignal;
    expect(sent.aborted).toBe(false);
    caller.abort();
    expect(sent.aborted).toBe(true);
    expect((await pending).size).toBe(0);
  });

  it("실패: 이미 중단된 signal로 부르면 요청의 signal도 처음부터 중단돼 있다", async () => {
    // Like the real fetch, this one rejects at once when it is handed a signal that is already aborted.
    const fetchMock = vi.fn((...args: [string, RequestInit?]) =>
      args[1]?.signal?.aborted ? Promise.reject(new DOMException("aborted", "AbortError")) : new Promise(() => {})
    );
    vi.stubGlobal("fetch", fetchMock);
    const caller = new AbortController();
    caller.abort();
    expect((await fetchCurrentPrices([line()], caller.signal)).size).toBe(0);
    expect((fetchMock.mock.calls[0][1]?.signal as AbortSignal).aborted).toBe(true);
  });

  it("성공: 정상 응답 뒤에는 5초 타이머가 남지 않아 나중에 signal이 중단되지 않는다", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<unknown>>(async () => jsonReply(200, detail()));
    vi.stubGlobal("fetch", fetchMock);
    await fetchCurrentPrices([line()]);
    const sent = fetchMock.mock.calls[0][1]?.signal as AbortSignal;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(sent.aborted).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("성공: signal을 주고 중단하지 않으면 결과를 그대로 돌려준다", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonReply(200, detail())));
    const result = await fetchCurrentPrices([line()], new AbortController().signal);
    expect(result.get("p1")?.price).toBe(8000);
  });

  it("성공: 담긴 줄이 없으면 요청하지 않는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    expect((await fetchCurrentPrices([])).size).toBe(0);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
