import { describe, it, expect, beforeEach } from "vitest";
import { act, render, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { CartProvider, cartLineKey, useCart } from "./CartContext";

const wrapper = ({ children }: { children: ReactNode }) => <CartProvider>{children}</CartProvider>;
const base = { productId: "p1", name: "린넨 셔츠", price: 8000, imageUrl: "https://example.com/a.jpg" };
const white = { ...base, optionId: "o-w", optionLabel: "화이트 / M", price: 9000 };
const black = { ...base, optionId: "o-b", optionLabel: "블랙 / M", price: 9500 };
const stored = () => JSON.parse(localStorage.getItem("shop-cart")!);

function Probe() {
  useCart();
  return null;
}

beforeEach(() => localStorage.clear());

describe("장바구니 항목 식별", () => {
  it("성공: 같은 상품의 같은 옵션만 수량이 합쳐진다", () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => {
      result.current.add(white, 1);
      result.current.add(white, 2);
      result.current.add(black, 1);
    });
    expect(result.current.items.map((i) => [i.optionId, i.quantity])).toEqual([["o-w", 3], ["o-b", 1]]);
    expect(result.current.totalAmount).toBe(3 * 9000 + 9500);
  });

  it("성공: 옵션 없는 줄과 옵션 있는 줄은 섞이지 않는다", () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => {
      result.current.add(base);
      result.current.add(white);
      result.current.add(base);
    });
    expect(result.current.items.map((i) => [i.optionId ?? null, i.quantity])).toEqual([[null, 2], ["o-w", 1]]);
  });

  it("성공: 수량 변경과 삭제는 그 줄만 바꾼다", () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => {
      result.current.add(white, 2);
      result.current.add(black, 2);
      result.current.add(base, 2);
    });
    act(() => result.current.setQuantity("p1", 5, "o-b"));
    expect(result.current.items.map((i) => i.quantity)).toEqual([2, 5, 2]);
    act(() => result.current.remove("p1", "o-w"));
    expect(result.current.items.map((i) => i.optionId ?? null)).toEqual(["o-b", null]);
    act(() => result.current.setQuantity("p1", 0));
    expect(result.current.items.map((i) => i.optionId)).toEqual(["o-b"]);
  });

  it("실패: 옵션 없이 지우거나 바꾸려 해도 옵션 줄은 그대로다", () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => result.current.add(white, 2));
    act(() => {
      result.current.remove("p1");
      result.current.setQuantity("p1", 9);
    });
    expect(result.current.items).toHaveLength(1);
    expect(result.current.items[0].quantity).toBe(2);
  });

  it("성공: 줄 키는 상품과 옵션을 함께 쓴다", () => {
    expect(cartLineKey({ productId: "p1" })).toBe("p1:");
    expect(cartLineKey({ productId: "p1", optionId: "o1" })).toBe("p1:o1");
  });
});

describe("장바구니 저장", () => {
  it("성공: optionId와 optionLabel도 localStorage에 저장된다", () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => result.current.add(white, 2));
    expect(stored()).toEqual([{ ...white, quantity: 2 }]);
  });

  it("성공: 옵션 없는 항목은 예전과 같은 모양으로 저장된다", () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => result.current.add(base, 1));
    expect(stored()).toEqual([{ ...base, quantity: 1 }]);
    expect(Object.keys(stored()[0])).not.toContain("optionId");
  });

  it("성공: 옵션 필드가 없는 예전 저장 항목도 불러와 그대로 쓰고 합친다", () => {
    localStorage.setItem("shop-cart", JSON.stringify([{ ...base, quantity: 2 }]));
    const { result } = renderHook(() => useCart(), { wrapper });
    expect(result.current.items).toEqual([{ ...base, quantity: 2 }]);
    act(() => result.current.add(base, 1));
    expect(result.current.items[0].quantity).toBe(3);
    act(() => result.current.remove("p1"));
    expect(result.current.items).toEqual([]);
  });

  it("실패: 저장된 값이 깨져 있으면 빈 장바구니로 시작한다", () => {
    localStorage.setItem("shop-cart", "{not json");
    const { result } = renderHook(() => useCart(), { wrapper });
    expect(result.current.items).toEqual([]);
  });

  it("실패: Provider 밖에서 쓰면 오류를 던진다", () => {
    expect(() => render(<Probe />)).toThrow("useCart must be used within CartProvider");
  });
});
