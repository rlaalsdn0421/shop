import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RegisterForm } from "./RegisterForm";

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
});

async function fillAndSubmit(username: string, email: string, password: string) {
  await userEvent.type(screen.getByPlaceholderText(/^아이디/), username);
  await userEvent.type(screen.getByPlaceholderText("이메일"), email);
  await userEvent.type(screen.getByPlaceholderText(/^비밀번호/), password);
  await userEvent.click(screen.getByRole("button", { name: "회원가입" }));
}

describe("RegisterForm", () => {
  it("성공: 아이디/이메일/비밀번호를 {username, email, password}로 요청하고 로그인 페이지로 이동한다", async () => {
    mockFetchOnce(201, {});
    render(<RegisterForm />);

    await fillAndSubmit("user_01", "user01@example.com", "test-password");

    await waitFor(() => expect(push).toHaveBeenCalledWith("/login"));
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toMatch(/\/api\/auth\/register$/);
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      username: "user_01",
      email: "user01@example.com",
      password: "test-password",
    });
  });

  it("실패: 이미 사용 중인 아이디(400)면 에러 메시지를 보여주고 이동하지 않는다", async () => {
    mockFetchOnce(400, { error: "이미 사용 중인 아이디입니다." });
    render(<RegisterForm />);

    await fillAndSubmit("user_01", "user01@example.com", "test-password");

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 사용 중인 아이디입니다.");
    expect(push).not.toHaveBeenCalled();
  });

  it("성공: 아이디 입력칸은 소문자·숫자·_ 4~20자 패턴과 필수 속성을, 이메일 입력칸은 email 타입을 가진다", () => {
    render(<RegisterForm />);

    const username = screen.getByPlaceholderText(/^아이디/);
    expect(username).toHaveAttribute("id", "register-username");
    expect(username).toHaveAttribute("pattern", "[a-z0-9_]{4,20}");
    expect(username).toBeRequired();
    expect(screen.getByPlaceholderText("이메일")).toHaveAttribute("type", "email");
  });

  it("실패: 패턴에 맞지 않는 아이디(대문자, 3자 이하, 21자 이상)는 유효하지 않고 맞는 값은 유효하다", async () => {
    render(<RegisterForm />);
    const username = screen.getByPlaceholderText(/^아이디/) as HTMLInputElement;

    for (const bad of ["Abcd", "abc", "a".repeat(21), "ab cd"]) {
      await userEvent.clear(username);
      await userEvent.type(username, bad);
      expect(username.validity.patternMismatch, bad).toBe(true);
    }
    for (const ok of ["abcd", "a".repeat(20), "a_1_"]) {
      await userEvent.clear(username);
      await userEvent.type(username, ok);
      expect(username.validity.patternMismatch, ok).toBe(false);
    }
  });
});
