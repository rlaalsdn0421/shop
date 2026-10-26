import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { UPLOAD_FAILED, uploadProductImage } from "@/lib/imageUpload";
import { useImageUploads } from "./ImageUploader";

vi.mock("@/lib/imageUpload", async (orig) => ({
  ...(await orig<typeof import("@/lib/imageUpload")>()),
  uploadProductImage: vi.fn(),
}));
const upload = vi.mocked(uploadProductImage);

const photo = (name = "a.jpg", type = "image/jpeg") => new File(["x"], name, { type });

// An upload that finishes only when the test says so (and ignores the abort signal, like a slow real network).
function pendingUpload() {
  let resolve: (url: string) => void = () => {};
  let reject: (e: Error) => void = () => {};
  upload.mockImplementationOnce(
    () =>
      new Promise<string>((res, rej) => {
        resolve = res;
        reject = rej;
      })
  );
  return { resolve: (url: string) => act(async () => resolve(url)), reject: (e: Error) => act(async () => reject(e)) };
}

beforeEach(() => {
  upload.mockReset();
});

describe("useImageUploads", () => {
  it("성공: 올리기가 끝나면 onUrl로 주소를 넘기고 '올렸어요' 상태가 되며 올리는 중은 아니다", async () => {
    const onUrl = vi.fn();
    upload.mockResolvedValue("https://blob.example.com/a.jpg");
    const { result } = renderHook(() => useImageUploads("tok"));
    await act(async () => {
      await result.current.pick(1, photo(), onUrl);
    });
    expect(onUrl).toHaveBeenCalledExactlyOnceWith("https://blob.example.com/a.jpg");
    expect(result.current.status[1]).toBe("done");
    expect(result.current.busy).toBe(false);
  });

  it("성공: 올리는 동안은 busy이고 끝나면 풀린다", async () => {
    const pending = pendingUpload();
    const { result } = renderHook(() => useImageUploads("tok"));
    act(() => void result.current.pick(1, photo(), vi.fn()));
    expect(result.current.busy).toBe(true);
    await pending.resolve("https://blob.example.com/a.jpg");
    expect(result.current.busy).toBe(false);
  });

  it("성공: 올리는 중에 줄을 취소하면 늦게 끝난 업로드의 주소를 onUrl로 넘기지 않고 상태도 비운다", async () => {
    const onUrl = vi.fn();
    const pending = pendingUpload();
    const { result } = renderHook(() => useImageUploads("tok"));
    act(() => void result.current.pick(1, photo(), onUrl));
    const signal = upload.mock.calls[0][2]!;
    act(() => result.current.cancel(1));
    expect(signal.aborted).toBe(true);
    expect(result.current.busy).toBe(false);

    await pending.resolve("https://blob.example.com/late.jpg");
    expect(onUrl).not.toHaveBeenCalled();
    expect(result.current.status).toEqual({});
  });

  it("실패: 취소한 줄에 늦게 온 업로드 오류도 상태에 남기지 않는다", async () => {
    const pending = pendingUpload();
    const { result } = renderHook(() => useImageUploads("tok"));
    act(() => void result.current.pick(1, photo(), vi.fn()));
    act(() => result.current.cancel(1));
    await pending.reject(new Error(UPLOAD_FAILED));
    expect(result.current.status).toEqual({});
  });

  it("성공: 화면을 떠나면(언마운트) 올리는 중인 업로드를 취소하고, 늦게 끝나도 onUrl을 부르지 않는다", async () => {
    const onUrl = vi.fn();
    const pending = pendingUpload();
    const { result, unmount } = renderHook(() => useImageUploads("tok"));
    act(() => void result.current.pick(1, photo(), onUrl));
    const signal = upload.mock.calls[0][2]!;
    unmount();
    expect(signal.aborted).toBe(true);
    await pending.resolve("https://blob.example.com/late.jpg");
    expect(onUrl).not.toHaveBeenCalled();
  });

  it("성공: 같은 줄에 다시 고르면 앞선 업로드는 취소되고 그 결과는 onUrl로 가지 않는다", async () => {
    const first = vi.fn();
    const second = vi.fn();
    const slow = pendingUpload();
    const { result } = renderHook(() => useImageUploads("tok"));
    act(() => void result.current.pick(1, photo("slow.jpg"), first));
    const firstSignal = upload.mock.calls[0][2]!;
    upload.mockResolvedValueOnce("https://blob.example.com/fast.jpg");
    await act(async () => {
      await result.current.pick(1, photo("fast.jpg"), second);
    });
    expect(firstSignal.aborted).toBe(true);
    expect(second).toHaveBeenCalledExactlyOnceWith("https://blob.example.com/fast.jpg");

    await slow.resolve("https://blob.example.com/slow.jpg");
    expect(first).not.toHaveBeenCalled();
    expect(result.current.status[1]).toBe("done");
  });

  it("실패: 올리기에 실패하면 던진 문구가 상태에 남고 onUrl은 부르지 않으며, 설명 없는 오류는 일반 안내가 된다", async () => {
    const onUrl = vi.fn();
    upload.mockRejectedValueOnce(new Error("로그인이 만료됐을 수 있어요."));
    const { result } = renderHook(() => useImageUploads("tok"));
    await act(async () => {
      await result.current.pick(1, photo(), onUrl);
    });
    expect(result.current.status[1]).toEqual({ error: "로그인이 만료됐을 수 있어요." });
    upload.mockRejectedValueOnce("weird");
    await act(async () => {
      await result.current.pick(2, photo(), onUrl);
    });
    expect(result.current.status[2]).toEqual({ error: UPLOAD_FAILED });
    expect(onUrl).not.toHaveBeenCalled();
    expect(result.current.busy).toBe(false);
  });

  it("실패: 형식이 틀린 파일은 올리지 않고 오류 상태만 남긴다", async () => {
    const onUrl = vi.fn();
    const { result } = renderHook(() => useImageUploads("tok"));
    await act(async () => {
      await result.current.pick(1, photo("note.txt", "text/plain"), onUrl);
    });
    expect(upload).not.toHaveBeenCalled();
    expect(onUrl).not.toHaveBeenCalled();
    expect(result.current.status[1]).toEqual({ error: "JPG, PNG, WEBP 사진만 올릴 수 있어요." });
  });
});
