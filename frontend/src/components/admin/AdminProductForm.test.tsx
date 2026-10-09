import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminProductForm } from "./AdminProductForm";

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
});

const originalPriceInput = () => screen.getByLabelText("정가 (선택)");
const hashtagInput = () => screen.getByLabelText("해시태그 (최대 3개, 공백으로 구분)");

async function fillRequired(price = "8000") {
  await userEvent.type(screen.getByLabelText("상품명"), "린넨 셔츠");
  await userEvent.type(screen.getByLabelText("상품 설명"), "시원한 셔츠");
  await userEvent.type(screen.getByLabelText("가격"), price);
  await userEvent.type(screen.getByLabelText("이미지 URL"), "https://example.com/a.jpg");
  await userEvent.type(screen.getByLabelText("재고 수량"), "5");
}

// The inline messages live in always-mounted status regions; only the non-empty ones count.
const messages = () =>
  screen.getAllByRole("status").map((s) => s.textContent).filter(Boolean);
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
      imageUrl: "https://example.com/a.jpg",
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
