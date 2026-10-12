import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import PrivacyPage from "./page";
import { FAKE_OFFICER_ENV, clearBusinessEnv, stubEnv } from "@/test-utils/businessEnv";

beforeEach(() => clearBusinessEnv());
afterEach(() => vi.unstubAllEnvs());

describe("개인정보 처리방침 페이지", () => {
  it("성공: 제목, 시행일, 수집 항목을 보여준다", () => {
    render(<PrivacyPage />);

    expect(screen.getByRole("heading", { level: 1, name: "개인정보 처리방침" })).toBeInTheDocument();
    expect(screen.getByText("시행일 2026년 10월 12일")).toBeInTheDocument();
    expect(screen.getByText(/아이디, 이메일, 비밀번호\(암호화하여 저장\), 생년월일/)).toBeInTheDocument();
    expect(screen.getByText("주문 시: 받는 사람의 이름, 연락처, 주소")).toBeInTheDocument();
    expect(screen.getByText(/접속 IP\(로그인·가입 등의 시도 횟수를 제한하기 위해 임시로 사용/)).toBeInTheDocument();
  });

  it("성공: 보유 기간(익명화, 5년, 5년, 3년)을 밝힌다", () => {
    render(<PrivacyPage />);

    expect(screen.getByText(/회원 탈퇴 시 서비스에서 복구할 수 없도록 익명화/)).toBeInTheDocument();
    expect(screen.getByText("계약 또는 청약철회 등에 관한 기록: 5년")).toBeInTheDocument();
    expect(screen.getByText("대금결제 및 재화 등의 공급에 관한 기록: 5년")).toBeInTheDocument();
    expect(screen.getByText("소비자의 불만 또는 분쟁처리에 관한 기록: 3년")).toBeInTheDocument();
    expect(screen.getByText(/보관기간이 지난 뒤 파기합니다/)).toBeInTheDocument();
  });

  it("성공: 국외 이전 수신자 4곳과 이전 국가를 보여주고 연락처는 쓰지 않는다", () => {
    const { container } = render(<PrivacyPage />);

    for (const name of ["Neon, Inc.", "Google LLC (Google Cloud)", "Vercel Inc.", "Upstash, Inc."]) {
      expect(screen.getByText(`이전받는 자: ${name}`)).toBeInTheDocument();
    }
    expect(screen.getAllByText(/^이전 국가: /)).toHaveLength(4);
    expect(screen.getAllByText("이전 국가: 싱가포르")).toHaveLength(2);
    expect(container.querySelector("a[href^='mailto:']")).toBeNull();
    expect(screen.queryByText(/@/)).not.toBeInTheDocument();
  });

  it("성공: 정보주체의 권리와 회원 탈퇴 링크, 브라우저 저장소 사용을 정확히 설명한다", () => {
    render(<PrivacyPage />);

    expect(screen.getByText(/열람, 정정, 삭제, 처리정지를 요구/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "내 정보" })).toHaveAttribute("href", "/account");
    expect(screen.getByText(/로그인 정보.*localStorage/)).toBeInTheDocument();
    expect(screen.getByText(/장바구니.*localStorage/)).toBeInTheDocument();
    expect(screen.getByText(/주문 요청 번호.*sessionStorage/)).toBeInTheDocument();
    expect(screen.getByText(/TLS\(HTTPS\)/)).toBeInTheDocument();
  });

  it("성공: 주문 입력 내용은 평문이 아니라 해시값으로만 sessionStorage에 보관한다고 밝힌다", () => {
    render(<PrivacyPage />);

    expect(screen.getByText(/임의 값과 주문 입력 내용의 해시값\(변환값\)을 sessionStorage에 보관/)).toBeInTheDocument();
    expect(screen.getByText(/이름·연락처·주소 자체는 저장하지 않습니다/)).toBeInTheDocument();
    expect(screen.getByText(/주문 요청 번호\(임의 값\)와 주문 입력 내용.*해시값.*서버에 주문 기록과 함께 보관/)).toBeInTheDocument();
    expect(screen.getByText(/주문 상품, 수량, 단가, 총액, 주문 시각/)).toBeInTheDocument();
  });

  it("성공: 장바구니는 로그아웃해도 남고 탈퇴 시 비워진다고 정확히 쓴다", () => {
    render(<PrivacyPage />);

    expect(screen.getByText(/로그아웃해도 이 브라우저에 남아 있고, 회원 탈퇴 시 비워집니다/)).toBeInTheDocument();
  });

  it("성공: 탈퇴 후에도 남는 정보를 명시한다", () => {
    render(<PrivacyPage />);

    expect(screen.getByText(/익명화된 계정 식별자, 가입 시각, 탈퇴 시각/)).toBeInTheDocument();
    expect(screen.getByText(/동의 일시와 동의한 약관 버전: 분쟁 대응을 위한 증빙/)).toBeInTheDocument();
    expect(screen.getByText(/익명화된 계정 식별자에 연결된 채 보관됩니다.*자동 파기 절차는 준비 중/)).toBeInTheDocument();
  });

  it("성공: 이메일은 계정 식별과 향후 연락 수단일 뿐 자동 발송은 하지 않는다고 쓴다", () => {
    render(<PrivacyPage />);

    expect(screen.getByText(/계정 식별 및 향후 연락 수단으로 보관합니다\(현재 이메일을 자동으로 보내지는 않습니다\)/)).toBeInTheDocument();
    expect(screen.queryByText(/서비스 이용 관련 안내/)).not.toBeInTheDocument();
  });

  it("성공: 외부 이미지 서버 접속 정보 전달과 챗봇 입력 처리를 안내한다", () => {
    render(<PrivacyPage />);

    expect(screen.getByText(/판매자가 등록한 외부 이미지 서버에서 방문자의 브라우저가 직접 불러올 수 있으며/)).toBeInTheDocument();
    expect(screen.getByText(/입력한 문장\(최대 200자\)\. 서버\(Google Cloud\)로 전송되어.*저장하거나 로그로 기록하지 않습니다/)).toBeInTheDocument();
  });

  it("성공: Neon의 인프라 사업자(Amazon Web Services) 재수탁을 한 줄로 밝힌다", () => {
    render(<PrivacyPage />);

    expect(screen.getAllByText(/Amazon Web Services/)).toHaveLength(1);
    expect(screen.getByText(/Amazon Web Services의 설비를 이용합니다\(재수탁\)/)).toBeInTheDocument();
  });

  it("성공: 열람·정정·처리정지는 보호책임자에게 요청하며 화면에서 직접 정정하는 기능은 없다고 밝힌다", () => {
    render(<PrivacyPage />);

    expect(screen.getByText(/개인정보 보호책임자에게 해 주세요\. 현재 화면에서 직접 정정하는 기능은 없습니다/)).toBeInTheDocument();
    expect(screen.queryByText(/연락처는 아래/)).not.toBeInTheDocument();
  });

  it("성공: 목차 링크의 #id가 실제 절 id와 모두 일치한다 (보호책임자 항목 포함)", () => {
    stubEnv(FAKE_OFFICER_ENV);
    render(<PrivacyPage />);

    const links = within(screen.getByRole("navigation", { name: "목차" })).getAllByRole("link");
    expect(links.length).toBeGreaterThan(8);
    for (const link of links) {
      const id = link.getAttribute("href")!.slice(1);
      expect(document.getElementById(id), id).not.toBeNull();
    }
    expect(screen.getByText(/연락처는 아래 "개인정보 보호책임자" 항목에 있습니다/)).toBeInTheDocument();
  });

  it("실패: 보호책임자 정보가 설정되지 않으면 해당 항목이 없다", () => {
    render(<PrivacyPage />);

    expect(screen.queryByRole("heading", { name: /개인정보 보호책임자/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/성명:/)).not.toBeInTheDocument();
  });

  it("실패: 보호책임자 이름만 설정되면 해당 항목이 없다", () => {
    stubEnv({ NEXT_PUBLIC_PRIVACY_OFFICER: "김책임" });
    render(<PrivacyPage />);

    expect(screen.queryByRole("heading", { name: /개인정보 보호책임자/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/김책임/)).not.toBeInTheDocument();
  });

  it("성공: 보호책임자 정보가 설정되면 항목을 보여준다", () => {
    stubEnv(FAKE_OFFICER_ENV);
    render(<PrivacyPage />);

    expect(screen.getByRole("heading", { name: /개인정보 보호책임자/ })).toBeInTheDocument();
    expect(screen.getByText("성명: 김책임")).toBeInTheDocument();
    expect(screen.getByText("연락처: privacy@example.com")).toBeInTheDocument();
  });
});
