import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { useEffect } from "react";
import { act, render, screen } from "@testing-library/react";
import { WishlistProvider, useWishlist } from "./WishlistContext";
import { AuthError, WishlistError } from "@/lib/api";

const auth = vi.hoisted(() => ({ session: null as null | { token: string }, ready: true, logout: vi.fn() }));
const api = vi.hoisted(() => ({ getWishlistIds: vi.fn(), addWish: vi.fn(), removeWish: vi.fn() }));
vi.mock("@/auth/AuthContext", () => ({ useAuth: () => auth }));
// Keep the real AuthError / WishlistError classes (the provider checks instanceof); fake only the network calls.
vi.mock("@/lib/api", async (orig) => ({ ...(await orig<typeof import("@/lib/api")>()), ...api }));

const GENERIC = "찜을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.";

function defer<T = void>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

let ctx!: ReturnType<typeof useWishlist>;
function Probe() {
  const value = useWishlist();
  // After every commit, so `ctx` is what the last render produced (act() flushes this effect).
  useEffect(() => {
    ctx = value;
  });
  return null;
}
const tree = () => (
  <WishlistProvider>
    <Probe />
  </WishlistProvider>
);

type Rendered = ReturnType<typeof render>;
async function mount() {
  let r!: Rendered;
  await act(async () => {
    r = render(tree());
  });
  return r;
}
// The auth mock is a plain object, so after changing it a re-render is what makes the provider read it again.
async function refresh(r: Rendered) {
  await act(async () => {
    r.rerender(tree());
  });
}
const ids = () => [...ctx.ids].sort();

beforeEach(() => {
  auth.session = { token: "token-a" };
  auth.ready = true;
  auth.logout.mockReset();
  api.getWishlistIds.mockReset().mockResolvedValue(["a"]);
  api.addWish.mockReset().mockResolvedValue(undefined);
  api.removeWish.mockReset().mockResolvedValue(undefined);
});

