import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { addWish, AuthError, getWishlistIds, listWishlist, removeWish, WishlistError } from "./api";

const GENERIC = "찜을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.";

// `body` undefined = no body at all (like a 204): json() rejects.
function mockFetch(status: number, body?: unknown) {
  const fn = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => {
      if (body === undefined) throw new SyntaxError("Unexpected end of JSON input");
      return body;
    },
  });
  vi.stubGlobal("fetch", fn);
  return fn;
}

const lastCall = (fn: ReturnType<typeof vi.fn>) => {
  const [url, init] = fn.mock.calls[0] as [string, RequestInit & { headers: Record<string, string> }];
  return { url, init };
};

let timeoutSpy: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  vi.unstubAllGlobals();
  timeoutSpy = vi.spyOn(AbortSignal, "timeout");
});
afterEach(() => timeoutSpy.mockRestore());

describe("getWishlistIds", () => {
  it("성공: GET /api/wishlist/ids에 Bearer 토큰과 15초 제한을 붙이고 id 배열을 돌려준다", async () => {
    const fn = mockFetch(200, { ids: ["a", "b"] });
    await expect(getWishlistIds("fake-token")).resolves.toEqual(["a", "b"]);
    const { url, init } = lastCall(fn);
    expect(url).toMatch(/\/api\/wishlist\/ids$/);
    expect(init.method).toBeUndefined(); // GET
    expect(init.headers.Authorization).toBe("Bearer fake-token");
    expect(init.signal).toBeInstanceOf(AbortSignal);
    expect(timeoutSpy).toHaveBeenCalledWith(15_000);
  });

  it("성공: 찜이 하나도 없으면 빈 배열이다", async () => {
    mockFetch(200, { ids: [] });
    await expect(getWishlistIds("t")).resolves.toEqual([]);
  });

  it.each([401, 403])("실패: %i이면 AuthError다", async (status) => {
    mockFetch(status, { error: "x" });
    await expect(getWishlistIds("t")).rejects.toBeInstanceOf(AuthError);
  });

  it("실패: 500이면 AuthError가 아닌 일반 Error다", async () => {
    mockFetch(500, { ids: ["a"] });
    const err = await getWishlistIds("t").catch((e) => e);
    expect(err).toBeInstanceOf(Error);
    expect(err).not.toBeInstanceOf(AuthError);
    expect(err.message).toBe("찜 목록을 불러오지 못했습니다.");
  });

  it.each([[{ ids: "a" }], [{}], [null], [["a"]]])("실패: 응답 모양이 틀리면(%j) Error를 던진다", async (body) => {
    mockFetch(200, body);
    await expect(getWishlistIds("t")).rejects.toThrow("찜 목록을 불러오지 못했습니다.");
  });

  it("실패: 본문이 JSON이 아니어도 Error를 던진다", async () => {
    mockFetch(200); // json() rejects
    await expect(getWishlistIds("t")).rejects.toThrow("찜 목록을 불러오지 못했습니다.");
  });
});

