import { describe, it, expect } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { PolicyPage } from "./PolicyPage";

const sections = [
  { id: "a", title: "첫째", body: <p>첫째 내용</p> },
  { id: "b", title: "둘째", body: <p>둘째 내용</p> },
];

describe("PolicyPage", () => {
  it("성공: 제목과 시행일을 보여주고 절 제목에 번호를 붙인다", () => {
    render(<PolicyPage title="테스트 정책" sections={sections} />);

    expect(screen.getByRole("heading", { level: 1, name: "테스트 정책" })).toBeInTheDocument();
    expect(screen.getByText("시행일 2026년 10월 12일")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "1. 첫째" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "2. 둘째" })).toBeInTheDocument();
  });

  it("성공: 목차 링크의 #id가 실제 절 id와 일치하고 절은 자기 제목으로 이름이 붙는다", () => {
    render(<PolicyPage title="테스트 정책" sections={sections} />);
    const toc = within(screen.getByRole("navigation", { name: "목차" }));

    for (const s of sections) {
      expect(toc.getByRole("link", { name: s.title })).toHaveAttribute("href", `#${s.id}`);
      expect(document.getElementById(s.id)).not.toBeNull();
      expect(screen.getByRole("region", { name: new RegExp(s.title) })).toBe(document.getElementById(s.id));
    }
  });

  it("실패: 절이 없으면 목차 항목도 절도 없다", () => {
    render(<PolicyPage title="빈 정책" sections={[]} />);

    expect(within(screen.getByRole("navigation", { name: "목차" })).queryAllByRole("link")).toHaveLength(0);
    expect(screen.queryAllByRole("heading", { level: 2 })).toHaveLength(0);
  });
});