describe("WishlistProvider 불러오기", () => {
  it("성공: 로그인 세션이 준비되면 id를 한 번 불러와 ready가 되고 isWished에 반영된다", async () => {
    const d = defer<string[]>();
    api.getWishlistIds.mockReturnValue(d.promise);
    await mount();
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
    expect(api.getWishlistIds).toHaveBeenCalledWith("token-a");
    // Still loading: not ready, nothing wished.
    expect(ctx.ready).toBe(false);
    expect(ctx.ids.size).toBe(0);
    await act(async () => d.resolve(["a", "b"]));
    expect(ctx.ready).toBe(true);
    expect(ids()).toEqual(["a", "b"]);
    expect(ctx.isWished("a")).toBe(true);
    expect(ctx.isWished("zzz")).toBe(false);
    expect(ctx.loadFailed).toBe(false);
  });

  it("성공: 다시 렌더링해도 다시 불러오지 않는다 (한 번만)", async () => {
    const r = await mount();
    await refresh(r);
    await refresh(r);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
  });

  it("성공: 로그아웃 상태(auth 준비됨)면 요청 없이 바로 ready이고 비어 있다", async () => {
    auth.session = null;
    await mount();
    expect(api.getWishlistIds).not.toHaveBeenCalled();
    expect(ctx.ready).toBe(true);
    expect(ctx.ids.size).toBe(0);
    expect(ctx.loadFailed).toBe(false);
  });

  it("실패: auth가 아직 준비되지 않았으면(토큰 있어도 없어도) 요청하지 않고 ready가 아니다", async () => {
    auth.ready = false;
    const r = await mount();
    expect(api.getWishlistIds).not.toHaveBeenCalled();
    expect(ctx.ready).toBe(false);
    auth.session = null;
    await refresh(r);
    expect(api.getWishlistIds).not.toHaveBeenCalled();
    expect(ctx.ready).toBe(false);
  });

  it("성공: auth가 준비되는 순간 불러온다", async () => {
    auth.ready = false;
    const r = await mount();
    auth.ready = true;
    await refresh(r);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
    expect(ctx.ready).toBe(true);
    expect(ids()).toEqual(["a"]);
  });

  it("성공: 로그아웃하면 id를 비우고 ready는 유지된다", async () => {
    const r = await mount();
    expect(ids()).toEqual(["a"]);
    auth.session = null;
    await refresh(r);
    expect(ctx.ids.size).toBe(0);
    expect(ctx.isWished("a")).toBe(false);
    expect(ctx.ready).toBe(true);
  });

  it("성공: id는 토큰에 묶여 있어 새 토큰이면 다시 불러오고, 그동안 이전 사람의 id는 보이지 않는다", async () => {
    const r = await mount();
    expect(ids()).toEqual(["a"]);
    const d = defer<string[]>();
    api.getWishlistIds.mockReturnValue(d.promise);
    auth.session = { token: "token-b" };
    await refresh(r);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(2);
    expect(api.getWishlistIds).toHaveBeenLastCalledWith("token-b");
    expect(ctx.ready).toBe(false);
    expect(ctx.ids.size).toBe(0);
    await act(async () => d.resolve(["x"]));
    expect(ctx.ready).toBe(true);
    expect(ids()).toEqual(["x"]);
  });

  it("실패: 로그아웃 뒤에 늦게 도착한 결과는 무시한다", async () => {
    const d = defer<string[]>();
    api.getWishlistIds.mockReturnValue(d.promise);
    const r = await mount();
    auth.session = null;
    await refresh(r);
    await act(async () => d.resolve(["a", "b"]));
    expect(ctx.ids.size).toBe(0);
    expect(ctx.ready).toBe(true);
    // And logging back in with the same token does not resurrect it either: it loads again.
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
  });

  it("실패: 토큰이 바뀐 뒤 이전 토큰의 늦은 결과는 무시하고 새 토큰의 결과만 쓴다", async () => {
    const a = defer<string[]>();
    const b = defer<string[]>();
    api.getWishlistIds.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
    const r = await mount();
    auth.session = { token: "token-b" };
    await refresh(r);
    await act(async () => a.resolve(["from-a"]));
    expect(ctx.ids.size).toBe(0);
    expect(ctx.ready).toBe(false);
    await act(async () => b.resolve(["from-b"]));
    expect(ids()).toEqual(["from-b"]);
    expect(ctx.ready).toBe(true);
  });

  it("실패: 새 토큰의 결과가 먼저 온 뒤 이전 토큰의 결과가 더 늦게 와도 덮어쓰지 않는다", async () => {
    const a = defer<string[]>();
    const b = defer<string[]>();
    api.getWishlistIds.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
    const r = await mount();
    auth.session = { token: "token-b" };
    await refresh(r);
    await act(async () => b.resolve(["from-b"]));
    await act(async () => a.resolve(["from-a"]));
    expect(ids()).toEqual(["from-b"]);
    expect(ctx.ready).toBe(true);
  });
});

