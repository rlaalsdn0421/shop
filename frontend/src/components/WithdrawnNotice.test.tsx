import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { WithdrawnNotice } from "./WithdrawnNotice";

describe("WithdrawnNotice", () => {
  it("성공: 안내를 보이고 주소에서 withdrawn만 지운다", () => {
    window.history.replaceState(null, "", "/?withdrawn=1&sort=newest#all");
    render(<WithdrawnNotice />);
    expect(screen.getByRole("status")).toHaveTextContent("탈퇴가 완료되었어요");
    expect(window.location.search).toBe("?sort=newest");
    expect(window.location.hash).toBe("#all");
  });

  it("실패: 주소에 withdrawn이 없으면 주소를 건드리지 않는다", () => {
    window.history.replaceState(null, "", "/?sort=newest");
    render(<WithdrawnNotice />);
    expect(window.location.search).toBe("?sort=newest");
  });
});
