import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { PromoBanner } from "./PromoBanner";

const products = [1, 2, 3].map((n) => ({
  id: `p${n}`,
  name: `상품${n}`,
  price: n * 1000,
  imageUrl: `https://example.com/${n}.jpg`,
}));

function activeDotIndex() {
  const dots = screen.getAllByRole("button", { name: /번째 배너로 이동/ });
  return dots.findIndex((d) => d.getAttribute("aria-current") === "true");
}

afterEach(() => {
  vi.useRealTimers();
});

describe("PromoBanner", () => {
  it("성공: 상품 수만큼 슬라이드를 그리고 첫 번째가 활성 상태다", () => {
    render(<PromoBanner products={products} />);
    expect(screen.getAllByRole("link", { hidden: true })).toHaveLength(3);
    expect(activeDotIndex()).toBe(0);
  });

  it("성공: 다음/이전 버튼으로 이동하고 끝에서는 순환한다", () => {
    render(<PromoBanner products={products} />);
    fireEvent.click(screen.getByRole("button", { name: "다음 배너" }));
    expect(activeDotIndex()).toBe(1);
    fireEvent.click(screen.getByRole("button", { name: "이전 배너" }));
    fireEvent.click(screen.getByRole("button", { name: "이전 배너" }));
    expect(activeDotIndex()).toBe(2);
  });

  it("성공: 4초마다 자동으로 다음 슬라이드로 넘어간다", () => {
    vi.useFakeTimers();
    render(<PromoBanner products={products} />);
    act(() => {
      vi.advanceTimersByTime(4000);
    });
    expect(activeDotIndex()).toBe(1);
  });

  it("실패: 상품이 없으면 아무것도 그리지 않는다", () => {
    const { container } = render(<PromoBanner products={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});
