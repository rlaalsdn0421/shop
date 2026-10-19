import { describe, it, expect, vi, beforeEach } from "vitest";
import { updateAdminProduct, ProductEditError, STOCK_CHANGED, AuthError, type ProductEditBody } from "./api";

const GENERIC = "저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.";

const plainBody: ProductEditBody = {
  name: "린넨 셔츠",
  description: "시원한 셔츠",
  category: null,
  hashtags: ["여름"],
  imageUrls: ["https://example.com/a.jpg"],
  price: 8000,
  originalPrice: null,
  stock: 8,
  expectedStock: 10,
};

function stubReply(status: number, json: () => Promise<unknown>) {
  const fetchMock = vi.fn().mockResolvedValue({ ok: status >= 200 && status < 300, status, json });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}
const stubJson = (status: number, body: unknown) => stubReply(status, async () => body);
const save = () => updateAdminProduct("p1", plainBody, "fake-token");

beforeEach(() => {
  vi.unstubAllGlobals();
});

describe("updateAdminProduct", () => {
  it("성공: PUT /api/admin/products/{id}에 토큰·JSON 본문·timeout signal을 싣고 {id}를 반환한다", async () => {
    const fetchMock = stubJson(200, { id: "p1" });
    await expect(save()).resolves.toEqual({ id: "p1" });
    const [url, init] = fetchMock.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe("/api/admin/products/p1");
    expect(init.method).toBe("PUT");
    expect(init.headers).toMatchObject({ Authorization: "Bearer fake-token", "Content-Type": "application/json" });
    expect(JSON.parse(init.body)).toEqual(plainBody);
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it("성공: id는 URL 경로에서 인코딩된다", async () => {
    const fetchMock = stubJson(200, { id: "a/b c" });
    await updateAdminProduct("a/b c", plainBody, "t");
    expect(String(fetchMock.mock.calls[0][0])).toContain("/api/admin/products/a%2Fb%20c");
  });

  it("성공: 옵션 상품 본문도 그대로 JSON으로 보낸다", async () => {
    const fetchMock = stubJson(200, { id: "p1" });
    const body: ProductEditBody = {
      name: "n",
      description: "d",
      category: "상의",
      hashtags: [],
      imageUrls: ["https://example.com/a.jpg"],
      options: [{ id: "o1", price: 1000, originalPrice: null, stock: 3, expectedStock: 5 }],
    };
    await updateAdminProduct("p1", body, "t");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual(body);
  });

  it("실패: 401과 403은 AuthError다 (본문을 읽지 않는다)", async () => {
    for (const status of [401, 403]) {
      stubReply(status, async () => {
        throw new Error("no body");
      });
      await expect(save()).rejects.toBeInstanceOf(AuthError);
    }
  });

  it("실패: 409 STOCK_CHANGED는 서버 문구가 아닌 한국어 안내와 code를 담은 ProductEditError다", async () => {
    stubJson(409, { error: "raw server text", code: STOCK_CHANGED });
    const err = await save().catch((e) => e);
    expect(err).toBeInstanceOf(ProductEditError);
    expect(err).not.toBeInstanceOf(AuthError);
    expect(err.status).toBe(409);
    expect(err.code).toBe("STOCK_CHANGED");
    expect(err.message).toBe("그 사이 재고가 바뀌었어요. 새로고침한 뒤 다시 입력해 주세요.");
  });

  it("실패: code가 없는 409는 일반 문구이고 code는 undefined다", async () => {
    stubJson(409, { error: "충돌" });
    await expect(save()).rejects.toMatchObject({ status: 409, message: GENERIC, code: undefined });
  });

  it("실패: 404는 상품을 찾을 수 없다는 문구다", async () => {
    stubJson(404, { error: "x" });
    await expect(save()).rejects.toMatchObject({ status: 404, message: "상품을 찾을 수 없어요." });
  });

  it("실패: 400은 서버의 error 문구를, 없으면 message 문구를 쓴다", async () => {
    stubJson(400, { error: "상품명은 200자 이하여야 합니다." });
    await expect(save()).rejects.toMatchObject({ status: 400, message: "상품명은 200자 이하여야 합니다." });
    stubJson(400, { message: "옵션이 빠졌습니다." });
    await expect(save()).rejects.toMatchObject({ status: 400, message: "옵션이 빠졌습니다." });
  });

  it("실패: 400에 error와 message가 둘 다 있으면 error가 우선이다", async () => {
    stubJson(400, { error: "에러 문구", message: "메시지 문구" });
    await expect(save()).rejects.toMatchObject({ status: 400, message: "에러 문구" });
  });

  it("실패: 400인데 문구가 없거나 문자열이 아니면 일반 문구다", async () => {
    for (const body of [{}, { error: 5 }, null]) {
      stubJson(400, body);
      await expect(save()).rejects.toMatchObject({ status: 400, message: GENERIC });
    }
    stubReply(400, async () => {
      throw new Error("not json");
    });
    await expect(save()).rejects.toMatchObject({ status: 400, message: GENERIC });
  });

  it("실패: 400이 아닌 다른 상태는 서버 문구를 숨기고 일반 문구를 쓴다", async () => {
    for (const status of [422, 500, 503]) {
      stubJson(status, { error: "internal detail" });
      await expect(save()).rejects.toMatchObject({ status, message: GENERIC });
    }
  });

  it("실패: 200이어도 문자열 id가 없으면 오류다", async () => {
    for (const body of [{}, { id: 7 }, null]) {
      stubJson(200, body);
      await expect(save()).rejects.toMatchObject({ status: 200, message: GENERIC });
    }
    stubReply(200, async () => {
      throw new Error("empty");
    });
    await expect(save()).rejects.toBeInstanceOf(ProductEditError);
  });

  it("실패: 네트워크 오류와 timeout은 가공 없이 그대로 전달된다 (화면이 네트워크 문구로 처리)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    const net = await save().catch((e) => e);
    expect(net).toBeInstanceOf(TypeError);
    expect(net).not.toBeInstanceOf(ProductEditError);
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new DOMException("timeout", "TimeoutError")));
    await expect(save()).rejects.toMatchObject({ name: "TimeoutError" });
  });
});
