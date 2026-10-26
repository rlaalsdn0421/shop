import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminProductForm } from "./AdminProductForm";
import { SESSION_EXPIRED, uploadProductImage } from "@/lib/imageUpload";

vi.mock("@/lib/imageUpload", async (orig) => ({
  ...(await orig<typeof import("@/lib/imageUpload")>()),
  uploadProductImage: vi.fn(),
}));
const upload = vi.mocked(uploadProductImage);
const blobUrl = (name: string) => `https://blob.example.com/${name}`;
const A = blobUrl("a.jpg");
const B = blobUrl("b.jpg");
const C = blobUrl("c.jpg");

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => ({ session: { token: "fake-token", role: "ADMIN", username: "seller" }, ready: true }),
}));

function mockFetchOnce(status: number, body: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({ ok: status >= 200 && status < 300, status, json: async () => body })
  );
}

beforeEach(() => {
  vi.unstubAllGlobals();
  push.mockClear();
  upload.mockReset();
  upload.mockImplementation(async (file) => blobUrl(file.name));
});

// Picks a file in the given row's picker and waits until its thumbnail shows (the upload finished).
const photo = (name: string, type = "image/jpeg") => new File(["x"], name, { type });
async function addImage(n: number, name: string) {
  await userEvent.upload(screen.getByLabelText(n === 1 ? "대표 이미지 선택" : `이미지 ${n} 선택`), photo(name));
  await screen.findByAltText(`이미지 ${n} 미리보기`);
}
const thumbnails = () => screen.queryAllByRole("img").map((i) => i.getAttribute("src"));

const originalPriceInput = () => screen.getByLabelText("정가 (선택)");
const hashtagInput = () => screen.getByLabelText("해시태그 (최대 3개, 공백으로 구분)");

async function fillRequired(price = "8000") {
  await userEvent.type(screen.getByLabelText("상품명"), "린넨 셔츠");
  await userEvent.type(screen.getByLabelText("상품 설명"), "시원한 셔츠");
  await userEvent.type(screen.getByLabelText("가격"), price);
  await addImage(1, "a.jpg");
  await userEvent.type(screen.getByLabelText("재고 수량"), "5");
}

// The inline messages live in always-mounted status regions; only the non-empty ones count.
const statusTexts = (keep: (id: string) => boolean) =>
  screen.getAllByRole("status").filter((s) => keep(s.id)).map((s) => s.textContent).filter(Boolean);
const messages = () => statusTexts((id) => !id.startsWith("product-image-"));
const uploadMessages = () => statusTexts((id) => id.startsWith("product-image-"));
const form = () => screen.getByRole("button", { name: /등록/ }).closest("form")!;
const submit = () => userEvent.click(screen.getByRole("button", { name: "등록하기" }));
const sentBody = () => JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));

