import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  listProducts,
  listBestProducts,
  getProduct,
  listAdminProducts,
  getOrder,
  listReviews,
  askChat,
  AuthError,
} from "./api";

function mockFetchOnce(status: number, body: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    })
  );
}

beforeEach(() => {
  vi.unstubAllGlobals();
});

describe("listProducts", () => {
  it("성공: 상품 목록과 hasMore를 반환한다", async () => {
    mockFetchOnce(200, {
      items: [{ id: "1", name: "티셔츠", price: 1000, imageUrl: "x" }],
      hasMore: true,
    });
    const page = await listProducts();
    expect(page.items).toHaveLength(1);
    expect(page.items[0].name).toBe("티셔츠");
    expect(page.hasMore).toBe(true);
  });

  it("실패: 서버 오류면 예외를 던진다", async () => {
    mockFetchOnce(500, { error: "서버 오류" });
    await expect(listProducts()).rejects.toThrow("상품 목록을 불러오지 못했습니다.");
  });
});

describe("getProduct", () => {
  it("성공: 상품 상세를 반환한다", async () => {
    mockFetchOnce(200, { id: "1", name: "티셔츠", price: 1000, imageUrl: "x", description: "d", stock: 5 });
    const product = await getProduct("1");
    expect(product?.stock).toBe(5);
  });

  it("실패: 404면 null을 반환한다 (예외가 아님)", async () => {
    mockFetchOnce(404, { error: "not found" });
    const product = await getProduct("missing");
    expect(product).toBeNull();
  });

  it("실패: 500이면 예외를 던진다", async () => {
    mockFetchOnce(500, {});
    await expect(getProduct("1")).rejects.toThrow("상품 정보를 불러오지 못했습니다.");
  });
});

describe("listAdminProducts", () => {
  it("성공: 토큰이 유효하면 상품 목록을 반환한다", async () => {
    mockFetchOnce(200, [{ id: "1", name: "티셔츠", price: 1000, stock: 5, createdAt: "2026-01-01" }]);
    const products = await listAdminProducts("valid-token");
    expect(products).toHaveLength(1);
  });

  it("실패: 401이면 AuthError를 던진다", async () => {
    mockFetchOnce(401, {});
    await expect(listAdminProducts("bad-token")).rejects.toBeInstanceOf(AuthError);
  });

  it("실패: 403이면 AuthError를 던진다 (권한 없는 역할)", async () => {
    mockFetchOnce(403, {});
    await expect(listAdminProducts("user-token")).rejects.toBeInstanceOf(AuthError);
  });
});

describe("getOrder", () => {
  it("성공: 주문 상세를 반환한다", async () => {
    mockFetchOnce(200, {
      id: "o1",
      totalAmount: 1000,
      customerName: "홍길동",
      customerPhone: "010",
      customerAddress: "서울",
      items: [],
    });
    const order = await getOrder("o1");
    expect(order?.customerName).toBe("홍길동");
  });

  it("실패: 404면 null을 반환한다", async () => {
    mockFetchOnce(404, {});
    expect(await getOrder("missing")).toBeNull();
  });
});

describe("listReviews", () => {
  it("성공: 평균 별점과 리뷰 목록을 반환한다", async () => {
    mockFetchOnce(200, { averageRating: 4.5, reviewCount: 2, reviews: [] });
    const result = await listReviews("1");
    expect(result?.averageRating).toBe(4.5);
  });

  it("실패: 상품이 없으면 null을 반환한다", async () => {
    mockFetchOnce(404, {});
    expect(await listReviews("missing")).toBeNull();
  });

  it("실패: 500이면 예외를 던진다", async () => {
    mockFetchOnce(500, {});
    await expect(listReviews("1")).rejects.toThrow("리뷰를 불러오지 못했습니다.");
  });
});

describe("askChat", () => {
  it("성공: 답변과 추천 질문을 반환하고 메시지를 POST로 보낸다", async () => {
    mockFetchOnce(200, { answer: "배송은 2~3일 걸려요.", suggestions: ["반품은?"] });
    const res = await askChat("배송");
    expect(res.answer).toBe("배송은 2~3일 걸려요.");
    expect(res.suggestions).toEqual(["반품은?"]);
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toMatch(/\/api\/chat$/);
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ message: "배송" });
  });

  it("실패: 400이면 서버가 준 error 메시지로 예외를 던진다", async () => {
    mockFetchOnce(400, { error: "메시지는 1~200자여야 합니다." });
    await expect(askChat("")).rejects.toThrow("메시지는 1~200자여야 합니다.");
  });
});

