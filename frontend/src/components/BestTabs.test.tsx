import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ReactElement } from "react";
import { BestTabs } from "./BestTabs";

describe("BestTabs", () => {
  it("성공: 실시간/주간/월간 링크가 ?best=...#best 이다", () => {
    render(<BestTabs selected="realtime" />);
    expect(screen.getByRole("link", { name: /실시간/ })).toHaveAttribute("href", "/?best=realtime#best");
    expect(screen.getByRole("link", { name: "주간" })).toHaveAttribute("href", "/?best=weekly#best");
    expect(screen.getByRole("link", { name: "월간" })).toHaveAttribute("href", "/?best=monthly#best");
  });

  it("성공: 선택된 탭만 체크 표시와 aria-current를 가진다", () => {
    render(<BestTabs selected="weekly" />);
    const selected = screen.getByRole("link", { name: /주간/ });
    expect(selected).toHaveAttribute("aria-current", "true");
    expect(selected).toHaveTextContent("✓");
    for (const name of ["실시간", "월간"]) {
      const other = screen.getByRole("link", { name });
      expect(other).not.toHaveAttribute("aria-current");
      expect(other).not.toHaveTextContent("✓");
    }
  });

  it("성공: sort를 넘기면 베스트 탭 링크가 정렬 선택을 유지한다", () => {
    render(<BestTabs selected="realtime" sort="price_asc" />);
    expect(screen.getByRole("link", { name: "주간" })).toHaveAttribute(
      "href",
      "/?sort=price_asc&best=weekly#best"
    );
  });
});

describe("BestTabs 기본 모양 (variant 없음)", () => {
  it("실패: 기본 모양은 예전 그대로다 (aria-current=true, text-neutral-400, mt-4 없음)", () => {
    render(<BestTabs selected="weekly" />);
    const selected = screen.getByRole("link", { name: /주간/ });
    expect(selected).toHaveAttribute("aria-current", "true");
    expect(selected).toHaveClass("font-bold", "text-neutral-900");
    const other = screen.getByRole("link", { name: "월간" });
    expect(other).toHaveClass("text-neutral-400");
    expect(other).not.toHaveClass("text-neutral-500");
    expect(screen.getByRole("navigation", { name: "베스트 기간" })).not.toHaveClass("mt-4");
  });
});

describe("BestTabs variant=listing", () => {
  it("성공: nav 는 mb-8 mt-4, 목록은 줄바꿈되는 flex 이고 li 마다 구분선 클래스가 있다", () => {
    render(<BestTabs variant="listing" selected="realtime" />);
    const nav = screen.getByRole("navigation", { name: "베스트 기간" });
    expect(nav).toHaveClass("mb-8", "mt-4");
    const ul = nav.querySelector("ul") as HTMLElement;
    expect(ul).toHaveClass("flex", "flex-wrap", "gap-y-2", "text-sm");
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(3);
    for (const li of items) {
      expect(li).toHaveClass("border-l", "border-neutral-300", "px-3", "first:border-l-0", "first:pl-0");
    }
  });

  it("성공: 선택한 링크만 aria-current=page, 굵게+진하게, 맨 앞 숨겨진 ✓, 접근 이름은 라벨뿐이다", () => {
    render(<BestTabs variant="listing" selected="weekly" />);
    const selected = screen.getByRole("link", { name: "주간" });
    expect(selected).toHaveAttribute("aria-current", "page");
    expect(selected).toHaveClass("font-bold", "text-neutral-900");
    expect(selected.textContent).toBe("✓ 주간");
    const mark = selected.firstElementChild as HTMLElement;
    expect(mark.tagName).toBe("SPAN");
    expect(mark).toHaveAttribute("aria-hidden", "true");
    expect(selected.firstChild).toBe(mark);
    expect(screen.getAllByRole("link").filter((l) => l.hasAttribute("aria-current"))).toHaveLength(1);
  });

  it("실패: 선택하지 않은 링크는 text-neutral-500 hover:text-neutral-800 이고 aria-current 와 ✓ 가 없다", () => {
    render(<BestTabs variant="listing" selected="weekly" />);
    for (const name of ["실시간", "월간"]) {
      const other = screen.getByRole("link", { name });
      expect(other).toHaveClass("text-neutral-500", "hover:text-neutral-800");
      expect(other).not.toHaveClass("font-bold", "text-neutral-400");
      expect(other).not.toHaveAttribute("aria-current");
      expect(other.textContent).toBe(name);
    }
    expect(document.body.textContent?.match(/✓/g)).toHaveLength(1);
  });

  it("성공: 링크는 scroll={false} 로 만들어져 클릭해도 맨 위로 튀지 않는다", () => {
    // next/link does not put scroll on the DOM node, so check the element tree.
    type Li = ReactElement<{ children: ReactElement<{ scroll: boolean }> }>;
    const nav = BestTabs({ variant: "listing", selected: "realtime" }) as ReactElement<{
      children: ReactElement<{ children: Li[] }>;
    }>;
    const lis = nav.props.children.props.children;
    expect(lis).toHaveLength(3);
    for (const li of lis) expect(li.props.children.props.scroll).toBe(false);
  });

  it("성공: basePath 를 주면 링크가 /best?best=<값> 이고 # 앵커가 없다 (sort 가 있으면 함께 담는다)", () => {
    const { unmount } = render(<BestTabs variant="listing" selected="realtime" basePath="/best" />);
    expect(screen.getByRole("link", { name: "실시간" })).toHaveAttribute("href", "/best?best=realtime");
    expect(screen.getByRole("link", { name: "주간" })).toHaveAttribute("href", "/best?best=weekly");
    expect(screen.getByRole("link", { name: "월간" })).toHaveAttribute("href", "/best?best=monthly");
    unmount();

    render(<BestTabs variant="listing" selected="realtime" basePath="/best" sort="price_asc" />);
    expect(screen.getByRole("link", { name: "주간" })).toHaveAttribute("href", "/best?sort=price_asc&best=weekly");
  });

  it("실패: basePath 가 없으면 예전 /?best=...#best 링크 그대로다 (listing 도 기본도)", () => {
    for (const variant of ["listing" as const, undefined]) {
      const { unmount } = render(<BestTabs variant={variant} selected="realtime" />);
      expect(screen.getByRole("link", { name: "주간" })).toHaveAttribute("href", "/?best=weekly#best");
      unmount();
    }
  });

  it("성공: 기본 모양에 basePath 만 줘도 선택 표시는 aria-current=true 그대로이고 링크만 바뀐다", () => {
    render(<BestTabs selected="monthly" basePath="/best" />);
    expect(screen.getByRole("link", { name: /월간/ })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("link", { name: "주간" })).toHaveAttribute("href", "/best?best=weekly");
  });
});
