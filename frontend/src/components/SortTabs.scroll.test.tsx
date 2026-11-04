import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ComponentProps } from "react";
import { SortTabs } from "./SortTabs";

// next/link keeps `scroll` to itself, so a stand-in exposes it as an attribute.
vi.mock("next/link", () => ({
  default: ({ scroll, children, ...rest }: ComponentProps<"a"> & { scroll?: boolean }) => (
    <a data-scroll={String(scroll)} {...rest}>
      {children}
    </a>
  ),
}));

describe("SortTabs scroll={false}", () => {
  it("성공: 모든 정렬 링크가 페이지 맨 위로 스크롤하지 않는다", () => {
    render(<SortTabs selected="popular" />);
    const links = screen.getAllByRole("link");
    expect(links).toHaveLength(7);
    for (const link of links) expect(link).toHaveAttribute("data-scroll", "false");
  });
});
