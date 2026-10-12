import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import TermsPage from "./page";
import { FAKE_BIZ_ENV, clearBusinessEnv, stubEnv } from "@/test-utils/businessEnv";

beforeEach(() => clearBusinessEnv());
afterEach(() => vi.unstubAllEnvs());

describe("이용약관 페이지", () => {
  it("성공: 제목, 시행일, 목차, 핵심 조항을 보여준다", () => {
    render(<TermsPage />);

    expect(screen.getByRole("heading", { level: 1, name: "이용약관" })).toBeInTheDocument();
    expect(screen.getByText("시행일 2026년 10월 12일")).toBeInTheDocument();
    const toc = within(screen.getByRole("navigation", { name: "목차" }));
    expect(toc.getByRole("link", { name: "회원가입" })).toHaveAttribute("href", "#signup");
    for (const title of ["목적", "회원 탈퇴", "주문과 결제", "배송", "교환과 환불", "리뷰 작성 규칙", "금지행위", "책임의 제한", "분쟁 해결과 준거법"]) {
      expect(screen.getByRole("heading", { level: 2, name: new RegExp(`\\d+\\. ${title}$`) })).toBeInTheDocument();
    }
    expect(screen.getByText(/만 14세 이상만 할 수 있으며/)).toBeInTheDocument();
    expect(screen.getByText(/대한민국 법을 적용/)).toBeInTheDocument();
    expect(screen.getByText(/배송비는 3,500원이며, 주문 금액이 50,000원 이상이면 무료/)).toBeInTheDocument();
  });

  it("성공: 결제수단, 20분 만료, 카드번호 미저장을 알린다", () => {
    render(<TermsPage />);

    expect(screen.getByText(/카드와 간편결제\(카카오페이, 네이버페이, 토스페이\)/)).toBeInTheDocument();
    expect(screen.getByText(/카드번호를 저장하지 않습니다/)).toBeInTheDocument();
    expect(screen.getByText(/20분 안에 결제하지 않으면 주문이 자동으로 취소/)).toBeInTheDocument();
    expect(screen.queryByText(/온라인 결제 기능이 없습니다/)).not.toBeInTheDocument();
    expect(screen.queryByText(/서비스 내 안내에 따릅니다/)).not.toBeInTheDocument();
  });

  it("성공: 개인정보 처리방침, 교환·환불 안내, 내 정보로 가는 링크가 있다", () => {
    render(<TermsPage />);

    expect(screen.getAllByRole("link", { name: "개인정보 처리방침" })[0]).toHaveAttribute("href", "/privacy");
    expect(screen.getAllByRole("link", { name: "교환·환불·배송 안내" })[0]).toHaveAttribute("href", "/refund");
    expect(screen.getByRole("link", { name: "내 정보" })).toHaveAttribute("href", "/account");
  });

  it("실패: 사업자 정보가 설정되지 않으면 사업자 정보 항목이 없다", () => {
    render(<TermsPage />);

    expect(screen.queryByRole("heading", { name: /사업자 정보/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/사업자등록번호/)).not.toBeInTheDocument();
  });

  it("성공: 사업자 정보가 모두 설정되면 사업자 정보 항목을 보여준다", () => {
    stubEnv(FAKE_BIZ_ENV);
    render(<TermsPage />);

    expect(screen.getByRole("heading", { name: /사업자 정보/ })).toBeInTheDocument();
    expect(screen.getByText("상호: 테스트상점")).toBeInTheDocument();
    expect(screen.getByText("통신판매업 신고번호: 0000-테스트-0000")).toBeInTheDocument();
  });
});
