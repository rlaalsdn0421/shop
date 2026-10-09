import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Header } from "./Header";
import { AuthProvider } from "@/auth/AuthContext";
import { CartProvider } from "@/cart/CartContext";
import { CATEGORIES } from "@/lib/categories";

const push = vi.fn();
let pathname = "/";
let query = "";
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => pathname,
  useSearchParams: () => new URLSearchParams(query),
}));

function renderHeader() {
  return render(
    <AuthProvider>
      <CartProvider>
        <Header />
      </CartProvider>
    </AuthProvider>
  );
}

function saveSession(role: "USER" | "ADMIN") {
  localStorage.setItem(
    "shop-auth",
    JSON.stringify({ token: "t", username: "user01", role, expiresAt: Date.now() + 60_000 })
  );
}

beforeEach(() => {
  localStorage.clear();
  push.mockClear();
  pathname = "/";
  query = "";
});

describe("Header", () => {
  it("성공: 중앙 로고 쇼핑몰이 홈으로 연결된다", () => {
    renderHeader();
    expect(screen.getByRole("link", { name: "쇼핑몰" })).toHaveAttribute("href", "/");
  });

  it("성공: 메뉴에 베스트/전체상품/신상품과 모든 카테고리가 올바른 href로 있다", () => {
    renderHeader();
    const menu = screen.getByRole("navigation", { name: "주요 메뉴" });
    expect(within(menu).getByRole("link", { name: "베스트" })).toHaveAttribute("href", "/#best");
    expect(within(menu).getByRole("link", { name: "전체상품" })).toHaveAttribute("href", "/#all");
    expect(within(menu).getByRole("link", { name: "신상품" })).toHaveAttribute("href", "/#new");
    for (const name of CATEGORIES) {
      expect(within(menu).getByRole("link", { name })).toHaveAttribute(
        "href",
        `/?category=${encodeURIComponent(name)}`
      );
    }
  });

  it("성공: 현재 카테고리 항목에 aria-current가 붙는다", () => {
    query = "category=" + encodeURIComponent("상의");
    renderHeader();
    expect(screen.getByRole("link", { name: "상의" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "바지" })).not.toHaveAttribute("aria-current");
  });

  it("실패: 홈이 아닌 페이지에서는 활성 메뉴가 없다", () => {
    pathname = "/cart";
    query = "category=" + encodeURIComponent("상의");
    renderHeader();
    expect(screen.getByRole("link", { name: "상의" })).not.toHaveAttribute("aria-current");
  });

  it("성공: 로그아웃 상태면 로그인/회원가입/장바구니 링크를 보여준다", () => {
    renderHeader();
    expect(screen.getByRole("link", { name: "로그인" })).toHaveAttribute("href", "/login");
    expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute("href", "/register");
    expect(screen.getByRole("link", { name: "장바구니 (0)" })).toHaveAttribute("href", "/cart");
    expect(screen.queryByRole("button", { name: "로그아웃" })).not.toBeInTheDocument();
  });

  it("성공: 로그인 상태면 이름과 로그아웃을 보여주고, 로그아웃하면 홈으로 간다", async () => {
    saveSession("USER");
    renderHeader();
    expect(await screen.findByText("user01")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "로그인" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "상품 관리" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "로그아웃" }));
    expect(push).toHaveBeenCalledWith("/");
    expect(await screen.findByRole("link", { name: "로그인" })).toBeInTheDocument();
  });

  it("성공: 관리자에게는 상품 관리 링크가 보인다", async () => {
    saveSession("ADMIN");
    renderHeader();
    expect(await screen.findByRole("link", { name: "상품 관리" })).toHaveAttribute(
      "href",
      "/admin/products"
    );
  });
});

describe("Header 더보기", () => {
  it("실패: 공간이 충분하면 더보기 버튼이 없다", () => {
    renderHeader();
    expect(screen.queryByRole("button", { name: /더보기/ })).not.toBeInTheDocument();
  });

  describe("메뉴가 한 줄에 다 안 들어갈 때", () => {
    // jsdom has no layout, so fake the item widths and row width: 14 items of 60px do not fit in 500px.
    beforeEach(() => {
      vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(60);
      vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(500);
    });
    afterEach(() => vi.restoreAllMocks());

    it("성공: 넘치는 항목은 더보기로 가고, 누르면 열리고 Escape로 닫힌다", async () => {
      renderHeader();
      const more = screen.getByRole("button", { name: /더보기/ });
      expect(more).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByRole("link", { name: "스포츠/레저" })).not.toBeInTheDocument();

      await userEvent.click(more);
      expect(more).toHaveAttribute("aria-expanded", "true");
      expect(screen.getByRole("link", { name: "스포츠/레저" })).toBeInTheDocument();

      await userEvent.keyboard("{Escape}");
      expect(more).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByRole("link", { name: "스포츠/레저" })).not.toBeInTheDocument();
      expect(more).toHaveFocus();
    });

    it("성공: 바깥을 누르면 닫힌다", async () => {
      renderHeader();
      await userEvent.click(screen.getByRole("button", { name: /더보기/ }));
      expect(screen.getByRole("link", { name: "스포츠/레저" })).toBeInTheDocument();
      await userEvent.click(document.body);
      expect(screen.queryByRole("link", { name: "스포츠/레저" })).not.toBeInTheDocument();
    });

    it("성공: 더보기 안의 항목이 현재 카테고리면 거기서 활성 표시된다", async () => {
      query = "category=" + encodeURIComponent("스포츠/레저");
      renderHeader();
      await userEvent.click(screen.getByRole("button", { name: /더보기/ }));
      expect(screen.getByRole("link", { name: "스포츠/레저" })).toHaveAttribute(
        "aria-current",
        "page"
      );
    });
  });
});
