import { describe, it, expect } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { ListingHeader } from "./ListingHeader";

function crumbs() {
  const nav = screen.getByRole("navigation", { name: "현재 위치" });
  return { nav, lis: within(nav).getAllByRole("listitem", { hidden: true }) };
}

describe("ListingHeader", () => {
  it("성공: 바깥 상자는 제목과 경로를 한 줄에 놓고 굵은 검은 선으로 끝난다", () => {
    const { container } = render(<ListingHeader title="전체상품" total={3} />);
    const wrapper = container.firstElementChild as HTMLElement;
    expect(container.children).toHaveLength(1);
    expect(wrapper.tagName).toBe("DIV");
    expect(wrapper.className.split(/\s+/).sort()).toEqual(
      "flex flex-wrap items-end justify-between gap-x-4 gap-y-2 border-b-2 border-black pb-4".split(" ").sort()
    );
    expect(wrapper.children).toHaveLength(2);
    expect(wrapper.children[0].tagName).toBe("H1");
    expect(wrapper.children[1].tagName).toBe("NAV");
  });

  it("성공: 제목은 id=all-title 인 h1 하나이고 h2 는 없다", () => {
    render(<ListingHeader title="신상품" total={3} />);
    const h1 = screen.getByRole("heading", { level: 1 });
    expect(h1).toHaveAttribute("id", "all-title");
    expect(h1).toHaveTextContent(/^신상품$/);
    expect(screen.getAllByRole("heading")).toHaveLength(1);
  });

  it("성공: 경로는 ol 하나에 li 셋 (홈 링크 '/', 숨겨진 ›, '제목(총 N개)')이고 마지막은 링크가 아니다", () => {
    render(<ListingHeader title="전체상품" total={42} />);
    const { nav, lis } = crumbs();
    expect(nav.querySelectorAll("ol")).toHaveLength(1);
    expect(lis).toHaveLength(3);
    const home = within(lis[0]).getByRole("link", { name: "홈" });
    expect(home).toHaveAttribute("href", "/");
    expect(lis[1]).toHaveAttribute("aria-hidden", "true");
    expect(lis[1].textContent).toBe("›");
    expect(lis[2].textContent).toBe("전체상품(총 42개)");
    expect(within(nav).getAllByRole("link")).toHaveLength(1);
    expect(lis[2]).not.toHaveAttribute("aria-hidden");
  });

  it.each([
    [0, "베스트(총 0개)"],
    [1, "베스트(총 1개)"],
    [999, "베스트(총 999개)"],
    [1000, "베스트(총 1,000개)"],
    [1234, "베스트(총 1,234개)"],
    [1234567, "베스트(총 1,234,567개)"],
  ])("성공: total=%i 이면 마지막 항목이 '%s' 이다 (천 단위 쉼표, 0 도 표시)", (total, text) => {
    render(<ListingHeader title="베스트" total={total} />);
    expect(crumbs().lis[2].textContent).toBe(text);
  });

  it("실패: total 이 없으면 제목만 나오고 '(총' 도 'undefined' 도 없다", () => {
    const { container } = render(<ListingHeader title="전체상품" />);
    expect(crumbs().lis[2].textContent).toBe("전체상품");
    expect(container.textContent).not.toContain("(총");
    expect(container.textContent).not.toContain("undefined");
    expect(container.textContent).not.toContain("NaN");
  });

  it("실패: 제목이 바뀌면 h1 과 경로 둘 다 새 제목을 쓴다 (고정 문구가 아니다)", () => {
    render(<ListingHeader title="원피스/스커트" total={7} />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(/^원피스\/스커트$/);
    expect(crumbs().lis[2].textContent).toBe("원피스/스커트(총 7개)");
  });
});
