import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
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