describe("AdminProductForm 정가/해시태그", () => {
  it("성공: 정가와 해시태그를 안 쓰면 요청에 두 필드가 아예 없다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
    expect(sentBody()).toEqual({
      name: "린넨 셔츠",
      description: "시원한 셔츠",
      price: 8000,
      imageUrl: A,
      imageUrls: [A],
      stock: 5,
      category: "",
    });
  });

  it("성공: 정가는 숫자로, 해시태그는 # 없는 배열로 보낸다 (공백/중복/# 정리)", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(originalPriceInput(), "10000");
    await userEvent.type(hashtagInput(), "#여름  린넨 #린넨 Daily_Look");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({
      price: 8000,
      originalPrice: 10000,
      hashtags: ["여름", "린넨", "Daily_Look"],
    });
  });

  it("성공: 정가 칸의 최솟값은 판매가 + 1이다", async () => {
    render(<AdminProductForm />);
    await userEvent.type(screen.getByLabelText("가격"), "8000");
    expect(originalPriceInput()).toHaveAttribute("min", "8001");
  });

  it("실패: 정가가 판매가 이하이면 오류를 보여주고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(originalPriceInput(), "8000");
    expect(messages()).toEqual(["정가는 판매가보다 커야 해요."]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(originalPriceInput()).toHaveAccessibleDescription("정가는 판매가보다 커야 해요.");
    await submit();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 해시태그가 4개면 오류를 보여주고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "a b c d");
    expect(messages()).toEqual(["해시태그는 최대 3개까지 입력할 수 있어요."]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(hashtagInput()).toHaveAccessibleDescription("해시태그는 최대 3개까지 입력할 수 있어요.");
    await submit();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 21자 해시태그는 오류", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "가".repeat(21));
    expect(messages()).toEqual(["해시태그는 하나당 20자까지 입력할 수 있어요."]);
    await submit();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 허용되지 않는 문자가 있으면 오류, 고치면 사라진다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "여름,린넨");
    expect(messages()).toEqual(["해시태그는 글자, 숫자, 밑줄(_)만 쓸 수 있어요."]);
    await submit();
    expect(fetch).not.toHaveBeenCalled();

    await userEvent.clear(hashtagInput());
    await userEvent.type(hashtagInput(), "여름 린넨");
    expect(messages()).toEqual([]);
    expect(hashtagInput()).not.toHaveAttribute("aria-invalid");
  });

  it("실패: 정가 가드 — 네이티브 검증을 거치지 않고 submit해도 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(originalPriceInput(), "8000");
    fireEvent.submit(form());
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 해시태그 가드 — 네이티브 검증 없이 submit해도 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "a b c d");
    fireEvent.submit(form());
    expect(fetch).not.toHaveBeenCalled();
  });

  it("성공: 막힌 제출은 조용하지 않다 — 첫 잘못된 입력으로 포커스가 가고 메시지는 그대로 보인다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "a b c d");
    await userEvent.type(originalPriceInput(), "8000");
    // Two invalid fields: the one that comes first in the form gets the focus.
    fireEvent.submit(form());
    expect(originalPriceInput()).toHaveFocus();
    expect(screen.getAllByRole("status").map((s) => s.textContent)).toEqual(
      expect.arrayContaining(["정가는 판매가보다 커야 해요.", "해시태그는 최대 3개까지 입력할 수 있어요."])
    );

    await userEvent.clear(originalPriceInput());
    fireEvent.submit(form());
    expect(hashtagInput()).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 판매가/정가가 2147483647을 넘으면 오류를 보여주고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired("2147483648");
    expect(screen.getByLabelText("가격")).toHaveAttribute("max", "2147483647");
    expect(originalPriceInput()).toHaveAttribute("max", "2147483647");
    expect(messages()).toEqual(["가격은 2,147,483,647원 이하로 입력해 주세요."]);
    fireEvent.submit(form());
    expect(screen.getByLabelText("가격")).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();

    await userEvent.clear(screen.getByLabelText("가격"));
    await userEvent.type(screen.getByLabelText("가격"), "8000");
    await userEvent.type(originalPriceInput(), "2147483648");
    expect(messages()).toEqual(["정가는 2,147,483,647원 이하로 입력해 주세요."]);
    fireEvent.submit(form());
    expect(originalPriceInput()).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("성공: 2147483647은 그대로 보낸다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired("2147483647");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().price).toBe(2147483647);
  });

  it("성공: 빠르게 두 번 눌러도 POST는 한 번이고, 성공 뒤에도 버튼은 잠겨 있다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    fireEvent.submit(form());
    fireEvent.submit(form());
    await waitFor(() => expect(push).toHaveBeenCalledTimes(1));
    await userEvent.click(screen.getByRole("button", { name: /등록/ }));
    fireEvent.submit(form());
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: /등록/ })).toBeDisabled();
  });

  it("실패: 서버 오류 뒤에는 잠금이 풀려 다시 제출할 수 있다", async () => {
    mockFetchOnce(400, { error: "등록 실패" });
    render(<AdminProductForm />);
    await fillRequired();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("등록 실패");
    expect(screen.getByRole("button", { name: "등록하기" })).toBeEnabled();
    await submit();
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  });

  it("실패: 서버가 보낸 오류 메시지를 그대로 보여준다", async () => {
    mockFetchOnce(400, { error: "정가는 판매가보다 커야 해요." });
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "ok");
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("정가는 판매가보다 커야 해요.");
    expect(push).not.toHaveBeenCalled();
  });
});