describe("askChat 응답 검증", () => {
  it("실패: 오류 본문이 JSON이 아니면 기본 메시지로 예외를 던진다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        json: async () => {
          throw new SyntaxError("not json");
        },
      })
    );
    await expect(askChat("a")).rejects.toThrow("답변을 가져오지 못했습니다.");
  });

  it("실패: 네트워크 오류는 그대로 전달된다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    await expect(askChat("a")).rejects.toThrow("network");
  });

  it("실패: answer가 비어 있거나 suggestions 형식이 틀리면 예외를 던진다", async () => {
    for (const body of [
      {},
      { answer: "", suggestions: [] },
      { answer: "  ", suggestions: [] },
      { answer: 1, suggestions: [] },
      { answer: "a", suggestions: "x" },
      { answer: "a", suggestions: [1] },
    ]) {
      mockFetchOnce(200, body);
      await expect(askChat("a")).rejects.toThrow("답변을 가져오지 못했습니다.");
    }
  });

  it("성공: suggestions가 없으면 빈 배열로 채운다", async () => {
    mockFetchOnce(200, { answer: "a" });
    expect(await askChat("a")).toEqual({ answer: "a", suggestions: [] });
  });
});

function mockFetchSpy(status: number, body: unknown) {
  const fn = vi.fn().mockResolvedValue({ ok: status >= 200 && status < 300, status, json: async () => body });
  vi.stubGlobal("fetch", fn);
  return fn;
}

describe("listProducts discount", () => {
  it("성공: sort=discount를 쿼리에 넣고 할인 필드를 그대로 돌려준다", async () => {
    mockFetchOnce(200, {
      items: [{ id: "1", name: "a", price: 8000, imageUrl: "x", originalPrice: 10000, discountRate: 20, hashtags: ["여름"] }],
      hasMore: false,
    });
    const page = await listProducts(undefined, 0, 8, "discount");
    expect(new URL(String(vi.mocked(fetch).mock.calls[0][0])).searchParams.get("sort")).toBe("discount");
    expect(page.items[0]).toMatchObject({ originalPrice: 10000, discountRate: 20, hashtags: ["여름"] });
  });

  it("실패: 옛 백엔드 응답(할인 필드 없음)도 그대로 받는다", async () => {
    mockFetchOnce(200, { items: [{ id: "1", name: "a", price: 8000, imageUrl: "x" }], hasMore: false });
    const page = await listProducts();
    expect(page.items[0].discountRate).toBeUndefined();
    expect(page.items[0].hashtags).toBeUndefined();
  });
});

describe("listProducts sort", () => {
  it("성공: sort를 주면 쿼리에 sort가 들어간다", async () => {
    const fetchMock = mockFetchSpy(200, { items: [], hasMore: false });
    await listProducts("상의", 2, 20, "price_desc");
    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.pathname).toBe("/api/products");
    expect(url.searchParams.get("sort")).toBe("price_desc");
    expect(url.searchParams.get("category")).toBe("상의");
    expect(url.searchParams.get("page")).toBe("2");
    expect(url.searchParams.get("size")).toBe("20");
  });

  it("성공: sort를 생략하면 쿼리에 sort가 없다 (기존 호출 유지)", async () => {
    const fetchMock = mockFetchSpy(200, { items: [], hasMore: false });
    await listProducts();
    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.searchParams.has("sort")).toBe(false);
    expect(url.searchParams.get("size")).toBe("8");
  });
});

describe("listBestProducts", () => {
  it("성공: best 엔드포인트에 period와 size를 보내고 items를 반환한다", async () => {
    const fetchMock = mockFetchSpy(200, {
      items: [{ id: "1", name: "티셔츠", price: 1000, imageUrl: "x" }],
    });
    const items = await listBestProducts("weekly", 4);
    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.pathname).toBe("/api/products/best");
    expect(url.searchParams.get("period")).toBe("weekly");
    expect(url.searchParams.get("size")).toBe("4");
    expect(items).toHaveLength(1);
    expect(items[0].name).toBe("티셔츠");
  });

  it("실패: 400/404/500이면 예외를 던진다", async () => {
    for (const status of [400, 404, 500]) {
      mockFetchOnce(status, { error: "x" });
      await expect(listBestProducts("realtime")).rejects.toThrow("베스트 상품을 불러오지 못했습니다.");
    }
  });

  it("실패: 응답에 items 배열이 없으면 예외를 던진다", async () => {
    mockFetchOnce(200, { something: "else" });
    await expect(listBestProducts("monthly")).rejects.toThrow("베스트 상품을 불러오지 못했습니다.");
  });

  it("실패: 네트워크 오류는 그대로 예외로 전달된다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    await expect(listBestProducts("realtime")).rejects.toThrow("network");
  });
});
