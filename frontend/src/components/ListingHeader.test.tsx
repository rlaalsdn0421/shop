import { describe, it, expect } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { ListingHeader } from "./ListingHeader";

function crumbs() {
  const nav = screen.getByRole("navigation", { name: "현재 위치" });
  return { nav, lis: within(nav).getAllByRole("listitem", { hidden: true }) };
}

describe("ListingHeader", () => {
  it("성공: 경로 nav 가 h1 보다 앞에 오고 오른쪽 정렬·작은 글씨이며, h1 은 가운데 정렬이다", () => {
    const { container } = render(<ListingHeader title="전체상품" />);
    const wrapper = container.firstElementChild as HTMLElement;
    expect(container.children).toHaveLength(1);
    expect(wrapper.children).toHaveLength(2);
    expect(wrapper.children[0].tagName).toBe("NAV");
    expect(wrapper.children[1].tagName).toBe("H1");
    expect(wrapper.children[0]).toHaveClass("text-right", "text-xs");
    expect(wrapper.children[1]).toHaveClass("text-center");
  });

  it("실패: 굵은 검은 선이 없다", () => {
    const { container } = render(<ListingHeader title="전체상품" />);
    expect(container.innerHTML).not.toContain("border-b-2");
    expect(container.innerHTML).not.toContain("border-black");
  });

  it("성공: 제목은 id=all-title 인 h1 하나이고 h2 는 없다", () => {
    render(<ListingHeader title="신상품" />);
    const h1 = screen.getByRole("heading", { level: 1 });
    expect(h1).toHaveAttribute("id", "all-title");
    expect(h1).toHaveTextContent(/^신상품$/);
    expect(screen.getAllByRole("heading")).toHaveLength(1);
  });

  it("성공: 경로는 ol 하나에 li 셋 (홈 링크 '/', 숨겨진 ›, 제목)이고 마지막은 링크가 아니다", () => {
    render(<ListingHeader title="전체상품" />);
    const { nav, lis } = crumbs();
    expect(nav.querySelectorAll("ol")).toHaveLength(1);
    expect(lis).toHaveLength(3);
    const home = within(lis[0]).getByRole("link", { name: "홈" });
    expect(home).toHaveAttribute("href", "/");
    expect(lis[1]).toHaveAttribute("aria-hidden", "true");
    expect(lis[1].textContent).toBe("›");
    expect(lis[2].textContent).toBe("전체상품");
    expect(within(nav).getAllByRole("link")).toHaveLength(1);
    expect(lis[2]).not.toHaveAttribute("aria-hidden");
  });

  it("실패: 총 개수는 이 머리에 나오지 않는다 (정렬 줄이 맡는다)", () => {
    const { container } = render(<ListingHeader title="전체상품" />);
    expect(container.textContent).not.toContain("총");
    expect(container.textContent).not.toContain("undefined");
  });

  it("실패: 제목이 바뀌면 h1 과 경로 둘 다 새 제목을 쓴다 (고정 문구가 아니다)", () => {
    render(<ListingHeader title="원피스/스커트" />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(/^원피스\/스커트$/);
    expect(crumbs().lis[2].textContent).toBe("원피스/스커트");
  });
});