describe("AdminProductForm 이미지 올리기", () => {
  const addButton = () => screen.getByRole("button", { name: "이미지 추가" });
  const addRow = () => userEvent.click(addButton());
  const picker = (n: number) => screen.getByLabelText(n === 1 ? "대표 이미지 선택" : `이미지 ${n} 선택`);
  // A pick that stays pending until resolved/rejected, to look at the state while uploading.
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
  const fillText = async () => {
    await userEvent.type(screen.getByLabelText("상품명"), "린넨 셔츠");
    await userEvent.type(screen.getByLabelText("상품 설명"), "시원한 셔츠");
    await userEvent.type(screen.getByLabelText("가격"), "8000");
    await userEvent.type(screen.getByLabelText("재고 수량"), "5");
  };
  // user-event drops files that do not match `accept`; the guard must also hold when the browser lets one through.
  const pickAnyFile = (n: number, file: File) => userEvent.setup({ applyAccept: false }).upload(picker(n), file);

  it("성공: 처음에는 대표 이미지 선택 한 줄만 있고 사진 형식만 고르게 하며 삭제 버튼은 없다", () => {
    render(<AdminProductForm />);
    expect(picker(1)).toHaveAttribute("type", "file");
    expect(picker(1)).toHaveAttribute("accept", "image/jpeg,image/png,image/webp");
    expect(screen.queryByLabelText("이미지 2 선택")).not.toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
  });

  it("성공: 사진을 올리면 로그인 토큰으로 올리고 미리보기가 생기며, 같은 파일을 다시 골라도 올라간다", async () => {
    render(<AdminProductForm />);
    const file = photo("a.jpg");
    await userEvent.upload(picker(1), file);
    expect(await screen.findByAltText("이미지 1 미리보기")).toHaveAttribute("src", A);
    expect(upload).toHaveBeenCalledWith(file, "fake-token", expect.any(AbortSignal));
    await userEvent.upload(picker(1), file);
    expect(upload).toHaveBeenCalledTimes(2);
  });

  it("성공: 올리는 동안은 '올리는 중', 끝나면 '사진을 올렸어요.'가 보이고, 줄을 지우면 사라진다", async () => {
    const pending = pendingUpload();
    render(<AdminProductForm />);
    await userEvent.upload(picker(1), photo("a.jpg"));
    expect(uploadMessages()).toEqual(["올리는 중이에요..."]);
    await pending.resolve(A);
    expect(uploadMessages()).toEqual(["사진을 올렸어요."]);
    expect(thumbnails()).toEqual([A]);
    // done is not "busy": the submit button is on again
    expect(screen.getByRole("button", { name: "등록하기" })).toBeEnabled();

    await addRow();
    await addImage(2, "b.jpg");
    expect(uploadMessages()).toEqual(["사진을 올렸어요.", "사진을 올렸어요."]);
    await userEvent.click(screen.getByRole("button", { name: "이미지 2 삭제" }));
    expect(uploadMessages()).toEqual(["사진을 올렸어요."]);
  });

  it("성공: 추가하면 새 줄에 포커스가 가고, 삭제하면 그 줄이 사라지고 남은 줄의 사진은 그대로 있다", async () => {
    render(<AdminProductForm />);
    await addImage(1, "a.jpg");
    await addRow();
    expect(picker(2)).toHaveFocus();
    await addImage(2, "b.jpg");
    await addRow();
    await addImage(3, "c.jpg");
    expect(thumbnails()).toEqual([A, B, C]);

    await userEvent.click(screen.getByRole("button", { name: "이미지 2 삭제" }));
    expect(screen.queryByLabelText("이미지 3 선택")).not.toBeInTheDocument();
    expect(thumbnails()).toEqual([A, C]);
    expect(picker(1)).toHaveFocus();
  });

  it("성공: 10장까지 늘릴 수 있고 그 이후에는 추가 버튼이 막힌다", async () => {
    render(<AdminProductForm />);
    for (let i = 0; i < 9; i++) await addRow();
    expect(picker(10)).toBeInTheDocument();
    expect(addButton()).toBeDisabled();
    expect(screen.getByText("이미지는 최대 10장까지 등록할 수 있어요.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "이미지 10 삭제" }));
    expect(addButton()).toBeEnabled();
  });

  it("성공: 올린 사진의 주소를 imageUrl(첫 번째)과 imageUrls(전부)로 함께 보내고 비워 둔 줄은 뺀다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await addRow();
    await addRow();
    await addRow();
    await addImage(2, "b.jpg");
    await addImage(4, "c.jpg");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
    expect(sentBody()).toMatchObject({ imageUrl: A, imageUrls: [A, B, C] });
  });

  it("실패: JPG/PNG/WEBP가 아닌 파일은 안내만 보이고 올리지 않는다", async () => {
    render(<AdminProductForm />);
    await pickAnyFile(1, photo("note.txt", "text/plain"));
    expect(uploadMessages()).toEqual(["JPG, PNG, WEBP 사진만 올릴 수 있어요."]);
    expect(picker(1)).toHaveAccessibleDescription("JPG, PNG, WEBP 사진만 올릴 수 있어요.");
    expect(upload).not.toHaveBeenCalled();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("실패: 5MB를 넘는 파일은 안내만 보이고 올리지 않으며, 5MB 딱 맞는 파일은 올라간다", async () => {
    render(<AdminProductForm />);
    const big = photo("big.jpg");
    Object.defineProperty(big, "size", { value: 5 * 1024 * 1024 + 1 });
    await userEvent.upload(picker(1), big);
    expect(uploadMessages()).toEqual(["사진은 5MB 이하만 올릴 수 있어요."]);
    expect(upload).not.toHaveBeenCalled();

    const exact = photo("exact.jpg");
    Object.defineProperty(exact, "size", { value: 5 * 1024 * 1024 });
    await userEvent.upload(picker(1), exact);
    expect(await screen.findByAltText("이미지 1 미리보기")).toBeInTheDocument();
    expect(uploadMessages()).toEqual(["사진을 올렸어요."]);
  });

  it("실패: 올리기에 실패하면 던진 안내를 보여 주고 줄은 비어 있으며, 다시 올리면 안내가 사라진다", async () => {
    upload.mockRejectedValueOnce(new Error("사진을 올리지 못했어요. 잠시 뒤 다시 시도해 주세요."));
    render(<AdminProductForm />);
    await userEvent.upload(picker(1), photo("a.jpg"));
    expect(await screen.findByText("사진을 올리지 못했어요. 잠시 뒤 다시 시도해 주세요.")).toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();

    await userEvent.upload(picker(1), photo("a.jpg"));
    expect(await screen.findByAltText("이미지 1 미리보기")).toBeInTheDocument();
    expect(uploadMessages()).toEqual(["사진을 올렸어요."]);
  });

  it("실패: 로그인 만료로 올리지 못하면 그 안내를 보여 주고 줄은 비어 있다", async () => {
    upload.mockRejectedValueOnce(new Error(SESSION_EXPIRED));
    render(<AdminProductForm />);
    await userEvent.upload(picker(1), photo("a.jpg"));
    expect(await screen.findByText(SESSION_EXPIRED)).toBeInTheDocument();
    expect(picker(1)).toHaveAccessibleDescription(SESSION_EXPIRED);
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("성공: 올리는 중에는 등록하기가 꺼져 있고 제출해도 요청하지 않으며, 끝나면 켜진다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await addRow();
    const pending = pendingUpload();
    await userEvent.upload(picker(2), photo("b.jpg"));
    expect(screen.getByRole("button", { name: "등록하기" })).toBeDisabled();
    fireEvent.submit(form());
    expect(fetch).not.toHaveBeenCalled();

    await pending.resolve(B);
    expect(screen.getByRole("button", { name: "등록하기" })).toBeEnabled();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual([A, B]);
  });

  it("실패: 올리기가 실패해도 등록하기는 다시 켜진다", async () => {
    render(<AdminProductForm />);
    const pending = pendingUpload();
    await userEvent.upload(picker(1), photo("a.jpg"));
    expect(screen.getByRole("button", { name: "등록하기" })).toBeDisabled();
    await pending.reject(new Error("사진을 올리지 못했어요. 잠시 뒤 다시 시도해 주세요."));
    expect(screen.getByRole("button", { name: "등록하기" })).toBeEnabled();
  });

  it("실패: 대표 이미지가 없으면 안내와 함께 대표 이미지 선택으로 포커스가 가고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillText();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("대표 이미지를 올려 주세요.");
    expect(picker(1)).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 첫 줄이 비어 있으면 두 번째 줄에 사진이 있어도 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillText();
    await addRow();
    await addImage(2, "b.jpg");
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("대표 이미지를 올려 주세요.");
    expect(picker(1)).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("성공: 이미 사진이 있는 줄에 다시 올리면 새 사진으로 바뀌고 그 주소를 보낸다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.upload(picker(1), photo("new.jpg"));
    await waitFor(() => expect(thumbnails()).toEqual([blobUrl("new.jpg")]));
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({ imageUrl: blobUrl("new.jpg"), imageUrls: [blobUrl("new.jpg")] });
  });

  it("실패: 교체하려던 사진이 형식 오류이면 기존 사진은 그대로 남는다", async () => {
    render(<AdminProductForm />);
    await addImage(1, "a.jpg");
    await pickAnyFile(1, photo("note.txt", "text/plain"));
    expect(uploadMessages()).toEqual(["JPG, PNG, WEBP 사진만 올릴 수 있어요. 기존 사진은 그대로예요."]);
    expect(thumbnails()).toEqual([A]);
  });

  it("성공: 올리는 중에 그 줄을 지우면 취소되고 등록하기가 다시 켜진다 (늦은 응답 처리는 훅 테스트에서 확인)", async () => {
    render(<AdminProductForm />);
    await addImage(1, "a.jpg");
    await addRow();
    const pending = pendingUpload();
    await userEvent.upload(picker(2), photo("b.jpg"));
    const signal = upload.mock.calls[1][2]!;
    expect(screen.getByRole("button", { name: "등록하기" })).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "이미지 2 삭제" }));
    expect(signal.aborted).toBe(true);
    expect(screen.getByRole("button", { name: "등록하기" })).toBeEnabled();
    await pending.resolve(B);
    expect(thumbnails()).toEqual([A]);
    expect(uploadMessages()).toEqual(["사진을 올렸어요."]);
  });

  it("성공: 올리는 중에 같은 줄에 다시 고르면 앞선 것은 취소되고 나중 사진만 남는다", async () => {
    render(<AdminProductForm />);
    const slow = pendingUpload();
    await userEvent.upload(picker(1), photo("slow.jpg"));
    const slowSignal = upload.mock.calls[0][2]!;
    await userEvent.upload(picker(1), photo("fast.jpg"));
    expect(slowSignal.aborted).toBe(true);
    await waitFor(() => expect(thumbnails()).toEqual([blobUrl("fast.jpg")]));
    await slow.resolve(blobUrl("slow.jpg"));
    expect(thumbnails()).toEqual([blobUrl("fast.jpg")]);
  });

  it("성공: 등록 요청을 보내는 동안에는 파일 선택이 꺼져 있다", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    render(<AdminProductForm />);
    await fillRequired();
    expect(picker(1)).toBeEnabled();
    await submit();
    expect(picker(1)).toBeDisabled();
  });
});

