import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  listProducts,
  listBestProducts,
  getProduct,
  listAdminProducts,
  getOrder,
  listMyOrders,
  listReviews,
  askChat,
  withdrawAccount,
  completeOrderPayment,
  cancelOrder,
  listAdminOrders,
  shipOrder,
  deliverOrder,
  refundOrder,
  OrderActionError,
  AuthError,
  PaymentError,
  WithdrawError,
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
    const order = await getOrder("o1", "valid-token");
    expect(order?.customerName).toBe("홍길동");
    expect(vi.mocked(fetch).mock.calls[0][1]?.headers).toMatchObject({ Authorization: "Bearer valid-token" });
    expect(vi.mocked(fetch).mock.calls[0][1]?.signal).toHaveProperty("aborted", false);
  });

  it("실패: 404면 null을 반환한다", async () => {
    mockFetchOnce(404, {});
    expect(await getOrder("missing", "valid-token")).toBeNull();
  });

  it("실패: 401이면 AuthError를 던진다", async () => {
    mockFetchOnce(401, {});
    await expect(getOrder("o1", "bad-token")).rejects.toBeInstanceOf(AuthError);
  });

  it("실패: 500이면 예외를 던진다", async () => {
    mockFetchOnce(500, {});
    await expect(getOrder("o1", "valid-token")).rejects.toThrow("주문 정보를 불러오지 못했습니다.");
  });
});

