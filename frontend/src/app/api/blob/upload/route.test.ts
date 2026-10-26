import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

type Options = {
  onBeforeGenerateToken: (pathname: string, payload: string | null, multipart: boolean) => Promise<Record<string, unknown>>;
};
const handleUploadMock = vi.fn();
vi.mock("@vercel/blob/client", () => ({ handleUpload: (o: Options) => handleUploadMock(o) }));

import { POST } from "./route";

const PATH_OK = "products/123e4567-e89b-12d3-a456-426614174000.jpg";
const JWT = "aaa.bbb.ccc";
const GENERATE = { type: "blob.generate-client-token", payload: { pathname: "products/a.jpg", clientPayload: null, multipart: false } };

function call(headers: Record<string, string> = {}, body: unknown = GENERATE) {
  return POST(new Request("http://localhost/api/blob/upload", { method: "POST", headers, body: JSON.stringify(body) }));
}

// handleUpload stand-in: runs the route's token callback the way the SDK does and returns its options.
beforeEach(() => {
  vi.stubEnv("BLOB_READ_WRITE_TOKEN", "fake-store-token");
  handleUploadMock.mockReset();
  handleUploadMock.mockImplementation(async (o: Options) => ({
    type: "blob.generate-client-token",
    options: await o.onBeforeGenerateToken(PATH_OK, null, false),
  }));
});
afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("POST /api/blob/upload", () => {
  it("관리자 토큰이면 이미지 형식·5MB·랜덤 이름 조건이 붙은 업로드 토큰을 준다", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 });
    vi.stubGlobal("fetch", fetchMock);

    const res = await call({ authorization: `Bearer ${JWT}` });

    expect(res.status).toBe(200);
    const options = (await res.json()).options;
    expect(options.validUntil).toBeGreaterThan(Date.now());
    expect(options.validUntil).toBeLessThanOrEqual(Date.now() + 60_000);
    delete options.validUntil;
    expect(options).toEqual({
      allowedContentTypes: ["image/jpeg", "image/png", "image/webp"],
      maximumSizeInBytes: 5 * 1024 * 1024,
      addRandomSuffix: true,
    });
    expect(fetchMock.mock.calls[0][0]).toContain("/api/admin/orders?size=1");
    expect(fetchMock.mock.calls[0][1].headers).toEqual({ Authorization: `Bearer ${JWT}` });
  });

  it.each([401, 403])("백엔드가 %i 로 거절한 토큰(일반 회원·만료)은 401 이다", async (status) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status }));
    const res = await call({ authorization: `Bearer ${JWT}` });
    expect(res.status).toBe(401);
    expect((await res.json()).options).toBeUndefined();
  });

  it("토큰이 없으면 백엔드를 부르지 않고 401 이다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    expect((await call()).status).toBe(401);
    expect((await call({ authorization: "Basic abc" })).status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("백엔드에 연결하지 못하거나 5xx 면 로그아웃처럼 보이지 않게 503 이고 허용하지 않는다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("down")));
    expect((await call({ authorization: `Bearer ${JWT}` })).status).toBe(503);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 502 }));
    const res = await call({ authorization: `Bearer ${JWT}` });
    expect(res.status).toBe(503);
    expect((await res.json()).options).toBeUndefined();
  });

  it("JWT 모양이 아니거나 너무 긴 토큰은 백엔드를 부르지 않고 401 이다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    for (const bad of ["garbage", "a.b", "a.b.c.d", "a b.c.d", `${"x".repeat(1600)}.b.c`]) {
      expect((await call({ authorization: `Bearer ${bad}` })).status).toBe(401);
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    "secrets/123e4567-e89b-12d3-a456-426614174000.jpg",
    "products/../123e4567-e89b-12d3-a456-426614174000.jpg",
    "products/a.html",
    "products/123e4567-e89b-12d3-a456-426614174000.svg",
    "products/123e4567-e89b-12d3-a456-426614174000.jpg/x",
  ])("정해진 이름 모양(products/<UUID>.jpg|png|webp)이 아니면 거절한다: %s", async (pathname) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200 }));
    handleUploadMock.mockImplementation((o: Options) => o.onBeforeGenerateToken(pathname, null, false));
    expect((await call({ authorization: `Bearer ${JWT}` })).status).toBe(400);
  });

  it("저장소 토큰이 설정되지 않았으면 503 이다", async () => {
    vi.stubEnv("BLOB_READ_WRITE_TOKEN", "");
    const res = await call({ authorization: `Bearer ${JWT}` });
    expect(res.status).toBe(503);
    expect(handleUploadMock).not.toHaveBeenCalled();
  });

  it("본문이 JSON 이 아니면 400 이다", async () => {
    const res = await POST(new Request("http://localhost/api/blob/upload", { method: "POST", body: "not json" }));
    expect(res.status).toBe(400);
  });
});