describe("WishlistProvider 불러오기 재시도", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());
  const advance = (ms: number) =>
    act(async () => {
      await vi.advanceTimersByTimeAsync(ms);
    });

  it("성공: 처음 실패하면 정확히 3000ms 뒤 한 번 다시 시도하고, 성공하면 ready가 되며 loadFailed는 계속 false다", async () => {
    api.getWishlistIds.mockRejectedValueOnce(new Error("down")).mockResolvedValueOnce(["a", "b"]);
    await mount();
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
    expect(ctx.loadFailed).toBe(false);
    expect(ctx.ready).toBe(false);
    await advance(2999);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(2);
    expect(api.getWishlistIds).toHaveBeenLastCalledWith("token-a");
    expect(ctx.loadFailed).toBe(false);
    expect(ctx.ready).toBe(true);
    expect(ids()).toEqual(["a", "b"]);
  });

  it("실패: 두 번째도 실패하면 loadFailed가 되고 ready는 false이며 세 번째 시도는 없다", async () => {
    api.getWishlistIds.mockRejectedValue(new Error("down"));
    await mount();
    await advance(3000);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(2);
    expect(ctx.loadFailed).toBe(true);
    expect(ctx.ready).toBe(false);
    expect(ctx.ids.size).toBe(0);
    await advance(120_000);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(2);
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it("성공: loadFailed 상태에서는 toggle해도 아무 요청이 없다", async () => {
    api.getWishlistIds.mockRejectedValue(new Error("down"));
    await mount();
    await advance(3000);
    await act(async () => {
      await ctx.toggle("a");
    });
    expect(api.addWish).not.toHaveBeenCalled();
    expect(api.removeWish).not.toHaveBeenCalled();
  });

  it("실패: 기다리는 동안 로그아웃하면 두 번째 요청을 하지 않고 loadFailed도 아니다", async () => {
    api.getWishlistIds.mockRejectedValue(new Error("down"));
    const r = await mount();
    auth.session = null;
    await refresh(r);
    await advance(10_000);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
    expect(ctx.loadFailed).toBe(false);
    expect(ctx.ready).toBe(true);
  });

  it("성공: loadFailed는 새 토큰이면 초기화되고, 새 토큰도 자기 재시도를 따로 가진다", async () => {
    api.getWishlistIds.mockRejectedValue(new Error("down"));
    const r = await mount();
    await advance(3000);
    expect(ctx.loadFailed).toBe(true);
    const d = defer<string[]>();
    api.getWishlistIds.mockReset().mockReturnValue(d.promise);
    auth.session = { token: "token-b" };
    await refresh(r);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
    expect(api.getWishlistIds).toHaveBeenCalledWith("token-b");
    expect(ctx.loadFailed).toBe(false);
    expect(ctx.ready).toBe(false);
    await act(async () => d.resolve(["x"]));
    expect(ctx.ready).toBe(true);
    expect(ids()).toEqual(["x"]);
  });

  it("실패: 로그아웃하면 loadFailed도 사라진다", async () => {
    api.getWishlistIds.mockRejectedValue(new Error("down"));
    const r = await mount();
    await advance(3000);
    expect(ctx.loadFailed).toBe(true);
    auth.session = null;
    await refresh(r);
    expect(ctx.loadFailed).toBe(false);
    expect(ctx.ready).toBe(true);
  });

  it("실패: 첫 시도가 AuthError면 로그아웃하고 재시도도 loadFailed도 없다", async () => {
    api.getWishlistIds.mockRejectedValue(new AuthError());
    await mount();
    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(auth.logout).toHaveBeenCalledWith({ keepOrderAttempt: true });
    await advance(10_000);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
    expect(ctx.loadFailed).toBe(false);
  });

  it("실패: 재시도가 AuthError여도 로그아웃하고 loadFailed는 아니다", async () => {
    api.getWishlistIds.mockRejectedValueOnce(new Error("down")).mockRejectedValueOnce(new AuthError());
    await mount();
    expect(auth.logout).not.toHaveBeenCalled();
    await advance(3000);
    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(auth.logout).toHaveBeenCalledWith({ keepOrderAttempt: true });
    expect(api.getWishlistIds).toHaveBeenCalledTimes(2);
    expect(ctx.loadFailed).toBe(false);
    await advance(10_000);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(2);
  });
});