describe("AdminProductForm 재고 한도", () => {
  const stockInput = () => screen.getByLabelText("재고 수량");
  const TOO_MANY = "재고는 1,000,000개 이하로 입력해 주세요.";

  it("실패: 재고가 1,000,000개를 넘으면 오류를 보여주고 요청하지 않고 재고 칸에 커서를 둔다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    expect(stockInput()).toHaveAttribute("max", "1000000");
    expect(stockInput()).not.toHaveAttribute("aria-invalid");
    fireEvent.change(stockInput(), { target: { value: "1000001" } });
    expect(messages()).toEqual([TOO_MANY]);
    expect(stockInput()).toHaveAttribute("aria-invalid", "true");
    expect(stockInput()).toHaveAccessibleDescription(TOO_MANY);
    // The native max check would stop a click first, so submit the form directly to reach the guard.
    fireEvent.submit(form());
    expect(fetch).not.toHaveBeenCalled();
    expect(stockInput()).toHaveFocus();
  });

  it("성공: 재고 1,000,000개는 통과하고, 고치면 오류가 사라진다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    fireEvent.change(stockInput(), { target: { value: "1000001" } });
    expect(messages()).toEqual([TOO_MANY]);
    fireEvent.change(stockInput(), { target: { value: "1000000" } });
    expect(messages()).toEqual([]);
    expect(stockInput()).not.toHaveAttribute("aria-invalid");
    expect(stockInput()).not.toHaveAttribute("aria-describedby");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
    expect(sentBody().stock).toBe(1000000);
  });

  it("성공: 옵션을 켜면 단일 재고 칸의 오류는 제출을 막지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    fireEvent.change(stockInput(), { target: { value: "1000001" } });
    await userEvent.click(screen.getByLabelText("옵션 사용"));
    await userEvent.type(screen.getByLabelText("옵션 1 색상 이름"), "화이트");
    await userEvent.type(screen.getByLabelText("옵션 1 사이즈"), "M");
    await userEvent.type(screen.getByLabelText("옵션 1 가격"), "1000");
    await userEvent.type(screen.getByLabelText("옵션 1 재고"), "4");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().stock).toBe(4);
  });
});

