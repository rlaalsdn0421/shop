import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AccountPage from "./page";

const mocks = vi.hoisted(() => ({
  logout: vi.fn(),
  clear: vi.fn(),
  replace: vi.fn(),
  auth: { session: { token: "fake-token", role: "USER", username: "buyer" } as unknown, ready: true },
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => mocks.auth,
  useAuth: () => ({ logout: mocks.logout }),
}));
vi.mock("@/cart/CartContext", () => ({ useCart: () => ({ clear: mocks.clear }) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: mocks.replace }) }));

const reply = (status: number, body: unknown = {}) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

async function fill(password = "pw-1234", confirm = true) {
  await userEvent.type(screen.getByLabelText("비밀번호"), password);
  if (confirm) await userEvent.click(screen.getByRole("checkbox", { name: "위 내용을 확인했어요" }));
}

const submit = () => screen.getByRole("button", { name: /회원 탈퇴|처리 중/ });

beforeEach(() => {
  vi.unstubAllGlobals();
  Object.values(mocks).forEach((m) => typeof m === "function" && m.mockClear());
  mocks.auth = { session: { token: "fake-token", role: "USER", username: "buyer" }, ready: true };
});

describe("내 정보 페이지", () => {
  it("성공: 아이디, 주문내역 링크, 탈퇴 안내와 비밀번호 입력을 보여준다", () => {
    render(<AccountPage />);
    expect(screen.getByRole("heading", { level: 1, name: "내 정보" })).toBeInTheDocument();
    expect(screen.getByText("buyer")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "주문내역" })).toHaveAttribute("href", "/orders");
    expect(screen.getByText(/되돌릴 수\s+없어요/)).toBeInTheDocument();
    expect(screen.getByLabelText("비밀번호")).toHaveAttribute("type", "password");
    expect(screen.getByLabelText("비밀번호")).toHaveAttribute("autocomplete", "current-password");
  });

  it("실패: 확인 체크 전에는 탈퇴 버튼이 비활성이고 요청하지 않는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    render(<AccountPage />);
    await fill("pw-1234", false);
    expect(submit()).toBeDisabled();
    expect(submit()).toHaveAccessibleDescription("'위 내용을 확인했어요'에 체크하면 탈퇴할 수 있어요.");
    await userEvent.click(submit());
    expect(fetchMock).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("checkbox"));
    expect(submit()).toBeEnabled();
    expect(submit()).not.toHaveAccessibleDescription();
  });

  it("성공: 탈퇴하면 토큰과 비밀번호를 보내고 로그아웃, 장바구니 비움, 홈 이동을 한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(204)));
    render(<AccountPage />);
    await fill();
    await userEvent.click(submit());
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith("/?withdrawn=1"));
    expect(screen.getByRole("status")).toHaveTextContent("탈퇴 처리 중…");
    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.clear).toHaveBeenCalledTimes(1);
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toMatch(/\/api\/users\/me$/);
    expect(init?.method).toBe("DELETE");
    expect(init?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    expect(JSON.parse(String(init?.body))).toEqual({ password: "pw-1234" });
  });

  it("성공: 401이라도 code가 AUTH_REQUIRED면 비밀번호 오류가 아니라 만료로 보고 로그인으로 보낸다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(401, { error: "인증이 필요합니다.", code: "AUTH_REQUIRED" })));
    render(<AccountPage />);
    await fill();
    await userEvent.click(submit());
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.replace).not.toHaveBeenCalledWith("/?withdrawn=1");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText(/비밀번호가 맞지 않아요/)).not.toBeInTheDocument();
  });

  it("실패: 401이면 비밀번호 오류를 alert로 보이고 입력값을 유지하며 로그아웃하지 않는다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(401, { error: "x" })));
    render(<AccountPage />);
    await fill("wrong-pw");
    await userEvent.click(submit());
    expect(await screen.findByRole("alert")).toHaveTextContent("비밀번호가 맞지 않아요.");
    expect(screen.getByLabelText("비밀번호")).toHaveValue("wrong-pw");
    expect(screen.getByLabelText("비밀번호")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText("비밀번호")).toHaveFocus();
    expect(screen.getByRole("checkbox")).toBeChecked();
    expect(submit()).toBeEnabled();
    expect(mocks.logout).not.toHaveBeenCalled();
    expect(mocks.clear).not.toHaveBeenCalled();
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it.each([
    [429, "시도가 너무 많아요"],
    [403, "탈퇴를 할 수 없어요"],
    [500, "회원 탈퇴에 실패했어요"],
  ])("실패: %i 응답은 각각의 한국어 안내를 보이고 입력값을 유지한다", async (status, text) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(status, { error: "x" })));
    render(<AccountPage />);
    await fill();
    await userEvent.click(submit());
    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(screen.getByLabelText("비밀번호")).toHaveValue("pw-1234");
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("실패: 네트워크 오류도 안내를 보이고 입력값을 유지한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    render(<AccountPage />);
    await fill();
    await userEvent.click(submit());
    expect(await screen.findByRole("alert")).toHaveTextContent("네트워크에 연결하지 못했어요. 처리됐는지 확인하지 못했어요. 다시 시도했는데 로그인 만료로 나오면 탈퇴가 이미 완료된 것일 수 있어요.");
    expect(screen.getByLabelText("비밀번호")).toHaveValue("pw-1234");
    expect(screen.getByLabelText("비밀번호")).not.toHaveAttribute("aria-invalid");
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("실패: 타임아웃은 네트워크 오류와 다른 문구로 처리 여부를 확인하지 못했다고 알린다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new DOMException("timed out", "TimeoutError")));
    render(<AccountPage />);
    await fill();
    await userEvent.click(submit());
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("응답이 너무 오래 걸려요. 처리됐는지 확인하지 못했어요.");
    expect(alert).not.toHaveTextContent("네트워크에 연결하지 못했어요");
    expect(screen.getByLabelText("비밀번호")).toHaveValue("pw-1234");
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("실패: 같은 틱에 submit이 두 번 와도 fetch는 1번이다 (버튼 disabled가 아니라 ref 가드)", async () => {
    let resolve!: (v: unknown) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise((r) => (resolve = r))));
    render(<AccountPage />);
    await fill();
    const form = screen.getByLabelText("비밀번호").closest("form") as HTMLFormElement;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(fetch).toHaveBeenCalledTimes(1);
    resolve(reply(204));
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledTimes(1));
  });

  it("실패: 로그인 확인 전이거나 세션이 없으면 아무것도 그리지 않는다", () => {
    mocks.auth = { session: null, ready: true };
    const { container, rerender } = render(<AccountPage />);
    expect(container).toBeEmptyDOMElement();
    mocks.auth = { session: { token: "t", role: "USER", username: "buyer" }, ready: false };
    rerender(<AccountPage />);
    expect(container).toBeEmptyDOMElement();
  });
});
