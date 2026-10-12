import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { getBusinessContact, getBusinessInfo, getPrivacyOfficer } from "./business";
import { FAKE_BIZ_ENV, FAKE_OFFICER_ENV, clearBusinessEnv, stubEnv } from "@/test-utils/businessEnv";

beforeEach(() => clearBusinessEnv());
afterEach(() => vi.unstubAllEnvs());

describe("getBusinessInfo", () => {
  it("성공: 7개 항목이 모두 채워지면 ready=true이고 값을 그대로 돌려준다", () => {
    stubEnv(FAKE_BIZ_ENV);
    expect(getBusinessInfo()).toEqual({
      ready: true,
      name: "테스트상점",
      ceo: "홍길동",
      regNo: "000-00-00000",
      mailOrderNo: "0000-테스트-0000",
      address: "테스트시 테스트구 테스트로 1",
      phone: "000-0000-0000",
      email: "biz@example.com",
    });
  });

  it("성공: 앞뒤 공백은 잘라서 돌려준다", () => {
    stubEnv({ ...FAKE_BIZ_ENV, NEXT_PUBLIC_BIZ_NAME: "  테스트상점 " });
    const info = getBusinessInfo();
    expect(info.ready && info.name).toBe("테스트상점");
  });

  it("실패: 아무것도 설정하지 않았거나 빈 문자열이면 ready=false", () => {
    expect(getBusinessInfo()).toEqual({ ready: false });
  });

  it("실패: 설정이 하나도 없는 환경(undefined)에서도 ready=false이고 오류가 나지 않는다", () => {
    for (const k of Object.keys(FAKE_BIZ_ENV)) vi.stubEnv(k, undefined);
    expect(getBusinessInfo()).toEqual({ ready: false });
  });

  it("실패: 공백뿐인 값은 비어 있는 것으로 본다", () => {
    stubEnv({ ...FAKE_BIZ_ENV, NEXT_PUBLIC_BIZ_PHONE: "   " });
    expect(getBusinessInfo()).toEqual({ ready: false });
  });

  it.each(Object.keys(FAKE_BIZ_ENV))("실패: %s 하나만 비어 있어도 ready=false (일부만 노출하지 않는다)", (key) => {
    stubEnv({ ...FAKE_BIZ_ENV, [key]: "" });
    expect(getBusinessInfo()).toEqual({ ready: false });
  });
});

describe("getBusinessContact", () => {
  it("성공: 전화와 이메일은 다른 사업자 항목이 비어 있어도 각각 돌려준다 (공백은 잘라낸다)", () => {
    stubEnv({ NEXT_PUBLIC_BIZ_PHONE: " 000-0000-0000 ", NEXT_PUBLIC_BIZ_EMAIL: "biz@example.com" });
    expect(getBusinessContact()).toEqual({ phone: "000-0000-0000", email: "biz@example.com" });
  });

  it("실패: 설정이 없거나 공백뿐이면 빈 문자열이다", () => {
    expect(getBusinessContact()).toEqual({ phone: "", email: "" });
    stubEnv({ NEXT_PUBLIC_BIZ_PHONE: "   " });
    expect(getBusinessContact().phone).toBe("");
  });
});

describe("getPrivacyOfficer", () => {
  it("성공: 이름과 연락처가 모두 있으면 ready=true", () => {
    stubEnv(FAKE_OFFICER_ENV);
    expect(getPrivacyOfficer()).toEqual({ ready: true, name: "김책임", contact: "privacy@example.com" });
  });

  it("실패: 설정이 없으면 ready=false", () => {
    expect(getPrivacyOfficer()).toEqual({ ready: false });
  });

  it("실패: 이름만 있거나 연락처만 있거나 공백뿐이면 ready=false", () => {
    stubEnv({ NEXT_PUBLIC_PRIVACY_OFFICER: "김책임" });
    expect(getPrivacyOfficer()).toEqual({ ready: false });
    stubEnv({ NEXT_PUBLIC_PRIVACY_OFFICER: "", NEXT_PUBLIC_PRIVACY_OFFICER_CONTACT: "privacy@example.com" });
    expect(getPrivacyOfficer()).toEqual({ ready: false });
    stubEnv({ NEXT_PUBLIC_PRIVACY_OFFICER: "김책임", NEXT_PUBLIC_PRIVACY_OFFICER_CONTACT: "  " });
    expect(getPrivacyOfficer()).toEqual({ ready: false });
  });
});
