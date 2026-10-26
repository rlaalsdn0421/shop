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

function saveSession(role: "USER" | "ADMIN") {
  localStorage.setItem(
    "shop-auth",
    JSON.stringify({ token: "t", username: "user01", role, expiresAt: Date.now() + 60_000 })
  );
}

describe("MobileTabBar 내 정보", () => {
  it("성공: USER면 내 정보 탭이 /account로 연결된다", async () => {
    saveSession("USER");
    renderBar();
    expect(await screen.findByRole("link", { name: /내 정보/ })).toHaveAttribute("href", "/account");
  });

  it("실패: 관리자에게는 내 정보 탭이 없다", async () => {
    saveSession("ADMIN");
    renderBar();
    await screen.findByRole("link", { name: /주문내역/ });
    expect(screen.queryByRole("link", { name: /내 정보/ })).not.toBeInTheDocument();
  });
});

describe("MobileTabBar 주문 관리", () => {
  it("성공: 관리자에게는 주문 관리 탭이 /admin/orders로 연결된다", async () => {
    saveSession("ADMIN");
    renderBar();
    expect(await screen.findByRole("link", { name: /주문 관리/ })).toHaveAttribute("href", "/admin/orders");
  });

  it("실패: 일반 회원에게는 주문 관리 탭이 없다", async () => {
    saveSession("USER");
    renderBar();
    await screen.findByRole("link", { name: /주문내역/ });
    expect(screen.queryByRole("link", { name: /주문 관리/ })).not.toBeInTheDocument();
  });
});

describe("MobileTabBar 찜", () => {
  it("실패: 탭이 이미 가득해서 로그인해도 찜 탭은 넣지 않는다", async () => {
    saveSession("USER");
    renderBar();
    await screen.findByRole("link", { name: /주문내역/ });
    expect(document.querySelector('a[href="/wishlist"]')).toBeNull();
    expect(screen.queryByRole("link", { name: /찜/ })).not.toBeInTheDocument();
  });
});

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
