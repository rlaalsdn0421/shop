import { describe, it, expect } from "vitest";
import { formatKoreanDate } from "./date";

describe("formatKoreanDate", () => {
  it("성공: UTC 14:30은 한국 시간으로도 같은 날(10. 11.)이다", () => {
    expect(formatKoreanDate("2026-10-11T14:30:00Z")).toBe("2026. 10. 11.");
  });

  it("성공: UTC 16:30은 한국 시간으로 다음 날(10. 12.)이다", () => {
    expect(formatKoreanDate("2026-10-11T16:30:00Z")).toBe("2026. 10. 12.");
  });

  it("성공: 연말 경계도 한국 시간 기준으로 해가 바뀐다", () => {
    expect(formatKoreanDate("2026-12-31T15:00:00Z")).toBe("2027. 1. 1.");
  });

  it("실패: 잘못된 값이나 빈 값은 빈 문자열이다", () => {
    expect(formatKoreanDate("not-a-date")).toBe("");
    expect(formatKoreanDate("")).toBe("");
  });
});
