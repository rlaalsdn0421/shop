import { describe, it, expect } from "vitest";
import { INVALID_BIRTH_DATE, UNDER_AGE, toLocalDateString, validateBirthDate } from "./birthDate";

const today = new Date(2026, 9, 8); // 2026-10-08 (로컬)

describe("validateBirthDate", () => {
  it("성공: 오늘 만 14세가 되는 날(14년 전 오늘)은 통과한다", () => {
    expect(validateBirthDate("2012-10-08", today)).toBeNull();
  });

  it("실패: 만 14세 생일 하루 전(14년 전 내일)은 만 14세 미만이다", () => {
    expect(validateBirthDate("2012-10-09", today)).toBe(UNDER_AGE);
  });

  it("성공: 14년 전 어제 태어났으면 통과한다", () => {
    expect(validateBirthDate("2012-10-07", today)).toBeNull();
  });

  it("성공: 윤일(2/29)생은 평년 3/1부터 만 14세로 본다", () => {
    expect(validateBirthDate("2008-02-29", new Date(2022, 2, 1))).toBeNull();
  });

  it("실패: 윤일(2/29)생은 평년 2/28에는 아직 만 14세 미만이다", () => {
    expect(validateBirthDate("2008-02-29", new Date(2022, 1, 28))).toBe(UNDER_AGE);
  });

  it("성공: 윤일에 오늘이 윤일이어도 계산이 깨지지 않는다", () => {
    expect(validateBirthDate("2010-02-28", new Date(2024, 1, 29))).toBeNull();
    expect(validateBirthDate("2010-03-01", new Date(2024, 1, 29))).toBe(UNDER_AGE);
  });

  it("실패: 1900-01-01 이전은 올바르지 않다 (1900-01-01은 통과)", () => {
    expect(validateBirthDate("1899-12-31", today)).toBe(INVALID_BIRTH_DATE);
    expect(validateBirthDate("1900-01-01", today)).toBeNull();
  });

  it("실패: 미래 날짜는 올바르지 않다", () => {
    expect(validateBirthDate("2026-10-09", today)).toBe(INVALID_BIRTH_DATE);
  });

  it("실패: 빈 값, 형식 오류, 존재하지 않는 날짜는 올바르지 않다", () => {
    for (const bad of ["", "abc", "2000-1-1", "2000/01/01", "2001-02-29", "2000-13-01", "2000-04-31"]) {
      expect(validateBirthDate(bad, today), bad).toBe(INVALID_BIRTH_DATE);
    }
  });
});

describe("toLocalDateString", () => {
  it("성공: 로컬 날짜를 0으로 채워 yyyy-MM-dd로 만든다", () => {
    expect(toLocalDateString(new Date(2026, 0, 5, 23, 59))).toBe("2026-01-05");
  });
});
