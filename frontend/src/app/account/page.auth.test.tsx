import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AccountPage from "./page";
import { AuthProvider } from "@/auth/AuthContext";
import { CartProvider } from "@/cart/CartContext";

// Real AuthProvider/useRequireRole here: the redirect to /login after logout must not beat the redirect home.
const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));

function renderPage(role: "USER" | "ADMIN") {
  localStorage.setItem(
    "shop-auth",
    JSON.stringify({ token: "fake-token", username: "buyer", role, expiresAt: Date.now() + 60_000 })
  );
  return render(
    <AuthProvider>
      <CartProvider>
        <AccountPage />
      </CartProvider>
    </AuthProvider>
  );
}

beforeEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
  replace.mockClear();
});

describe("내 정보 페이지 (실제 인증 컨텍스트)", () => {
  it("성공: 탈퇴하면 세션과 장바구니가 지워지고 마지막 이동은 /login이 아니라 홈이다", async () => {
    localStorage.setItem("shop-cart", JSON.stringify([{ productId: "p", name: "n", price: 1, imageUrl: "x", quantity: 2 }]));
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 204, json: async () => ({}) }));
    renderPage("USER");
    await userEvent.type(await screen.findByLabelText("비밀번호"), "pw-1234");
    await userEvent.click(screen.getByRole("checkbox"));
    await userEvent.click(screen.getByRole("button", { name: "회원 탈퇴" }));
    await waitFor(() => expect(localStorage.getItem("shop-auth")).toBeNull());
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/?withdrawn=1"));
    // Let any redirect effect from the logout re-render run; /login must never be requested.
    await new Promise((r) => setTimeout(r, 50));
    expect(replace).toHaveBeenCalledTimes(1);
    expect(replace).not.toHaveBeenCalledWith("/login");
    expect(JSON.parse(localStorage.getItem("shop-cart") ?? "null")).toEqual([]);
  });

  it("성공: 세션 만료 401(AUTH_REQUIRED)이면 /login?reason=expired 한 곳으로만 이동한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({ code: "AUTH_REQUIRED" }) })
    );
    renderPage("USER");
    await userEvent.type(await screen.findByLabelText("비밀번호"), "pw-1234");
    await userEvent.click(screen.getByRole("checkbox"));
    await userEvent.click(screen.getByRole("button", { name: "회원 탈퇴" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?reason=expired"));
    await new Promise((r) => setTimeout(r, 50));
    expect(replace).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem("shop-auth")).toBeNull();
  });

  it("실패: 관리자는 화면 없이 /login으로 보내진다", async () => {
    const { container } = renderPage("ADMIN");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
    expect(container).toBeEmptyDOMElement();
  });

  it("실패: 로그인하지 않았으면 /login으로 보내진다", async () => {
    render(
      <AuthProvider>
        <CartProvider>
          <AccountPage />
        </CartProvider>
      </AuthProvider>
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByRole("heading", { name: "내 정보" })).not.toBeInTheDocument();
  });
});