describe("WishlistProvider toggle", () => {
  it("성공: 찜 추가는 낙관적이다 (요청이 끝나기 전에 id가 들어가고 PUT은 토큰과 함께 나간다)", async () => {
    const d = defer();
    api.addWish.mockReturnValue(d.promise);
    await mount();
    await act(async () => {
      void ctx.toggle("b");
    });
    expect(ctx.isWished("b")).toBe(true);
    expect(ids()).toEqual(["a", "b"]);
    expect(api.addWish).toHaveBeenCalledTimes(1);
    expect(api.addWish).toHaveBeenCalledWith("b", "token-a");
    expect(api.removeWish).not.toHaveBeenCalled();
    await act(async () => d.resolve());
    expect(ids()).toEqual(["a", "b"]);
    expect(ctx.lastError).toBeNull();
  });

  it("성공: 찜 해제도 낙관적이다 (DELETE)", async () => {
    const d = defer();
    api.removeWish.mockReturnValue(d.promise);
    await mount();
    await act(async () => {
      void ctx.toggle("a");
    });
    expect(ctx.isWished("a")).toBe(false);
    expect(api.removeWish).toHaveBeenCalledWith("a", "token-a");
    expect(api.addWish).not.toHaveBeenCalled();
    await act(async () => d.resolve());
    expect(ctx.isWished("a")).toBe(false);
  });

  it("실패: 추가가 실패하면 되돌리고 서버가 준 한도 문구를 lastError에 담는다", async () => {
    api.addWish.mockRejectedValue(new WishlistError(400, "찜은 최대 200개까지 담을 수 있어요."));
    await mount();
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(ctx.isWished("b")).toBe(false);
    expect(ids()).toEqual(["a"]);
    expect(ctx.lastError).toEqual({ productId: "b", message: "찜은 최대 200개까지 담을 수 있어요." });
    // The server answered: no need to ask it again.
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1);
  });

  it("실패: 해제가 실패하면 다시 찜한 상태로 되돌린다", async () => {
    api.removeWish.mockRejectedValue(new WishlistError(500, GENERIC));
    await mount();
    await act(async () => {
      await ctx.toggle("a");
    });
    expect(ctx.isWished("a")).toBe(true);
    expect(ctx.lastError).toEqual({ productId: "a", message: GENERIC });
  });

  it("실패: 404는 그 문구를 그대로 보여준다", async () => {
    api.addWish.mockRejectedValue(new WishlistError(404, "상품을 찾을 수 없어요."));
    await mount();
    await act(async () => {
      await ctx.toggle("gone");
    });
    expect(ctx.lastError).toEqual({ productId: "gone", message: "상품을 찾을 수 없어요." });
    expect(ctx.isWished("gone")).toBe(false);
  });

  it("성공: lastError는 다음 toggle을 시작하는 순간 지워진다", async () => {
    api.addWish.mockRejectedValueOnce(new WishlistError(400, "한도"));
    await mount();
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(ctx.lastError).not.toBeNull();
    const d = defer();
    api.addWish.mockReturnValue(d.promise);
    await act(async () => {
      void ctx.toggle("c");
    });
    expect(ctx.lastError).toBeNull();
    await act(async () => d.resolve());
  });

  it("실패: AuthError면 로그아웃하고 lastError는 없다", async () => {
    api.addWish.mockRejectedValue(new AuthError());
    await mount();
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(auth.logout).toHaveBeenCalledWith({ keepOrderAttempt: true });
    expect(ctx.lastError).toBeNull();
    expect(ctx.pending.size).toBe(0);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(1); // a refused session is not an unknown outcome
  });

  it("실패: 같은 상품이 진행 중일 때 다시 누르면 무시한다 (요청 1번, 상태 그대로)", async () => {
    const d = defer();
    api.addWish.mockReturnValue(d.promise);
    await mount();
    await act(async () => {
      void ctx.toggle("b");
    });
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(api.addWish).toHaveBeenCalledTimes(1);
    expect(api.removeWish).not.toHaveBeenCalled();
    expect(ctx.isWished("b")).toBe(true);
    await act(async () => d.resolve());
    expect(ids()).toEqual(["a", "b"]);
  });

  it("실패: 같은 틱에 두 번 호출해도 요청은 한 번이다", async () => {
    const d = defer();
    api.addWish.mockReturnValue(d.promise);
    await mount();
    await act(async () => {
      void ctx.toggle("b");
      void ctx.toggle("b");
    });
    expect(api.addWish).toHaveBeenCalledTimes(1);
    expect(api.removeWish).not.toHaveBeenCalled();
    expect(ctx.isWished("b")).toBe(true);
    await act(async () => d.resolve());
  });

  it("성공: 다른 상품은 진행 중인 상품과 상관없이 바로 toggle된다", async () => {
    const d = defer();
    api.addWish.mockReturnValueOnce(d.promise);
    await mount();
    await act(async () => {
      void ctx.toggle("b");
    });
    await act(async () => {
      await ctx.toggle("c");
    });
    expect(api.addWish).toHaveBeenCalledTimes(2);
    expect(ids()).toEqual(["a", "b", "c"]);
    expect([...ctx.pending]).toEqual(["b"]);
    await act(async () => d.resolve());
  });

  it("성공: pending에는 요청이 진행되는 동안에만 그 id가 들어 있다 (성공과 실패 모두)", async () => {
    await mount();
    expect(ctx.pending.size).toBe(0);
    const ok = defer();
    api.addWish.mockReturnValueOnce(ok.promise);
    await act(async () => {
      void ctx.toggle("b");
    });
    expect(ctx.pending.has("b")).toBe(true);
    await act(async () => ok.resolve());
    expect(ctx.pending.has("b")).toBe(false);

    const bad = defer();
    api.addWish.mockReturnValueOnce(bad.promise);
    await act(async () => {
      void ctx.toggle("c");
    });
    expect(ctx.pending.has("c")).toBe(true);
    await act(async () => bad.reject(new WishlistError(500, GENERIC)));
    expect(ctx.pending.has("c")).toBe(false);
    // Free again: the same id can be toggled once more.
    await act(async () => {
      await ctx.toggle("c");
    });
    expect(api.addWish).toHaveBeenCalledTimes(3);
  });

  it("실패: 로그아웃 상태에서는 toggle해도 아무 요청도 없다", async () => {
    auth.session = null;
    await mount();
    await act(async () => {
      await ctx.toggle("a");
    });
    expect(api.addWish).not.toHaveBeenCalled();
    expect(api.removeWish).not.toHaveBeenCalled();
    expect(ctx.ids.size).toBe(0);
    expect(ctx.pending.size).toBe(0);
  });

  it("실패: id를 아직 불러오는 중이면 toggle해도 아무 요청도 없다", async () => {
    api.getWishlistIds.mockReturnValue(defer<string[]>().promise);
    await mount();
    await act(async () => {
      await ctx.toggle("a");
    });
    expect(api.addWish).not.toHaveBeenCalled();
    expect(api.removeWish).not.toHaveBeenCalled();
    expect(ctx.ids.size).toBe(0);
  });

  it("실패: 요청 중 다른 계정으로 바뀐 뒤 실패해도 새 계정의 id는 건드리지 않는다", async () => {
    const d = defer();
    api.addWish.mockReturnValue(d.promise);
    const r = await mount();
    await act(async () => {
      void ctx.toggle("b");
    });
    api.getWishlistIds.mockResolvedValue(["b", "z"]);
    auth.session = { token: "token-b" };
    await refresh(r);
    expect(ids()).toEqual(["b", "z"]);
    await act(async () => d.reject(new WishlistError(500, GENERIC)));
    // The rollback belongs to token-a; token-b's own "b" stays wished.
    expect(ids()).toEqual(["b", "z"]);
  });
});

