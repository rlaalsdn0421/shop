import { describe, it, expect } from "vitest";
import { isValidPhone, phoneDigits } from "./phone";

describe("phoneDigits", () => {
  it("성공: 숫자 이외는 모두 지운다", () => {
    expect(phoneDigits("+82 (10) 0000-0000")).toBe("821000000000");
  });

  it("실패: 숫자가 없으면 빈 문자열이다", () => {
    expect(phoneDigits("asd")).toBe("");
  });
});

describe("isValidPhone", () => {
  it.each(["010-0000-0000", "01000000000", "+82 10 0000 0000", "(02) 000-0000", "123456789", "1".repeat(15)])(
    "성공: %s",
    (phone) => expect(isValidPhone(phone)).toBe(true)
  );

  it.each(["", "asd", "12345678", "1".repeat(16), "010.0000.0000", "010-0000-0000x", "01+00000000", "++01000000000"])(
    "실패: %j",
    (phone) => expect(isValidPhone(phone)).toBe(false)
  );
});