describe("listMyOrders", () => {
  const summary = { id: "o1", createdAt: "2026-10-11T03:00:00Z", totalAmount: 1000, status: "PAID", itemCount: 1, firstItemName: "셔츠" };

  it("성공: cursor 없이 첫 페이지를 요청하고 토큰·timeout signal을 싣는다", async () => {
    mockFetchOnce(200, { orders: [summary], nextCursor: "next" });
    const page = await listMyOrders("valid-token");
    expect(page).toEqual({ orders: [summary], nextCursor: "next" });
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toMatch(/\/api\/orders\?size=10$/);
    expect(init?.headers).toMatchObject({ Authorization: "Bearer valid-token" });
    expect(init?.signal).toHaveProperty("aborted", false);
  });

  it("성공: cursor는 URL 인코딩해서 보낸다", async () => {
    mockFetchOnce(200, { orders: [], nextCursor: null });
    await listMyOrders("valid-token", "a+b/c=d e");
    expect(String(vi.mocked(fetch).mock.calls[0][0])).toMatch(/\?size=10&cursor=a%2Bb%2Fc%3Dd%20e$/);
  });

  it("실패: 401/403이면 AuthError를 던진다", async () => {
    for (const status of [401, 403]) {
      mockFetchOnce(status, {});
      await expect(listMyOrders("bad-token")).rejects.toBeInstanceOf(AuthError);
    }
  });

  it("실패: 400/500이거나 orders 배열이 없으면 예외를 던진다", async () => {
    for (const [status, body] of [[400, { error: "x" }], [500, {}], [200, { something: 1 }]] as const) {
      mockFetchOnce(status, body);
      await expect(listMyOrders("valid-token")).rejects.toThrow("주문 내역을 불러오지 못했습니다.");
    }
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

describe("withdrawAccount", () => {
  function stubReply(status: number, json: () => Promise<unknown>) {
    const fetchMock = vi.fn().mockResolvedValue({ ok: status >= 200 && status < 300, status, json });
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
  }

  it("성공: DELETE /api/users/me 에 토큰과 비밀번호를 보내고 204면 끝난다", async () => {
    const fetchMock = stubReply(204, async () => {
      throw new Error("no body");
    });
    await expect(withdrawAccount("fake-token", "pw-1234")).resolves.toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe("/api/users/me");
    expect(init.method).toBe("DELETE");
    expect(init.headers).toMatchObject({ Authorization: "Bearer fake-token", "Content-Type": "application/json" });
    expect(JSON.parse(init.body)).toEqual({ password: "pw-1234" });
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it("실패: 401은 AuthError가 아닌 WithdrawError(status 401)로 서버 문구를 담는다", async () => {
    stubReply(401, async () => ({ error: "아이디 또는 비밀번호가 올바르지 않습니다." }));
    const err = await withdrawAccount("t", "bad").catch((e) => e);
    expect(err).toBeInstanceOf(WithdrawError);
    expect(err).not.toBeInstanceOf(AuthError);
    expect(err.status).toBe(401);
    expect(err.message).toBe("아이디 또는 비밀번호가 올바르지 않습니다.");
  });

  it("성공: 응답 본문의 code를 WithdrawError.code에 담고, 없으면 undefined다", async () => {
    stubReply(401, async () => ({ error: "인증이 필요합니다.", code: "AUTH_REQUIRED" }));
    await expect(withdrawAccount("t", "p")).rejects.toMatchObject({ status: 401, code: "AUTH_REQUIRED" });
    stubReply(401, async () => ({ error: "아이디 또는 비밀번호가 올바르지 않습니다." }));
    const err = await withdrawAccount("t", "p").catch((e) => e);
    expect(err.code).toBeUndefined();
  });

  it("실패: 403/429/500은 각 status를 가진 WithdrawError다", async () => {
    for (const status of [403, 429, 500]) {
      stubReply(status, async () => ({ error: "x" }));
      await expect(withdrawAccount("t", "p")).rejects.toMatchObject({ status });
    }
  });

  it("실패: 응답이 JSON이 아니어도 기본 문구로 WithdrawError를 던진다", async () => {
    stubReply(502, async () => {
      throw new SyntaxError("not json");
    });
    await expect(withdrawAccount("t", "p")).rejects.toMatchObject({
      status: 502,
      message: "회원 탈퇴에 실패했습니다.",
    });
  });

  it("실패: 네트워크 오류는 그대로 전달된다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    await expect(withdrawAccount("t", "p")).rejects.toThrow("network");
  });
});

describe("completeOrderPayment", () => {
  function stubReply(status: number, json: () => Promise<unknown>) {
    const fetchMock = vi.fn().mockResolvedValue({ ok: status >= 200 && status < 300, status, json });
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
  }

  it("성공: POST /api/orders/{id}/payment/complete 에 토큰을 보내고 서버가 확인한 status를 돌려준다", async () => {
    const fetchMock = stubReply(200, async () => ({ status: "PAID" }));
    await expect(completeOrderPayment("a/b", "fake-token")).resolves.toBe("PAID");
    const [url, init] = fetchMock.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe("/api/orders/a%2Fb/payment/complete");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it("실패: 401/403은 AuthError다", async () => {
    for (const status of [401, 403]) {
      stubReply(status, async () => ({}));
      await expect(completeOrderPayment("o1", "t")).rejects.toBeInstanceOf(AuthError);
    }
  });

  it.each([
    ["PAYMENT_NOT_CONFIRMED", 409, "아직 결제가 확인되지 않았어요. 잠시 뒤 다시 확인해 주세요."],
    ["PAYMENT_AMOUNT_MISMATCH", 409, "결제 금액이 주문 금액과 달라 확인이 필요해요. 고객센터로 문의해 주세요."],
    ["PAYMENT_NEEDS_REVIEW", 409, "결제는 확인됐지만 재고 문제로 주문을 확정하지 못했어요. 확인 후 취소·환불을 안내해 드릴게요."],
    ["PAYMENT_PROVIDER_UNAVAILABLE", 503, "결제 확인이 지연되고 있어요. 잠시 뒤 다시 확인해 주세요."],
    ["PAYMENT_NOT_CONFIGURED", 503, "결제 준비 중이에요. 잠시 후 다시 시도해 주세요."],
  ])("실패: %s 는 PaymentError(code)와 한국어 문구다", async (code, status, message) => {
    stubReply(status, async () => ({ error: "raw", code }));
    await expect(completeOrderPayment("o1", "t")).rejects.toMatchObject({ status, code, message });
    await expect(completeOrderPayment("o1", "t")).rejects.toBeInstanceOf(PaymentError);
  });

  it("실패: 알 수 없는 코드는 서버 문구(error, 없으면 message)를, 둘 다 없으면 기본 문구를 쓴다", async () => {
    stubReply(404, async () => ({ error: "주문을 찾을 수 없습니다." }));
    await expect(completeOrderPayment("o1", "t")).rejects.toMatchObject({ status: 404, message: "주문을 찾을 수 없습니다." });
    stubReply(409, async () => ({ message: "서버 메시지", code: "SOMETHING_ELSE" }));
    await expect(completeOrderPayment("o1", "t")).rejects.toMatchObject({ message: "서버 메시지", code: "SOMETHING_ELSE" });
    stubReply(502, async () => {
      throw new SyntaxError("not json");
    });
    await expect(completeOrderPayment("o1", "t")).rejects.toMatchObject({
      status: 502,
      message: "결제 확인에 실패했어요. 잠시 뒤 다시 확인해 주세요.",
    });
  });

  it("실패: 200인데 status가 없으면 성공으로 다루지 않는다", async () => {
    stubReply(200, async () => ({}));
    await expect(completeOrderPayment("o1", "t")).rejects.toBeInstanceOf(PaymentError);
  });

  it("실패: 네트워크 오류는 그대로 전달된다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    await expect(completeOrderPayment("o1", "t")).rejects.toThrow("network");
  });
});

function stubJson(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => {
      if (body === undefined) throw new SyntaxError("not json");
      return body;
    },
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("cancelOrder", () => {
  it("성공: POST /api/orders/{id}/cancel 에 토큰과 사유를 보내고 status를 돌려준다", async () => {
    const fetchMock = stubJson(200, { status: "CANCELLED" });
    await expect(cancelOrder("a/b", "fake-token", "  단순 변심  ")).resolves.toBe("CANCELLED");
    const [url, init] = fetchMock.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe("/api/orders/a%2Fb/cancel");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    expect(JSON.parse(init.body)).toEqual({ reason: "단순 변심" });
    expect(init.signal).toHaveProperty("aborted", false);
  });

  it("성공: 사유가 비어 있으면 reason 없이 빈 본문 객체를 보낸다", async () => {
    const fetchMock = stubJson(200, { status: "CANCELING" });
    await expect(cancelOrder("o1", "t", "   ")).resolves.toBe("CANCELING");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({});
  });

  it("실패: 401과 403은 AuthError다", async () => {
    for (const status of [401, 403]) {
      stubJson(status, {});
      await expect(cancelOrder("o1", "t")).rejects.toBeInstanceOf(AuthError);
    }
  });

  it.each([
    ["ORDER_NOT_CANCELLABLE", 409, "배송이 시작되어 바로 취소할 수 없어요. 교환·환불은 연락처로 신청해 주세요."],
    ["CANCEL_PROVIDER_UNAVAILABLE", 503, "취소 요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요."],
    ["PAYMENT_NOT_CONFIGURED", 503, "취소 요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요."],
  ])("실패: %s 는 OrderActionError(code)와 한국어 문구다", async (code, status, message) => {
    stubJson(status, { error: "raw", code });
    await expect(cancelOrder("o1", "t")).rejects.toMatchObject({ status, code, message });
    await expect(cancelOrder("o1", "t")).rejects.toBeInstanceOf(OrderActionError);
  });

  it("실패: 404와 알 수 없는 오류, JSON이 아닌 응답은 기본 문구다", async () => {
    stubJson(404, { error: "x" });
    await expect(cancelOrder("o1", "t")).rejects.toMatchObject({ status: 404, message: "주문을 찾을 수 없어요." });
    stubJson(500, undefined);
    await expect(cancelOrder("o1", "t")).rejects.toMatchObject({
      status: 500,
      message: "요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
    });
  });

  it("실패: 200인데 status가 없으면 성공으로 다루지 않는다", async () => {
    stubJson(200, {});
    await expect(cancelOrder("o1", "t")).rejects.toBeInstanceOf(OrderActionError);
  });
});

describe("listAdminOrders", () => {
  const page = { orders: [{ id: "o1" }], nextCursor: "c2" };

  it("성공: 상태와 cursor를 쿼리로 보내고 토큰을 싣는다", async () => {
    const fetchMock = stubJson(200, page);
    await expect(listAdminOrders("fake-token", "PAID", "c1")).resolves.toEqual(page);
    const [url, init] = fetchMock.mock.calls[0];
    const u = new URL(String(url));
    expect(u.pathname).toBe("/api/admin/orders");
    expect(u.searchParams.get("status")).toBe("PAID");
    expect(u.searchParams.get("cursor")).toBe("c1");
    expect(u.searchParams.get("size")).toBe("20");
    expect(init.headers).toMatchObject({ Authorization: "Bearer fake-token" });
  });

  it("성공: 상태가 비면 status 없이 요청하고 nextCursor가 없으면 null이다", async () => {
    const fetchMock = stubJson(200, { orders: [] });
    await expect(listAdminOrders("t", "")).resolves.toEqual({ orders: [], nextCursor: null });
    const u = new URL(String(fetchMock.mock.calls[0][0]));
    expect(u.searchParams.has("status")).toBe(false);
    expect(u.searchParams.has("cursor")).toBe(false);
  });

  it("실패: 401/403은 AuthError, 그 밖의 오류와 잘못된 본문은 일반 예외다", async () => {
    stubJson(403, {});
    await expect(listAdminOrders("t")).rejects.toBeInstanceOf(AuthError);
    stubJson(500, {});
    await expect(listAdminOrders("t")).rejects.toThrow("주문 목록을 불러오지 못했습니다.");
    stubJson(200, { orders: "x" });
    await expect(listAdminOrders("t")).rejects.toThrow("주문 목록을 불러오지 못했습니다.");
  });
});

describe("shipOrder / deliverOrder / refundOrder", () => {
  it("성공: ship과 deliver는 본문 없이 POST하고 status를 돌려준다", async () => {
    const ship = stubJson(200, { status: "SHIPPED" });
    await expect(shipOrder("a/b", "fake-token")).resolves.toBe("SHIPPED");
    const [url, init] = ship.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe("/api/admin/orders/a%2Fb/ship");
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
    expect(init.headers).toMatchObject({ Authorization: "Bearer fake-token" });

    const deliver = stubJson(200, { status: "DELIVERED" });
    await expect(deliverOrder("o1", "t")).resolves.toBe("DELIVERED");
    expect(new URL(String(deliver.mock.calls[0][0])).pathname).toBe("/api/admin/orders/o1/deliver");
  });

  it("성공: refund는 reason을 본문으로 보낸다", async () => {
    const fetchMock = stubJson(200, { status: "REFUNDED" });
    await expect(refundOrder("o1", "t", "하자")).resolves.toBe("REFUNDED");
    const [url, init] = fetchMock.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe("/api/admin/orders/o1/refund");
    expect(JSON.parse(init.body)).toEqual({ reason: "하자" });
  });

  it("실패: 401/403은 AuthError, 409 INVALID_ORDER_STATE와 404는 한국어 OrderActionError다", async () => {
    stubJson(401, {});
    await expect(shipOrder("o1", "t")).rejects.toBeInstanceOf(AuthError);
    stubJson(409, { error: "raw", code: "INVALID_ORDER_STATE" });
    await expect(deliverOrder("o1", "t")).rejects.toMatchObject({
      status: 409,
      code: "INVALID_ORDER_STATE",
      message: "주문 상태가 바뀌어 처리할 수 없어요. 목록을 새로고침해 확인해 주세요.",
    });
    stubJson(404, { error: "x" });
    await expect(refundOrder("o1", "t", "r")).rejects.toMatchObject({ status: 404, message: "주문을 찾을 수 없어요." });
  });

  it("실패: 네트워크 오류는 그대로 전달된다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    await expect(shipOrder("o1", "t")).rejects.toThrow("network");
  });
});
