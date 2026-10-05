import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LoginForm } from "./LoginForm";
import { AuthProvider } from "@/auth/AuthContext";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
}));

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
  push.mockClear();
  localStorage.clear();
});

function renderLoginForm() {
  return render(
    <AuthProvider>
      <LoginForm />
    </AuthProvider>
  );
}

describe("LoginForm", () => {
  it("성공: 로그인하면 역할에 맞는 페이지로 이동한다", async () => {
    mockFetchOnce(200, { token: "jwt-token", role: "USER", email: "a@a.com" });
    renderLoginForm();

    await userEvent.type(screen.getByPlaceholderText("아이디 또는 이메일"), "a@a.com");
    await userEvent.type(screen.getByPlaceholderText("비밀번호"), "password1");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/"));
  });

  it("성공: ADMIN/SELLER는 상품 관리 페이지로 이동한다", async () => {
    mockFetchOnce(200, { token: "jwt-token", role: "ADMIN", email: "owner@example.com" });
    renderLoginForm();

    await userEvent.type(screen.getByPlaceholderText("아이디 또는 이메일"), "owner@example.com");
    await userEvent.type(screen.getByPlaceholderText("비밀번호"), "test-password");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
  });

  it("성공: @가 없는 아이디도 형식 검사에 막히지 않고 로그인 요청이 나간다", async () => {
    mockFetchOnce(200, { token: "jwt-token", role: "SELLER", email: "seller01" });
    renderLoginForm();

    const idInput = screen.getByPlaceholderText("아이디 또는 이메일");
    expect(idInput).toHaveAttribute("type", "text");
    await userEvent.type(idInput, "seller01");
    await userEvent.type(screen.getByPlaceholderText("비밀번호"), "test-password");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
  });

  it("실패: 잘못된 비밀번호면 에러 메시지를 보여주고 이동하지 않는다", async () => {
    mockFetchOnce(401, { error: "이메일 또는 비밀번호가 올바르지 않습니다." });
    renderLoginForm();

    await userEvent.type(screen.getByPlaceholderText("아이디 또는 이메일"), "a@a.com");
    await userEvent.type(screen.getByPlaceholderText("비밀번호"), "wrong-password");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "이메일 또는 비밀번호가 올바르지 않습니다."
    );
    expect(push).not.toHaveBeenCalled();
  });
});
