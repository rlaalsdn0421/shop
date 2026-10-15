import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AddToCartForm } from "./AddToCartForm";

const push = vi.hoisted(() => vi.fn());
const add = vi.hoisted(() => vi.fn());
const auth = vi.hoisted(() => ({ session: null as null | { token: string } }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
vi.mock("@/cart/CartContext", () => ({ useCart: () => ({ add }) }));
vi.mock("@/auth/AuthContext", () => ({ useAuth: () => ({ session: auth.session }) }));

function renderForm(stock = 10) {
  render(<AddToCartForm id="p1" name="린넨 셔츠" price={8000} imageUrl="https://example.com/a.jpg" stock={stock} />);
}
const item = { productId: "p1", name: "린넨 셔츠", price: 8000, imageUrl: "https://example.com/a.jpg" };

beforeEach(() => {
  push.mockClear();
  add.mockClear();
  auth.session = { token: "fake-token" };
});

describe("AddToCartForm 재고 안내", () => {
  it.each([[5, true], [1, true], [6, false], [10, false]])("성공: 재고 %i개일 때 거의 매진 안내 표시는 %s", (stock, shown) => {
    renderForm(stock);
    const note = screen.queryByText(`${stock}개 남음 · 거의 매진`);
    if (shown) expect(note).toBeInTheDocument();
    else expect(note).not.toBeInTheDocument();
  });

  it("실패: 재고가 0이면 품절 문구를 보이고 구매 버튼을 모두 막는다", async () => {
    renderForm(0);
    expect(screen.getByText("품절된 상품입니다.")).toBeInTheDocument();
    expect(screen.queryByText(/개 남음/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "수량 증가" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "구매하기" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "장바구니" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "구매하기" }));
    expect(add).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });
});

describe("AddToCartForm 수량과 합계", () => {
  it("성공: 수량을 바꾸면 총 상품금액이 가격 x 수량으로 바뀐다", async () => {
    renderForm(10);
    expect(screen.getByText("8,000원")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "수량 증가" }));
    await userEvent.click(screen.getByRole("button", { name: "수량 증가" }));
    expect(screen.getByText("24,000원")).toBeInTheDocument();
  });

  it("실패: 수량은 1 아래로 내려가지 않고 재고를 넘지 않는다", async () => {
    renderForm(2);
    const minus = screen.getByRole("button", { name: "수량 감소" });
    const plus = screen.getByRole("button", { name: "수량 증가" });
    expect(minus).toBeDisabled();
    await userEvent.click(plus);
    expect(plus).toBeDisabled();
    expect(screen.getByRole("group", { name: "수량" })).toHaveTextContent("2");
    expect(screen.getByText("16,000원")).toBeInTheDocument();
    await userEvent.click(minus);
    await userEvent.click(minus);
    expect(screen.getByText("8,000원")).toBeInTheDocument();
  });
});

describe("AddToCartForm 버튼", () => {
  it("성공: 구매하기는 고른 수량을 담고 장바구니로 이동한다", async () => {
    renderForm(10);
    await userEvent.click(screen.getByRole("button", { name: "수량 증가" }));
    await userEvent.click(screen.getByRole("button", { name: "구매하기" }));
    expect(add).toHaveBeenCalledWith(item, 2);
    expect(push).toHaveBeenCalledWith("/cart");
  });

  it("실패: 로그인하지 않았으면 구매하기는 담지 않고 로그인으로 보낸다", async () => {
    auth.session = null;
    renderForm(10);
    await userEvent.click(screen.getByRole("button", { name: "구매하기" }));
    expect(add).not.toHaveBeenCalled();
    expect(push).toHaveBeenCalledTimes(1);
    expect(push).toHaveBeenCalledWith("/login");
  });

  it("성공: 장바구니는 담고 안내 메시지를 보여주며 이동하지 않는다", async () => {
    renderForm(10);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "장바구니" }));
    expect(add).toHaveBeenCalledWith(item, 1);
    expect(screen.getByRole("status")).toHaveTextContent("장바구니에 담았습니다.");
    expect(push).not.toHaveBeenCalled();
  });

  it("실패: 로그인하지 않았으면 장바구니는 담지 않고 메시지 없이 로그인으로 보낸다", async () => {
    auth.session = null;
    renderForm(10);
    await userEvent.click(screen.getByRole("button", { name: "장바구니" }));
    expect(add).not.toHaveBeenCalled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(push).toHaveBeenCalledWith("/login");
  });

  it("실패: 찜하기는 준비 중이라 눌러도 아무 일도 없다", async () => {
    renderForm(10);
    const wish = screen.getByRole("button", { name: /찜하기/ });
    expect(wish).toBeDisabled();
    expect(wish).toHaveAttribute("aria-disabled", "true");
    expect(wish).toHaveAttribute("title", "준비 중이에요");
    expect(wish).toHaveTextContent("준비 중이에요");
    await userEvent.click(wish);
    expect(add).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });
});
