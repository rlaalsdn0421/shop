import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
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

// 첫 번째 비밀번호 칸 (확인 칸은 placeholder가 "비밀번호 확인"이라 괄호로 구분한다)
const passwordInput = () => screen.getByPlaceholderText(/^비밀번호 \(/);
const confirmInput = () => screen.getByLabelText("비밀번호 확인");
const birthInput = () => screen.getByLabelText(/생년월일/);

async function fillAndSubmit(
  username: string,
  email: string,
  password: string,
  { confirm = password, birthDate = "2000-01-31" }: { confirm?: string; birthDate?: string } = {}
) {
  await userEvent.type(screen.getByPlaceholderText(/^아이디/), username);
  await userEvent.type(screen.getByPlaceholderText("이메일"), email);
  await userEvent.type(passwordInput(), password);
  await userEvent.type(confirmInput(), confirm);
  fireEvent.change(birthInput(), { target: { value: birthDate } });
  await userEvent.click(screen.getByRole("button", { name: "회원가입" }));
}

describe("RegisterForm", () => {
  it("성공: 아이디/이메일/비밀번호/생년월일을 {username, email, password, birthDate}로만 요청하고 로그인 페이지로 이동한다", async () => {
    mockFetchOnce(201, {});
    render(<RegisterForm />);

    await fillAndSubmit("user_01", "user01@example.com", "pass1234!");

    await waitFor(() => expect(push).toHaveBeenCalledWith("/login"));
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toMatch(/\/api\/auth\/register$/);
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      username: "user_01",
      email: "user01@example.com",
      password: "pass1234!",
      birthDate: "2000-01-31",
    });
  });

  it("성공: 생년월일 입력칸은 date 타입·필수·bday 자동완성이고 min=1900-01-01이며 max는 없다", () => {
    render(<RegisterForm />);

    const birth = birthInput();
    expect(birth).toHaveAttribute("type", "date");
    expect(birth).toBeRequired();
    expect(birth).toHaveAttribute("autocomplete", "bday");
    expect(birth).toHaveAttribute("min", "1900-01-01");
    expect(birth).not.toHaveAttribute("max");
  });

  it("성공: 두 비밀번호 칸 모두 new-password 자동완성이고 확인 칸은 필수다", () => {
    render(<RegisterForm />);

    expect(passwordInput()).toHaveAttribute("autocomplete", "new-password");
    expect(confirmInput()).toHaveAttribute("type", "password");
    expect(confirmInput()).toHaveAttribute("autocomplete", "new-password");
    expect(confirmInput()).toBeRequired();
  });

  it("성공: 비밀번호 확인 입력칸은 비밀번호 입력칸 바로 다음 입력칸이다", () => {
    render(<RegisterForm />);

    const all = Array.from(document.querySelectorAll("input"));
    expect(all.indexOf(confirmInput() as HTMLInputElement)).toBe(all.indexOf(passwordInput() as HTMLInputElement) + 1);
  });

  it("실패: 비밀번호 확인이 다르면 role=alert로 안내하고 요청을 보내지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<RegisterForm />);

    await fillAndSubmit("user_01", "user01@example.com", "pass1234!", { confirm: "pass1234?" });

    expect(await screen.findByRole("alert")).toHaveTextContent("비밀번호가 서로 달라요.");
    expect(confirmInput()).toHaveAttribute("aria-invalid", "true");
    expect(confirmInput()).toHaveAccessibleDescription("비밀번호가 서로 달라요.");
    expect(fetch).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });

  it("성공: 확인 칸에 올바른 앞부분을 입력하는 동안은 조용하고, 끝까지 같아도 안내가 없다", async () => {
    render(<RegisterForm />);

    await userEvent.type(passwordInput(), "pass1234!");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    await userEvent.type(confirmInput(), "pass");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(confirmInput()).toHaveAttribute("aria-invalid", "false");

    await userEvent.type(confirmInput(), "1234!");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 확인 칸에 틀린 글자나 더 긴 값을 넣으면 바로 안내하고, 고치면 사라진다", async () => {
    render(<RegisterForm />);
    await userEvent.type(passwordInput(), "pass1234!");

    await userEvent.type(confirmInput(), "px");
    expect(screen.getByRole("alert")).toHaveTextContent("비밀번호가 서로 달라요.");
    expect(confirmInput()).toHaveAttribute("aria-invalid", "true");

    await userEvent.clear(confirmInput());
    await userEvent.type(confirmInput(), "pass1234!x");
    expect(screen.getByRole("alert")).toHaveTextContent("비밀번호가 서로 달라요.");

    await userEvent.type(confirmInput(), "{Backspace}");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 올바른 앞부분만 입력하고 제출하면 안내를 띄우고 요청을 보내지 않는다 (두 번 제출해도 마찬가지)", async () => {
    mockFetchOnce(201, {});
    render(<RegisterForm />);

    await fillAndSubmit("user_01", "user01@example.com", "pass1234!", { confirm: "pass" });
    expect(screen.getByRole("alert")).toHaveTextContent("비밀번호가 서로 달라요.");
    expect(confirmInput()).toHaveAttribute("aria-invalid", "true");

    await userEvent.click(screen.getByRole("button", { name: "회원가입" }));
    expect(screen.getByRole("alert")).toHaveTextContent("비밀번호가 서로 달라요.");
    expect(fetch).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });

  it("실패: 확인 칸이 일치한 뒤 첫 번째 비밀번호를 고치면 불일치 안내가 다시 나타난다", async () => {
    render(<RegisterForm />);
    await userEvent.type(passwordInput(), "pass1234!");
    await userEvent.type(confirmInput(), "pass1234!");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    await userEvent.type(passwordInput(), "{Backspace}");

    expect(screen.getByRole("alert")).toHaveTextContent("비밀번호가 서로 달라요.");
  });

  it("실패: 만 14세 미만 생년월일이면 안내하고 요청을 보내지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<RegisterForm />);
    const now = new Date();
    const recent = `${now.getFullYear() - 5}-01-01`;

    await fillAndSubmit("user_01", "user01@example.com", "pass1234!", { birthDate: recent });

    expect(await screen.findByRole("alert")).toHaveTextContent("만 14세 이상만 가입할 수 있어요.");
    expect(birthInput()).toHaveAttribute("aria-invalid", "true");
    expect(fetch).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });

  describe("오늘 날짜를 고정한 나이 경계", () => {
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(new Date(2026, 9, 8, 12, 0, 0)); // 2026-10-08 (로컬)
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    it("성공: 오늘 정확히 만 14세가 되면 가입 요청을 보낸다", async () => {
      mockFetchOnce(201, {});
      render(<RegisterForm />);

      await fillAndSubmit("user_01", "user01@example.com", "pass1234!", { birthDate: "2012-10-08" });

      await waitFor(() => expect(push).toHaveBeenCalledWith("/login"));
      expect(JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body)).birthDate).toBe("2012-10-08");
    });

    it("실패: 내일 만 14세가 되면 안내하고 요청을 보내지 않는다", async () => {
      mockFetchOnce(201, {});
      render(<RegisterForm />);

      await fillAndSubmit("user_01", "user01@example.com", "pass1234!", { birthDate: "2012-10-09" });

      expect(await screen.findByRole("alert")).toHaveTextContent("만 14세 이상만 가입할 수 있어요.");
      expect(fetch).not.toHaveBeenCalled();
    });

    it("실패: 미래 날짜는 '올바르지 않아요' 안내를 보이고 요청을 보내지 않는다", async () => {
      mockFetchOnce(201, {});
      render(<RegisterForm />);

      await fillAndSubmit("user_01", "user01@example.com", "pass1234!", { birthDate: "2026-10-09" });

      expect(await screen.findByRole("alert")).toHaveTextContent("생년월일이 올바르지 않아요.");
      expect(fetch).not.toHaveBeenCalled();
    });

    it("성공: 생년월일을 바꾸면 이전 생년월일 오류 안내가 사라진다", async () => {
      mockFetchOnce(201, {});
      render(<RegisterForm />);
      await fillAndSubmit("user_01", "user01@example.com", "pass1234!", { birthDate: "2012-10-09" });
      expect(await screen.findByRole("alert")).toBeInTheDocument();

      fireEvent.change(birthInput(), { target: { value: "2012-10-08" } });

      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(birthInput()).toHaveAttribute("aria-invalid", "false");
    });
  });

  it("성공: 요청이 진행되는 동안 버튼은 비활성화되고 '가입 중...'으로 바뀌며 다시 눌러도 요청이 늘지 않는다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(new Promise(() => {})));
    render(<RegisterForm />);

    await fillAndSubmit("user_01", "user01@example.com", "pass1234!");

    const button = await screen.findByRole("button", { name: "가입 중..." });
    expect(button).toBeDisabled();
    await userEvent.click(button);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("실패: 생년월일 서버 오류(400)는 role=alert로 보여주고 이동하지 않는다", async () => {
    mockFetchOnce(400, { error: "생년월일이 올바르지 않아요." });
    render(<RegisterForm />);

    await fillAndSubmit("user_01", "user01@example.com", "pass1234!");

    expect(await screen.findByRole("alert")).toHaveTextContent("생년월일이 올바르지 않아요.");
    expect(push).not.toHaveBeenCalled();
  });

  it("실패: 이미 사용 중인 아이디(400)면 에러 메시지를 보여주고 이동하지 않는다", async () => {
    mockFetchOnce(400, { error: "이미 사용 중인 아이디입니다." });
    render(<RegisterForm />);

    await fillAndSubmit("user_01", "user01@example.com", "pass1234!");

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 사용 중인 아이디입니다.");
    expect(push).not.toHaveBeenCalled();
  });

  it("실패: 비밀번호 정책 위반(400)이면 서버 메시지를 role=alert로 보여주고 이동하지 않는다", async () => {
    const message = "비밀번호는 영문, 숫자, 특수문자를 각각 1자 이상 포함해야 합니다.";
    mockFetchOnce(400, { error: message });
    render(<RegisterForm />);

    await fillAndSubmit("user_01", "user01@example.com", "password");

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(push).not.toHaveBeenCalled();
  });

  it("성공: 아이디/비밀번호 입력칸이 aria-describedby로 화면에 보이는 도움말과 연결된다", () => {
    render(<RegisterForm />);

    const username = screen.getByPlaceholderText(/^아이디/);
    const password = passwordInput();
    const usernameHelp = screen.getByText("영문 소문자, 숫자, 밑줄(_)로 4~20자");
    const passwordHelp = screen.getByText(
      "8자 이상, 영문·숫자·특수문자를 각각 1자 이상 포함해 주세요. 대문자·소문자는 구분하지 않아요."
    );

    expect(username).toHaveAttribute("aria-describedby", "register-username-help");
    expect(usernameHelp).toHaveAttribute("id", "register-username-help");
    expect(username).toHaveAccessibleDescription(usernameHelp.textContent!);
    expect(password).toHaveAttribute("aria-describedby", "register-password-help");
    expect(passwordHelp).toHaveAttribute("id", "register-password-help");
    expect(password).toHaveAccessibleDescription(passwordHelp.textContent!);
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