const TYPE_ERROR = () => new TypeError("Failed to fetch");
const noticeText = () => screen.getByRole("status").textContent;

describe("WishlistProvider 결과를 모르는 실패 (네트워크 오류·시간 초과)", () => {
  it("실패: 서버 상태가 그대로면 같은 토큰으로 id를 다시 받아 되돌리고 일반 문구를 보인다", async () => {
    api.addWish.mockRejectedValue(TYPE_ERROR());
    await mount();
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(api.getWishlistIds).toHaveBeenCalledTimes(2);
    expect(api.getWishlistIds).toHaveBeenLastCalledWith("token-a");
    expect(ids()).toEqual(["a"]);
    expect(ctx.lastError).toEqual({ productId: "b", message: GENERIC });
    expect(ctx.pending.size).toBe(0);
  });

  it("성공: 요청은 서버에 반영됐으면(다시 받은 id에 있으면) 되돌리지 않고 메시지도 없다", async () => {
    api.addWish.mockRejectedValue(TYPE_ERROR());
    await mount();
    api.getWishlistIds.mockResolvedValue(["a", "b"]);
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(ids()).toEqual(["a", "b"]);
    expect(ctx.lastError).toBeNull();
    expect(noticeText()).toBe("");
  });

  it("실패: 해제 요청이 시간 초과이고 서버에 아직 있으면 찜한 상태로 돌아가고 일반 문구를 보인다", async () => {
    api.removeWish.mockRejectedValue(TYPE_ERROR());
    await mount();
    await act(async () => {
      await ctx.toggle("a");
    });
    expect(ids()).toEqual(["a"]);
    expect(ctx.lastError).toEqual({ productId: "a", message: GENERIC });
  });

  it("성공: 해제 요청이 시간 초과여도 서버에서 이미 사라졌으면 해제된 채로 두고 메시지도 없다", async () => {
    api.removeWish.mockRejectedValue(TYPE_ERROR());
    await mount();
    api.getWishlistIds.mockResolvedValue([]);
    await act(async () => {
      await ctx.toggle("a");
    });
    expect(ids()).toEqual([]);
    expect(ctx.lastError).toBeNull();
  });

  it("실패: 다시 받기도 실패하면(인증 오류 아님) 그 id를 되돌리고 일반 문구를 보인다", async () => {
    api.addWish.mockRejectedValue(TYPE_ERROR());
    await mount();
    api.getWishlistIds.mockRejectedValue(new Error("down"));
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(ids()).toEqual(["a"]);
    expect(ctx.lastError).toEqual({ productId: "b", message: GENERIC });
    expect(ctx.pending.size).toBe(0);
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it("실패: 다시 받다가 AuthError면 keepOrderAttempt로 로그아웃하고 메시지는 없다", async () => {
    api.addWish.mockRejectedValue(TYPE_ERROR());
    await mount();
    api.getWishlistIds.mockRejectedValue(new AuthError());
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(auth.logout).toHaveBeenCalledWith({ keepOrderAttempt: true });
    expect(ctx.lastError).toBeNull();
    expect(ctx.pending.size).toBe(0);
  });

  it("성공: pending은 요청과 다시 받기가 끝날 때까지 유지되고, 그동안 다시 눌러도 무시된다", async () => {
    api.addWish.mockRejectedValue(TYPE_ERROR());
    await mount();
    const refetch = defer<string[]>();
    api.getWishlistIds.mockReturnValueOnce(refetch.promise);
    await act(async () => {
      void ctx.toggle("b");
    });
    expect(ctx.pending.has("b")).toBe(true);
    expect(api.getWishlistIds).toHaveBeenCalledTimes(2);
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(api.addWish).toHaveBeenCalledTimes(1);
    expect(api.removeWish).not.toHaveBeenCalled();
    await act(async () => refetch.resolve(["a"]));
    expect(ctx.pending.has("b")).toBe(false);
    expect(ids()).toEqual(["a"]);
  });

  it("성공: 다시 받는 동안 진행 중인 다른 토글은 낙관적 상태를 그대로 유지한다", async () => {
    const other = defer();
    api.addWish.mockImplementation((id: string) => (id === "c" ? other.promise : Promise.reject(TYPE_ERROR())));
    await mount();
    await act(async () => {
      void ctx.toggle("c");
    });
    // The server does not know about "c" yet, and says "b" never went through.
    api.getWishlistIds.mockResolvedValue(["a"]);
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(ids()).toEqual(["a", "c"]);
    expect(ctx.lastError).toEqual({ productId: "b", message: GENERIC });
    expect([...ctx.pending]).toEqual(["c"]);
    await act(async () => other.resolve());
    expect(ids()).toEqual(["a", "c"]);
  });

  it("실패: 다시 받는 동안 다른 계정으로 바뀌었으면 그 결과도 메시지도 새 계정에 쓰지 않는다", async () => {
    api.addWish.mockRejectedValue(TYPE_ERROR());
    const r = await mount();
    const refetch = defer<string[]>();
    api.getWishlistIds.mockReturnValueOnce(refetch.promise);
    await act(async () => {
      void ctx.toggle("b");
    });
    api.getWishlistIds.mockResolvedValue(["z"]);
    auth.session = { token: "token-b" };
    await refresh(r);
    expect(ids()).toEqual(["z"]);
    await act(async () => refetch.resolve(["a"])); // server state of token-a, unchanged -> would be an error for token-a
    expect(ids()).toEqual(["z"]);
    expect(ctx.lastError).toBeNull();
    expect(noticeText()).toBe("");
    expect(ctx.pending.size).toBe(0);
  });
});

describe("WishlistProvider 안내(한 곳의 알림 영역)", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());
  const advance = (ms: number) =>
    act(async () => {
      await vi.advanceTimersByTimeAsync(ms);
    });
  const fail = (message: string, id = "b") => {
    api.addWish.mockRejectedValueOnce(new WishlistError(400, message));
    return act(async () => {
      await ctx.toggle(id);
    });
  };

  it("성공: 알림 영역은 항상 있고(role=status, aria-live=polite) 처음에는 비어 있어 알림 칸도 없다", async () => {
    await mount();
    const region = screen.getByRole("status");
    expect(region).toHaveAttribute("aria-live", "polite");
    expect(region).toHaveClass("pointer-events-none", "fixed");
    expect(region.textContent).toBe("");
    expect(region.children).toHaveLength(0);
    expect(screen.getAllByRole("status")).toHaveLength(1);
  });

  it("성공: 변경이 실패하면 그 문구가 알림이 되고 정확히 5000ms 뒤 사라진다", async () => {
    await mount();
    await fail("한도 문구");
    expect(noticeText()).toBe("한도 문구");
    expect(screen.getByRole("status").children).toHaveLength(1);
    await advance(4999);
    expect(noticeText()).toBe("한도 문구");
    expect(ctx.lastError).not.toBeNull();
    await advance(1);
    expect(noticeText()).toBe("");
    expect(ctx.lastError).toBeNull();
    expect(screen.getByRole("status").children).toHaveLength(0);
  });

  it("성공: 새 오류가 나면 타이머가 다시 시작돼 이전 오류의 타이머가 새 문구를 지우지 않는다", async () => {
    await mount();
    await fail("첫 오류");
    await advance(3000);
    await fail("둘째 오류", "c");
    expect(noticeText()).toBe("둘째 오류");
    await advance(4999); // 3000 + 4999: past the first timer, before the second
    expect(noticeText()).toBe("둘째 오류");
    await advance(1);
    expect(noticeText()).toBe("");
  });

  it("성공: 다음 toggle을 시작하면 알림이 바로 지워진다", async () => {
    await mount();
    await fail("한도 문구");
    api.addWish.mockReturnValueOnce(new Promise(() => {}));
    await act(async () => {
      void ctx.toggle("c");
    });
    expect(noticeText()).toBe("");
  });

  it("성공: 사라진 뒤에는 타이머가 남지 않고, 언마운트하면 대기 중인 타이머도 정리된다", async () => {
    const r = await mount();
    await fail("한도 문구");
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    await advance(5000);
    expect(vi.getTimerCount()).toBe(0);
    await fail("또 오류");
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    r.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("실패: 오류는 로그아웃하거나 다른 토큰으로 바뀌면 노출되지 않는다 (토큰에 묶임)", async () => {
    const r = await mount();
    await fail("한도 문구");
    expect(noticeText()).toBe("한도 문구");
    auth.session = { token: "token-b" };
    await refresh(r);
    expect(ctx.lastError).toBeNull();
    expect(noticeText()).toBe("");
    auth.session = null;
    await refresh(r);
    expect(ctx.lastError).toBeNull();
    expect(noticeText()).toBe("");
  });

  it("실패: 요청 중 토큰이 바뀐 뒤 서버가 거절해도 lastError를 만들지 않는다", async () => {
    const d = defer();
    api.addWish.mockReturnValue(d.promise);
    const r = await mount();
    await act(async () => {
      void ctx.toggle("b");
    });
    auth.session = { token: "token-b" };
    await refresh(r);
    await act(async () => d.reject(new WishlistError(400, "한도 문구")));
    expect(ctx.lastError).toBeNull();
    expect(noticeText()).toBe("");
    // Not merely hidden: logging back in as the first login does not bring the old message back.
    auth.session = { token: "token-a" };
    await refresh(r);
    expect(ctx.lastError).toBeNull();
    expect(noticeText()).toBe("");
  });

  it("성공: ids를 불러오지 못한(loadFailed) 로그인 상태에서는 불러오기 실패 안내가 계속 떠 있고 저절로 사라지지 않는다", async () => {
    api.getWishlistIds.mockRejectedValue(new Error("down"));
    await mount();
    await advance(3000);
    expect(ctx.loadFailed).toBe(true);
    expect(noticeText()).toBe("찜 정보를 불러오지 못했어요. 페이지를 새로고침해 주세요.");
    await advance(600_000);
    expect(noticeText()).toBe("찜 정보를 불러오지 못했어요. 페이지를 새로고침해 주세요.");
  });

  it("실패: 첫 시도만 실패한 동안(재시도 대기)이나 로그아웃 상태에서는 불러오기 실패 안내가 없다", async () => {
    api.getWishlistIds.mockRejectedValue(new Error("down"));
    const r = await mount();
    expect(ctx.loadFailed).toBe(false);
    expect(noticeText()).toBe("");
    await advance(3000);
    expect(noticeText()).not.toBe("");
    auth.session = null;
    await refresh(r);
    expect(ctx.loadFailed).toBe(false);
    expect(noticeText()).toBe("");
  });

  it("실패: 불러오기에 성공하면 불러오기 실패 안내가 없다", async () => {
    await mount();
    expect(noticeText()).toBe("");
  });
});

describe("WishlistProvider value 안정성", () => {
  it("성공: 바뀐 게 없으면 다시 렌더링해도 value 객체와 toggle / isWished 함수가 그대로다", async () => {
    const r = await mount();
    const first = ctx;
    await refresh(r);
    expect(ctx).toBe(first);
    expect(ctx.toggle).toBe(first.toggle);
    expect(ctx.isWished).toBe(first.isWished);
    expect(ctx.ids).toBe(first.ids);
  });

  it("성공: 찜이 바뀌면 value와 ids 객체가 새로 만들어진다 (구독자가 알아챈다)", async () => {
    await mount();
    const first = ctx;
    api.addWish.mockResolvedValue(undefined);
    await act(async () => {
      await ctx.toggle("b");
    });
    expect(ctx).not.toBe(first);
    expect(ctx.ids).not.toBe(first.ids);
  });
});
