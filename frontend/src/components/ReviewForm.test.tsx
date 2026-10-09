import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ReviewForm } from "./ReviewForm";
import { AuthProvider } from "@/auth/AuthContext";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh }),
}));

function mockFetchOnce(status: number, body: unknown) {
  const fn = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  });
  vi.stubGlobal("fetch", fn);
  return fn;
}

function saveSession() {
  localStorage.setItem(
    "shop-auth",
    JSON.stringify({
      token: "fake-jwt",
      username: "login_user01",
      role: "USER",
      expiresAt: Date.now() + 60_000,
    })
  );
}

function renderForm() {
  return render(
    <AuthProvider>
      <ReviewForm productId="p1" />
    </AuthProvider>
  );
}

async function fillAndSubmit() {
  await userEvent.click(screen.getByRole("radio", { name: "4점" }));
  await userEvent.type(screen.getByLabelText("이름"), "리뷰어");
  await userEvent.type(screen.getByLabelText("리뷰 내용"), "좋아요");
  await userEvent.click(screen.getByRole("button", { name: "리뷰 등록" }));
}

beforeEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
  refresh.mockClear();
});

describe("ReviewForm", () => {
  it("실패: 로그아웃 상태면 폼 없이 로그인 안내와 링크만 보인다", async () => {
    renderForm();
    expect(await screen.findByText(/리뷰는 로그인 후 작성할 수 있어요\./)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "로그인" })).toHaveAttribute("href", "/login");
    expect(screen.queryByRole("button", { name: "리뷰 등록" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("리뷰 내용")).not.toBeInTheDocument();
  });

  it("성공: 로그인 상태면 폼이 보이고 이름 칸에 로그인 아이디가 들어가 있지 않다", async () => {
    saveSession();
    renderForm();
    expect(await screen.findByLabelText("이름")).toHaveValue("");
    expect(screen.queryByText("login_user01")).not.toBeInTheDocument();
  });

  it("성공: 제출하면 Authorization 헤더와 기존 payload를 보내고 목록을 새로고침한다", async () => {
    saveSession();
    const fetchMock = mockFetchOnce(201, { id: "r1" });
    renderForm();
    await screen.findByLabelText("이름");
    await fillAndSubmit();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toMatch(/\/api\/products\/p1\/reviews$/);
    expect(init.method).toBe("POST");
    expect(init.headers.Authorization).toBe("Bearer fake-jwt");
    expect(JSON.parse(init.body)).toEqual({ reviewerName: "리뷰어", rating: 4, comment: "좋아요" });
    expect(refresh).toHaveBeenCalled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 이미 리뷰를 남겼다는 409 메시지를 서버 문구 그대로 보여준다", async () => {
    saveSession();
    mockFetchOnce(409, { error: "이미 이 상품에 리뷰를 남기셨어요." });
    renderForm();
    await screen.findByLabelText("이름");
    await fillAndSubmit();
    expect(await screen.findByRole("alert")).toHaveTextContent("이미 이 상품에 리뷰를 남기셨어요.");
    expect(refresh).not.toHaveBeenCalled();
  });

  it("실패: 401이면 본문이 없어도 다시 로그인하라는 메시지를 보여준다", async () => {
    saveSession();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        json: async () => {
          throw new SyntaxError("no body");
        },
      })
    );
    renderForm();
    await screen.findByLabelText("이름");
    await fillAndSubmit();
    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 필요해요");
    expect(refresh).not.toHaveBeenCalled();
  });

  it("실패: 네트워크 오류면 안내 메시지를 보여준다", async () => {
    saveSession();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    renderForm();
    await screen.findByLabelText("이름");
    await fillAndSubmit();
    expect(await screen.findByRole("alert")).toHaveTextContent("네트워크 오류");
  });
});
