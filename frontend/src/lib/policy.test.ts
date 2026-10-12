import { describe, it, expect } from "vitest";
import { POLICY_EFFECTIVE_DATE_LABEL, POLICY_VERSION, policyEffectiveDateLabel } from "./policy";

describe("policy", () => {
  it("성공: 정책 버전은 백엔드가 기록하는 값과 같고, 시행일 표기는 버전에서 만든다", () => {
    expect(POLICY_VERSION).toBe("2026-10-12");
    expect(POLICY_EFFECTIVE_DATE_LABEL).toBe("2026년 10월 12일");
  });

  it("성공: 월·일의 앞자리 0은 떼고 표기한다", () => {
    expect(policyEffectiveDateLabel("2027-01-05")).toBe("2027년 1월 5일");
  });

  it("실패: 형식이 틀린 버전은 NaN을 그대로 드러낸다(조용히 틀린 날짜를 만들지 않는다)", () => {
    expect(policyEffectiveDateLabel("abc")).toContain("NaN");
  });
});