describe("AdminProductForm 옵션", () => {
  async function fillBase() {
    await userEvent.type(screen.getByLabelText("상품명"), "린넨 셔츠");
    await userEvent.type(screen.getByLabelText("상품 설명"), "시원한 셔츠");
    await addImage(1, "a.jpg");
  }
  const type = (label: string, value: string) => userEvent.type(screen.getByLabelText(label), value);
  async function fillRow(n: number, v: { color?: string; size?: string; price?: string; original?: string; stock?: string }) {
    if (v.color) await type(`옵션 ${n} 색상 이름`, v.color);
    if (v.size) await type(`옵션 ${n} 사이즈`, v.size);
    if (v.price) await type(`옵션 ${n} 가격`, v.price);
    if (v.original) await type(`옵션 ${n} 정가 (선택)`, v.original);
    if (v.stock) await type(`옵션 ${n} 재고`, v.stock);
  }
  const optionsToggle = () => screen.getByLabelText("옵션 사용");
  const addRow = () => userEvent.click(screen.getByRole("button", { name: "옵션 추가" }));
  const note = () => screen.getByText(/개 옵션 ·/).textContent;

  it("성공: 옵션 사용을 켜면 단일 가격·정가·재고 칸이 사라지고 옵션 행이 나온다", async () => {
    render(<AdminProductForm />);
    expect(optionsToggle()).not.toBeChecked();
    expect(screen.queryByLabelText("색상 옵션 사용")).not.toBeInTheDocument();
    await userEvent.click(optionsToggle());
    expect(screen.queryByLabelText("가격")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("정가 (선택)")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("재고 수량")).not.toBeInTheDocument();
    expect(screen.getByLabelText("색상 옵션 사용")).toBeChecked();
    expect(screen.getByLabelText("사이즈 옵션 사용")).toBeChecked();
    for (const label of ["색상 이름", "색상 선택", "사이즈", "가격", "정가 (선택)", "재고"]) {
      expect(screen.getByLabelText(`옵션 1 ${label}`)).toBeInTheDocument();
    }
    await userEvent.click(optionsToggle());
    expect(screen.getByLabelText("가격")).toBeInTheDocument();
    expect(screen.getByLabelText("재고 수량")).toBeInTheDocument();
    expect(screen.queryByLabelText("옵션 1 가격")).not.toBeInTheDocument();
  });

  it("성공: 색상과 사이즈 옵션을 보내며 최저가와 재고 합계도 최상위에 함께 보낸다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "25000", original: "30000", stock: "3" });
    fireEvent.change(screen.getByLabelText("옵션 1 색상 선택"), { target: { value: "#ffffff" } });
    await addRow();
    await fillRow(2, { color: " 블랙 ", size: "M", price: "23000", stock: "10" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
    expect(sentBody()).toEqual({
      name: "린넨 셔츠",
      description: "시원한 셔츠",
      price: 23000,
      imageUrl: A,
      imageUrls: [A],
      stock: 13,
      category: "",
      options: [
        { colorName: "화이트", colorHex: "#ffffff", sizeLabel: "M", price: 25000, originalPrice: 30000, stock: 3 },
        { colorName: "블랙", sizeLabel: "M", price: 23000, stock: 10 },
      ],
    });
  });

  it("성공: 사이즈만 쓰면 색상 칸이 없고 요청에도 색상 필드가 없다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await userEvent.click(screen.getByLabelText("색상 옵션 사용"));
    expect(screen.queryByLabelText("옵션 1 색상 이름")).not.toBeInTheDocument();
    await fillRow(1, { size: "FREE", price: "9000", stock: "4" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().options).toEqual([{ sizeLabel: "FREE", price: 9000, stock: 4 }]);
  });

  it("성공: 색상만 쓰면 사이즈 칸이 없고 색 선택을 지우면 색 코드를 보내지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await userEvent.click(screen.getByLabelText("사이즈 옵션 사용"));
    expect(screen.queryByLabelText("옵션 1 사이즈")).not.toBeInTheDocument();
    await fillRow(1, { color: "민트", price: "9000", stock: "4" });
    expect(screen.getByText("색 선택 안 함 (글자 칩)")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("옵션 1 색상 선택"), { target: { value: "#00ff00" } });
    expect(screen.getByText("#00ff00")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "옵션 1 색상 지우기" }));
    expect(screen.getByText("색 선택 안 함 (글자 칩)")).toBeInTheDocument();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().options).toEqual([{ colorName: "민트", price: 9000, stock: 4 }]);
  });

  it("성공: 옵션을 켰다 꺼도 옛 방식 요청에는 options가 없다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await userEvent.click(optionsToggle());
    await userEvent.click(optionsToggle());
    await fillRequired();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody()).toEqual({
      name: "린넨 셔츠",
      description: "시원한 셔츠",
      price: 8000,
      imageUrl: A,
      imageUrls: [A],
      stock: 5,
      category: "",
    });
  });

  it("실패: 색상과 사이즈를 둘 다 끄면 안내를 보이고 요청하지 않으며 색상 체크박스로 포커스를 옮긴다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await userEvent.click(screen.getByLabelText("색상 옵션 사용"));
    await userEvent.click(screen.getByLabelText("사이즈 옵션 사용"));
    expect(screen.getByRole("alert")).toHaveTextContent("색상 또는 사이즈 중 하나는 사용해야 해요.");
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByLabelText("색상 옵션 사용")).toHaveFocus();
  });

  it("실패: 비어 있는 옵션 행은 제출 전에는 조용하고, 제출하면 인라인 오류와 첫 칸 포커스가 생기며 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getAllByRole("alert").map((a) => a.textContent)).toEqual([
      "색상 이름을 입력해 주세요.",
      "사이즈를 입력해 주세요.",
      "가격은 0 이상의 정수로 입력해 주세요.",
      "재고는 0 이상의 정수로 입력해 주세요.",
    ]);
    const colorInput = screen.getByLabelText("옵션 1 색상 이름");
    expect(colorInput).toHaveFocus();
    expect(colorInput).toHaveAttribute("aria-invalid", "true");
    expect(colorInput).toHaveAccessibleDescription("색상 이름을 입력해 주세요.");
  });

  it("실패: 오류가 있는 첫 칸으로 포커스를 옮기고, 고치면 오류가 사라진다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "25000", stock: "3" });
    await addRow();
    await fillRow(2, { color: "블랙", size: "M", price: "25000", original: "20000", stock: "3" });
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("정가는 판매가보다 커야 해요.");
    expect(screen.getByLabelText("옵션 2 정가 (선택)")).toHaveFocus();
    await userEvent.clear(screen.getByLabelText("옵션 2 정가 (선택)"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 같은 색상·사이즈 조합은 공백과 대소문자 차이가 있어도 중복 오류가 난다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "White", size: "m", price: "1000", stock: "1" });
    await addRow();
    await fillRow(2, { color: " white ", size: "M", price: "1000", stock: "1" });
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("같은 색상과 사이즈 조합의 옵션이 이미 있어요.");
    expect(screen.getByLabelText("옵션 2 색상 이름")).toHaveFocus();
  });

  it("실패: 31자 이름과 쓸 수 없는 문자는 막는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "가".repeat(31), size: "M", price: "1000", stock: "1" });
    await submit();
    expect(screen.getByRole("alert")).toHaveTextContent("30자 이하");
    expect(fetch).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("옵션 1 색상 이름"), { target: { value: "화이트​" } });
    expect(screen.getByRole("alert")).toHaveTextContent("옵션 이름에 쓸 수 없는 문자가 있어요.");
  });

  it("성공: 행을 추가하고 삭제하며, 행이 하나뿐이면 삭제 버튼이 없다", async () => {
    render(<AdminProductForm />);
    await userEvent.click(optionsToggle());
    expect(screen.queryByRole("button", { name: /옵션 1 삭제/ })).not.toBeInTheDocument();
    await addRow();
    expect(screen.getByLabelText("옵션 2 색상 이름")).toHaveFocus();
    await type("옵션 2 색상 이름", "블랙");
    await userEvent.click(screen.getByRole("button", { name: "옵션 1 삭제" }));
    // The remaining row keeps its own text and is renumbered.
    expect(screen.queryByLabelText("옵션 2 색상 이름")).not.toBeInTheDocument();
    expect(screen.getByLabelText("옵션 1 색상 이름")).toHaveValue("블랙");
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
  });

  it("실패: 옵션은 50개까지만 추가할 수 있다", async () => {
    render(<AdminProductForm />);
    await userEvent.click(optionsToggle());
    // fireEvent: 49 userEvent clicks (each re-rendering up to 50 rows) would time out
    for (let i = 1; i < 50; i++) fireEvent.click(screen.getByText("옵션 추가"));
    expect(screen.getByLabelText("옵션 50 색상 이름")).toBeInTheDocument();
    expect(screen.getByText("옵션 추가")).toBeDisabled();
    expect(screen.getByText("옵션은 최대 50개까지 등록할 수 있어요.")).toBeInTheDocument();
  }, 30_000);

  it("성공: 옵션 개수·최저가·재고 합계를 바로 보여준다", async () => {
    render(<AdminProductForm />);
    await userEvent.click(optionsToggle());
    expect(note()).toBe("1개 옵션 · 최저가 - · 재고 합계 0개");
    await fillRow(1, { price: "30000", stock: "2" });
    await addRow();
    await fillRow(2, { price: "20000", stock: "3" });
    expect(note()).toBe("2개 옵션 · 최저가 20,000원 · 재고 합계 5개");
  });

  it("성공: 이미지 여러 장과 함께 옵션을 보내도 imageUrls가 유지된다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(screen.getByRole("button", { name: "이미지 추가" }));
    await addImage(2, "b.jpg");
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "1" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({
      imageUrl: A,
      imageUrls: [A, B],
    });
  });

  it("실패: 서버가 거절하면 role=alert로 메시지를 보이고 다시 제출할 수 있다", async () => {
    mockFetchOnce(400, { error: "같은 색상과 사이즈 조합의 옵션이 중복되어 있어요." });
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "1" });
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("중복되어 있어요");
    expect(screen.getByRole("button", { name: "등록하기" })).toBeEnabled();
    expect(push).not.toHaveBeenCalled();
  });

  it("성공: 옵션 재고 합계가 1,000,000을 넘으면 최상위 재고는 1,000,000으로 줄여 보낸다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "600000" });
    await addRow();
    await fillRow(2, { color: "블랙", size: "M", price: "1000", stock: "600000" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    const body = sentBody();
    expect(body.stock).toBe(1000000);
    expect(body.options.map((o: { stock: number }) => o.stock)).toEqual([600000, 600000]);
  });

  it("실패: 옵션 한 줄의 재고가 1,000,000을 넘으면 그 칸에 오류를 보이고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "1000001" });
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("재고는 1,000,000개 이하로 입력해 주세요.");
    expect(screen.getByLabelText("옵션 1 재고")).toHaveFocus();
  });

  it("성공: 옵션을 켜면 옛 가격 입력의 오류는 제출을 막지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    fireEvent.change(screen.getByLabelText("가격"), { target: { value: "99999999999" } });
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "1" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().price).toBe(1000);
  });
});
