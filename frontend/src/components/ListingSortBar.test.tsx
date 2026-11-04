import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ListingCount, ListingSortBar } from "./ListingSortBar";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const select = () => screen.getByRole("combobox", { name: "정렬" }) as HTMLSelectElement;

beforeEach(() => {
  push.mockReset();
});

describe("ListingSortBar", () => {
  it("성공: 왼쪽에 '총 N개'(N은 굵게, 천 단위 쉼표)가 있다", () => {
    const { container } = render(<ListingSortBar total={1234} selected="popular" basePath="/products" />);
    expect(container.textContent).toContain("총 1,234개");
    const bold = container.querySelector("b") as HTMLElement;
    expect(bold.textContent).toBe("1,234");
  });

  it("성공: total 이 0 이면 '총 0개'가 보이고, 999 이하는 쉼표가 없다", () => {
    const { container, rerender } = render(<ListingSortBar total={0} selected="popular" basePath="/products" />);
    expect(container.textContent).toContain("총 0개");
    rerender(<ListingSortBar total={999} selected="popular" basePath="/products" />);
    expect(container.textContent).toContain("총 999개");
  });

  it("실패: total 이 없으면 개수 문구가 없지만 select 는 오른쪽에 남는다", () => {
    const { container } = render(<ListingSortBar selected="popular" basePath="/products" />);
    expect(container.textContent).not.toContain("총");
    expect(container.textContent).not.toContain("undefined");
    expect(container.firstElementChild).toHaveClass("justify-between");
    expect(select()).toBeInTheDocument();
  });

  it("성공: aria-label 이 '정렬'인 select 하나에 옵션 7개(할인율순 없음)가 순서대로 있다", () => {
    render(<ListingSortBar total={3} selected="popular" basePath="/products" />);
    expect(screen.getAllByRole("combobox")).toHaveLength(1);
    expect(Array.from(select().options).map((o) => o.textContent)).toEqual([
      "인기도순",
      "최신 등록순",
      "낮은 가격순",
      "높은 가격순",
      "누적 판매순",
      "리뷰 많은순",
      "평점 높은순",
    ]);
    expect(screen.queryByRole("option", { name: "할인율순" })).not.toBeInTheDocument();
  });

  it("성공: selected 가 select 의 값이다", () => {
    render(<ListingSortBar selected="rating" basePath="/products" />);
    expect(select().value).toBe("rating");
    expect(screen.getByRole("option", { name: "평점 높은순", selected: true })).toBeInTheDocument();
  });

  it("성공: 옵션을 고르면 basePath?sort=<값> 으로 스크롤 없이 이동한다", () => {
    render(<ListingSortBar total={3} selected="popular" basePath="/products" />);
    fireEvent.change(select(), { target: { value: "price_asc" } });
    expect(push).toHaveBeenCalledTimes(1);
    expect(push).toHaveBeenCalledWith("/products?sort=price_asc", { scroll: false });
  });

  it("성공: category 가 있으면 이동 주소에 category 와 sort 가 모두 들어간다", () => {
    render(<ListingSortBar total={3} selected="popular" category="상의" basePath="/" />);
    fireEvent.change(select(), { target: { value: "newest" } });
    expect(push).toHaveBeenCalledWith(`/?category=${encodeURIComponent("상의")}&sort=newest`, { scroll: false });
  });

  it("성공: best 가 있으면 이동 주소가 best 를 유지한다", () => {
    render(<ListingSortBar selected="popular" best="weekly" basePath="/best" />);
    fireEvent.change(select(), { target: { value: "sales" } });
    expect(push).toHaveBeenCalledWith("/best?best=weekly&sort=sales", { scroll: false });
  });

  it("실패: 이동하기 전에는 push 를 부르지 않는다", () => {
    render(<ListingSortBar total={3} selected="popular" basePath="/products" />);
    expect(push).not.toHaveBeenCalled();
  });
});

describe("ListingSortBar kind=best (베스트 기간 select)", () => {
  const periodSelect = () => screen.getByRole("combobox", { name: "베스트 기간" }) as HTMLSelectElement;

  it("성공: aria-label 이 '베스트 기간'이고 옵션 3개(실시간/주간/월간)이며 '정렬' select 는 없다", () => {
    render(<ListingSortBar kind="best" total={5} selected="weekly" basePath="/best" />);
    expect(screen.getAllByRole("combobox")).toHaveLength(1);
    expect(screen.queryByRole("combobox", { name: "정렬" })).not.toBeInTheDocument();
    expect(Array.from(periodSelect().options).map((o) => [o.value, o.textContent])).toEqual([
      ["realtime", "실시간"],
      ["weekly", "주간"],
      ["monthly", "월간"],
    ]);
  });

  it("성공: selected 기간이 select 의 값으로 보이고 '총 N개'가 왼쪽에 있다", () => {
    const { container } = render(<ListingSortBar kind="best" total={20} selected="monthly" basePath="/best" />);
    expect(periodSelect().value).toBe("monthly");
    expect(screen.getByRole("option", { name: "월간", selected: true })).toBeInTheDocument();
    expect(container.textContent).toContain("총 20개");
  });

  it("성공: 기간을 고르면 /best?best=<값> 으로 스크롤 없이 이동한다", () => {
    render(<ListingSortBar kind="best" total={5} selected="realtime" basePath="/best" />);
    fireEvent.change(periodSelect(), { target: { value: "weekly" } });
    expect(push).toHaveBeenCalledTimes(1);
    expect(push).toHaveBeenCalledWith("/best?best=weekly", { scroll: false });
  });

  it("실패: sort 용 category 는 이동 주소에 섞이지 않는다", () => {
    render(<ListingSortBar kind="best" selected="realtime" category="상의" basePath="/best" />);
    fireEvent.change(periodSelect(), { target: { value: "monthly" } });
    expect(push).toHaveBeenCalledWith("/best?best=monthly", { scroll: false });
  });

  it("실패: total 이 없으면 개수 문구 없이 기간 select 만 남는다", () => {
    const { container } = render(<ListingSortBar kind="best" selected="realtime" basePath="/best" />);
    expect(container.textContent).not.toContain("총");
    expect(periodSelect()).toBeInTheDocument();
  });
});

describe("ListingCount", () => {
  it("성공: total 이 있으면 '총 N개'", () => {
    const { container } = render(<ListingCount total={42} />);
    expect(container.textContent).toBe("총 42개");
  });

  it("실패: total 이 없으면 아무 글자도 없다", () => {
    const { container } = render(<ListingCount />);
    expect(container.textContent).toBe("");
  });
});
