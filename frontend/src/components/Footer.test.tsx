import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { Footer } from "./Footer";
import { FAKE_BIZ_ENV, FAKE_OFFICER_ENV, clearBusinessEnv, stubEnv } from "@/test-utils/businessEnv";

beforeEach(() => clearBusinessEnv());
afterEach(() => vi.unstubAllEnvs());

function expectPolicyLinks() {
  expect(screen.getByRole("link", { name: "이용약관" })).toHaveAttribute("href", "/terms");
  expect(screen.getByRole("link", { name: "개인정보 처리방침" })).toHaveAttribute("href", "/privacy");
  expect(screen.getByRole("link", { name: "교환·환불·배송 안내" })).toHaveAttribute("href", "/refund");
}

describe("Footer", () => {
  it("성공: 사업자 정보가 없어도 정책 링크 3개는 항상 보인다", () => {
    render(<Footer />);
    expectPolicyLinks();
    expect(screen.getAllByRole("link")).toHaveLength(3);
  });

  it("실패: 사업자 정보와 보호책임자가 설정되지 않으면 해당 영역이 없다", () => {
    const { container } = render(<Footer />);
    expect(container.querySelector("address")).toBeNull();
    expect(screen.queryByText(/사업자등록번호/)).not.toBeInTheDocument();
    expect(screen.queryByText(/통신판매업/)).not.toBeInTheDocument();
    expect(screen.queryByText(/개인정보 보호책임자/)).not.toBeInTheDocument();
  });

  it("실패: 사업자 정보가 일부만 채워지면 아무것도 보이지 않는다", () => {
    stubEnv({ ...FAKE_BIZ_ENV, NEXT_PUBLIC_BIZ_ADDRESS: "" });
    const { container } = render(<Footer />);
    expect(container.querySelector("address")).toBeNull();
    expect(screen.queryByText(/테스트상점/)).not.toBeInTheDocument();
    expectPolicyLinks();
  });

  it("성공: 사업자 정보가 모두 있으면 7개 항목을 보여준다", () => {
    stubEnv(FAKE_BIZ_ENV);
    render(<Footer />);
    const text = screen.getByText(/상호 테스트상점/).closest("address")!.textContent;
    for (const v of Object.values(FAKE_BIZ_ENV)) expect(text).toContain(v);
    expect(screen.getByText(/사업자등록번호 000-00-00000/)).toBeInTheDocument();
    expect(screen.getByText(/통신판매업 신고번호 0000-테스트-0000/)).toBeInTheDocument();
    expectPolicyLinks();
  });

  it("성공: 보호책임자는 이름과 연락처가 모두 있을 때만 보인다", () => {
    stubEnv(FAKE_OFFICER_ENV);
    render(<Footer />);
    expect(screen.getByText("개인정보 보호책임자 김책임 (privacy@example.com)")).toBeInTheDocument();
  });

  it("실패: 보호책임자 이름만 있으면 보이지 않는다", () => {
    stubEnv({ NEXT_PUBLIC_PRIVACY_OFFICER: "김책임" });
    render(<Footer />);
    expect(screen.queryByText(/김책임/)).not.toBeInTheDocument();
  });

  it("성공: 모바일 하단 탭바(고정)에 가려지지 않도록 아래 여백을 확보한다", () => {
    const { container } = render(<Footer />);
    expect(container.querySelector("footer")).toHaveClass("pb-32", "md:pb-20");
  });
});