describe("listWishlist", () => {
  it("성공: GET /api/wishlist에 Bearer 토큰과 15초 제한을 붙이고 상품 배열을 돌려준다", async () => {
    const items = [{ id: "a", name: "셔츠", price: 1000, imageUrl: "x" }];
    const fn = mockFetch(200, items);
    await expect(listWishlist("fake-token")).resolves.toEqual(items);
    const { url, init } = lastCall(fn);
    expect(url).toMatch(/\/api\/wishlist$/);
    expect(init.method).toBeUndefined();
    expect(init.headers.Authorization).toBe("Bearer fake-token");
    expect(timeoutSpy).toHaveBeenCalledWith(15_000);
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it.each([401, 403])("실패: %i이면 AuthError다", async (status) => {
    mockFetch(status, {});
    await expect(listWishlist("t")).rejects.toBeInstanceOf(AuthError);
  });

  it("실패: 500이면 일반 Error다", async () => {
    mockFetch(500, []);
    const err = await listWishlist("t").catch((e) => e);
    expect(err).not.toBeInstanceOf(AuthError);
    expect(err.message).toBe("찜한 상품을 불러오지 못했습니다.");
  });

  it.each([[{ items: [] }], [{ ids: [] }], [null], ["x"]])("실패: 배열이 아닌 응답(%j)이면 Error를 던진다", async (body) => {
    mockFetch(200, body);
    await expect(listWishlist("t")).rejects.toThrow("찜한 상품을 불러오지 못했습니다.");
  });
});

describe.each([
  ["addWish", addWish, "PUT"],
  ["removeWish", removeWish, "DELETE"],
] as const)("%s", (_name, fn, method) => {
  it(`성공: ${method} /api/wishlist/{id}에 Bearer 토큰과 15초 제한을 붙이고, 본문 없는 204도 성공이다`, async () => {
    const f = mockFetch(204);
    await expect(fn("p1", "fake-token")).resolves.toBeUndefined();
    const { url, init } = lastCall(f);
    expect(url).toMatch(/\/api\/wishlist\/p1$/);
    expect(init.method).toBe(method);
    expect(init.headers.Authorization).toBe("Bearer fake-token");
    expect(init.signal).toBeInstanceOf(AbortSignal);
    expect(timeoutSpy).toHaveBeenCalledWith(15_000);
  });

  it("성공: 상품 id는 경로에 안전하게 인코딩된다", async () => {
    const f = mockFetch(204);
    await fn("a/b c", "t");
    expect(lastCall(f).url).toMatch(/\/api\/wishlist\/a%2Fb%20c$/);
  });

  it.each([401, 403])("실패: %i이면 WishlistError가 아니라 AuthError다", async (status) => {
    mockFetch(status, { error: "찜은 최대 200개까지 담을 수 있어요." });
    const err = await fn("p1", "t").catch((e) => e);
    expect(err).toBeInstanceOf(AuthError);
    expect(err).not.toBeInstanceOf(WishlistError);
  });

  it("실패: 400이고 error가 문자열이면 서버 문구를 그대로 WishlistError에 담는다", async () => {
    mockFetch(400, { error: "찜은 최대 200개까지 담을 수 있어요." });
    const err = await fn("p1", "t").catch((e) => e);
    expect(err).toBeInstanceOf(WishlistError);
    expect(err.status).toBe(400);
    expect(err.message).toBe("찜은 최대 200개까지 담을 수 있어요.");
  });

  it.each([[{ error: 5 }], [{ error: "" }], [{}], [null], [undefined]])(
    "실패: 400이어도 error가 문자열이 아니거나 비면(%j) 일반 문구다",
    async (body) => {
      mockFetch(400, body);
      const err = await fn("p1", "t").catch((e) => e);
      expect(err).toBeInstanceOf(WishlistError);
      expect(err.message).toBe(GENERIC);
    }
  );

  it("실패: 404이면 '상품을 찾을 수 없어요.'이고 서버 문구는 쓰지 않는다", async () => {
    mockFetch(404, { error: "서버 문구" });
    const err = await fn("p1", "t").catch((e) => e);
    expect(err).toBeInstanceOf(WishlistError);
    expect(err.status).toBe(404);
    expect(err.message).toBe("상품을 찾을 수 없어요.");
  });

  it.each([409, 429, 500, 502])("실패: %i이면 서버 문구와 상관없이 일반 문구다", async (status) => {
    mockFetch(status, { error: "서버 문구" });
    const err = await fn("p1", "t").catch((e) => e);
    expect(err).toBeInstanceOf(WishlistError);
    expect(err.status).toBe(status);
    expect(err.message).toBe(GENERIC);
  });

  it("실패: 네트워크 오류는 그대로 던진다 (WishlistError가 아니다)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    const err = await fn("p1", "t").catch((e) => e);
    expect(err).toBeInstanceOf(TypeError);
  });
});
