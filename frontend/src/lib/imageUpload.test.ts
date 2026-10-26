import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

const uploadMock = vi.fn();
vi.mock("@vercel/blob/client", () => ({ upload: (...args: unknown[]) => uploadMock(...args) }));

import { MAX_IMAGE_BYTES, SESSION_EXPIRED, UPLOAD_FAILED, UPLOAD_PATH, uploadProductImage, validateImageFile } from "./imageUpload";

const file = (type: string, size = 1000, name = "내 사진.jpg") => new File([new Uint8Array(size)], name, { type });

// The session probe after a failed upload; each test sets what the backend answers.
const probe = vi.fn();

beforeEach(() => {
  uploadMock.mockReset();
  probe.mockReset();
  probe.mockResolvedValue({ status: 200 });
  vi.stubGlobal("fetch", probe);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("validateImageFile", () => {
  it.each(["image/jpeg", "image/png", "image/webp"])("%s 는 통과한다", (type) => {
    expect(validateImageFile({ type, size: 1000 })).toBeNull();
  });

  it.each(["image/gif", "image/svg+xml", "application/pdf", ""])("허용하지 않는 형식(%s)은 막는다", (type) => {
    expect(validateImageFile({ type, size: 1000 })).toBe("JPG, PNG, WEBP 사진만 올릴 수 있어요.");
  });

  it("5MB 는 통과하고 1바이트 넘으면 막는다", () => {
    expect(validateImageFile({ type: "image/png", size: MAX_IMAGE_BYTES })).toBeNull();
    expect(validateImageFile({ type: "image/png", size: MAX_IMAGE_BYTES + 1 })).toBe("사진은 5MB 이하만 올릴 수 있어요.");
  });

  it("빈 파일은 막는다", () => {
    expect(validateImageFile({ type: "image/png", size: 0 })).toBe("빈 파일이에요. 다른 사진을 골라 주세요.");
  });
});

describe("uploadProductImage", () => {
  it("관리자 토큰을 헤더로 보내고 올라간 주소를 돌려준다", async () => {
    uploadMock.mockResolvedValue({ url: "https://abc.public.blob.vercel-storage.com/products/x.jpg" });

    const url = await uploadProductImage(file("image/jpeg"), "tok");

    expect(url).toBe("https://abc.public.blob.vercel-storage.com/products/x.jpg");
    const [pathname, , options] = uploadMock.mock.calls[0];
    expect(options).toMatchObject({
      access: "public",
      handleUploadUrl: UPLOAD_PATH,
      headers: { Authorization: "Bearer tok" },
      contentType: "image/jpeg",
    });
    expect(pathname).toMatch(/^products\/[0-9a-f-]{36}\.jpg$/);
  });

  it("원래 파일 이름은 저장 이름에 쓰지 않는다", async () => {
    uploadMock.mockResolvedValue({ url: "https://x/y.png" });
    await uploadProductImage(file("image/png", 10, "../../etc/passwd.png"), "tok");
    expect(uploadMock.mock.calls[0][0]).toMatch(/^products\/[0-9a-f-]{36}\.png$/);
  });

  it("형식이 틀리면 올리지 않고 검사 문구로 실패한다", async () => {
    await expect(uploadProductImage(file("image/gif"), "tok")).rejects.toThrow("JPG, PNG, WEBP 사진만 올릴 수 있어요.");
    expect(uploadMock).not.toHaveBeenCalled();
  });

  it("업로드가 실패하면 SDK 오류 문구 대신 안내 문구로 바꾼다", async () => {
    uploadMock.mockRejectedValue(new Error("Vercel Blob: Failed to retrieve the client token secret-detail"));
    await expect(uploadProductImage(file("image/webp"), "tok")).rejects.toThrow(UPLOAD_FAILED);
  });

  it("업로드가 실패하고 백엔드가 로그인을 거절하면(401, 403) 로그인 만료 안내로 바꾼다", async () => {
    for (const status of [401, 403]) {
      uploadMock.mockRejectedValue(new Error("Vercel Blob: Failed to retrieve the client token"));
      probe.mockResolvedValue({ status });
      await expect(uploadProductImage(file("image/jpeg"), "tok")).rejects.toThrow(SESSION_EXPIRED);
    }
    const [url, init] = probe.mock.calls[0];
    expect(new URL(String(url)).pathname + new URL(String(url)).search).toBe("/api/admin/orders?size=1");
    expect(init.headers).toMatchObject({ Authorization: "Bearer tok" });
  });

  it("업로드가 실패해도 로그인은 멀쩡하거나(200, 500) 확인이 안 되면(네트워크 오류) 일반 실패 안내다", async () => {
    uploadMock.mockRejectedValue(new Error("Vercel Blob: boom"));
    for (const answer of [{ status: 200 }, { status: 500 }]) {
      probe.mockResolvedValue(answer);
      await expect(uploadProductImage(file("image/jpeg"), "tok")).rejects.toThrow(UPLOAD_FAILED);
    }
    probe.mockRejectedValue(new TypeError("network"));
    await expect(uploadProductImage(file("image/jpeg"), "tok")).rejects.toThrow(UPLOAD_FAILED);
  });

  it("토큰이 비어 있으면 Blob에 가지 않고 로그인 만료 안내로 실패한다", async () => {
    await expect(uploadProductImage(file("image/jpeg"), "")).rejects.toThrow(SESSION_EXPIRED);
    expect(uploadMock).not.toHaveBeenCalled();
    expect(probe).not.toHaveBeenCalled();
  });

  it("성공한 업로드는 로그인 확인 요청을 보내지 않는다", async () => {
    uploadMock.mockResolvedValue({ url: "https://x/y.jpg" });
    await uploadProductImage(file("image/jpeg"), "tok");
    expect(probe).not.toHaveBeenCalled();
  });

  it("취소한 업로드는 취소 오류를 그대로 던진다", async () => {
    const controller = new AbortController();
    controller.abort();
    uploadMock.mockRejectedValue(new DOMException("aborted", "AbortError"));
    await expect(uploadProductImage(file("image/webp"), "tok", controller.signal)).rejects.toMatchObject({
      name: "AbortError",
    });
  });
});
