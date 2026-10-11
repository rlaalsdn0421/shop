import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MobileTabBar } from "./MobileTabBar";
import { AuthProvider } from "@/auth/AuthContext";
import { CartProvider } from "@/cart/CartContext";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/",
}));

function renderBar() {
  return render(
    <AuthProvider>
      <CartProvider>
        <MobileTabBar />
      </CartProvider>
    </AuthProvider>
  );
}

beforeEach(() => localStorage.clear());

describe("MobileTabBar 주문내역", () => {
  it("성공: 로그인 상태면 주문내역 탭이 /orders로 연결된다", async () => {
    localStorage.setItem(
      "shop-auth",
      JSON.stringify({ token: "t", username: "user01", role: "USER", expiresAt: Date.now() + 60_000 })
    );
    renderBar();
    expect(await screen.findByRole("link", { name: /주문내역/ })).toHaveAttribute("href", "/orders");
  });

  it("실패: 비로그인 상태면 주문내역 탭이 없다", () => {
    renderBar();
    expect(screen.getByRole("link", { name: /로그인/ })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /주문내역/ })).not.toBeInTheDocument();
  });
});
