import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import RefundPage from "./page";
import { FAKE_BIZ_ENV, clearBusinessEnv, stubEnv } from "@/test-utils/businessEnv";

beforeEach(() => clearBusinessEnv());
afterEach(() => vi.unstubAllEnvs());

describe("교환·환불·배송 안내 페이지", () => {
  it("성공: 제목, 시행일, 확정된 교환·환불 기간과 비용 부담을 보여준다", () => {
    render(<RefundPage />);

    expect(screen.getByRole("heading", { level: 1, name: "교환·환불·배송 안내" })).toBeInTheDocument();
    expect(screen.getByText("시행일 2026년 10월 12일")).toBeInTheDocument();
    expect(screen.getByText(/상품을 받은 날부터 7일 이내에 교환 또는 환불/)).toBeInTheDocument();
    expect(screen.getByText(/받은 날부터 3개월 이내, 또는 그 사실을 안 날부터 30일 이내/)).toBeInTheDocument();
    expect(screen.getByText("단순 변심에 의한 교환·환불은 왕복 배송비를 구매자가 부담합니다.")).toBeInTheDocument();
    expect(screen.getByText(/판매자가 부담합니다\(무상 처리\)/)).toBeInTheDocument();
  });

  it("성공: 배송비 3,500원과 50,000원 이상 무료를 안내하고 배송 기간 숫자는 약속하지 않는다", () => {
    render(<RefundPage />);

    expect(screen.getByText("배송비는 3,500원이며, 주문 금액이 50,000원 이상이면 무료입니다.")).toBeInTheDocument();
    expect(screen.queryByText(/영업일/)).not.toBeInTheDocument();
    expect(screen.queryByText(/\d일 (이내|안)에 (배송|도착)/)).not.toBeInTheDocument();
  });

  it("성공: 법령상 청약철회가 제한되는 경우를 안내한다", () => {
    render(<RefundPage />);

    expect(screen.getByRole("heading", { name: /교환·환불이 제한되는 경우/ })).toBeInTheDocument();
    expect(screen.getByText(/복제가 가능한 상품의 포장을 훼손한 경우/)).toBeInTheDocument();
  });

  it("실패: 사업자 정보가 설정되지 않으면 신청 방법(연락처)이 없다", () => {
    render(<RefundPage />);

    expect(screen.queryByRole("heading", { name: /신청 방법/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/전화:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/이메일:/)).not.toBeInTheDocument();
  });

  it("성공: 사업자 정보 전체가 ready가 아니어도 설정된 연락처(전화, 이메일)는 각각 보여준다", () => {
    stubEnv({ NEXT_PUBLIC_BIZ_PHONE: "000-0000-0000" });
    const { unmount } = render(<RefundPage />);
    expect(screen.getByText("전화: 000-0000-0000")).toBeInTheDocument();
    expect(screen.queryByText(/이메일:/)).not.toBeInTheDocument();
    unmount();

    stubEnv({ NEXT_PUBLIC_BIZ_PHONE: "  ", NEXT_PUBLIC_BIZ_EMAIL: "biz@example.com" });
    render(<RefundPage />);
    expect(screen.getByText("이메일: biz@example.com")).toBeInTheDocument();
    expect(screen.queryByText(/전화:/)).not.toBeInTheDocument();
  });

  it("실패: 전화와 이메일이 둘 다 공백이면 신청 방법 절이 없다", () => {
    stubEnv({ NEXT_PUBLIC_BIZ_PHONE: " ", NEXT_PUBLIC_BIZ_EMAIL: " ", NEXT_PUBLIC_BIZ_CEO: "홍길동" });
    render(<RefundPage />);

    expect(screen.queryByRole("heading", { name: /신청 방법/ })).not.toBeInTheDocument();
  });

  it("성공: 사업자 정보가 모두 설정되면 전화와 이메일을 신청 방법으로 보여준다", () => {
    stubEnv(FAKE_BIZ_ENV);
    render(<RefundPage />);

    expect(screen.getByRole("heading", { name: /신청 방법/ })).toBeInTheDocument();
    expect(screen.getByText("전화: 000-0000-0000")).toBeInTheDocument();
    expect(screen.getByText("이메일: biz@example.com")).toBeInTheDocument();
  });
});
