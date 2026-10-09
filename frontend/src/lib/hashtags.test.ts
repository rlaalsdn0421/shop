import { describe, it, expect } from "vitest";
import { HASHTAG_ERRORS, parseHashtags } from "./hashtags";

describe("parseHashtags", () => {
  it("성공: 공백으로 나누고 앞의 #를 떼며 빈 값은 버린다", () => {
    expect(parseHashtags("  #여름   린넨 ##데일리_룩 # ")).toEqual({
      tags: ["여름", "린넨", "데일리_룩"],
      error: null,
    });
  });

  it("성공: 비어 있으면 태그 없음, 오류 없음", () => {
    expect(parseHashtags("")).toEqual({ tags: [], error: null });
    expect(parseHashtags("   ")).toEqual({ tags: [], error: null });
  });

  it("성공: 대소문자 구분 없이 중복을 없앤다 (먼저 쓴 표기를 남김)", () => {
    expect(parseHashtags("Linen #linen LINEN 셔츠").tags).toEqual(["Linen", "셔츠"]);
  });

  it("성공: 중복을 뺀 뒤 3개면 통과한다", () => {
    expect(parseHashtags("a b c a").error).toBeNull();
  });

  it("실패: 4개 이상이면 개수 오류", () => {
    expect(parseHashtags("a b c d").error).toBe(HASHTAG_ERRORS.tooMany);
  });

  it("성공: 한글 20자는 통과하고, 21자는 길이 오류", () => {
    expect(parseHashtags("가".repeat(20)).error).toBeNull();
    expect(parseHashtags("가".repeat(21)).error).toBe(HASHTAG_ERRORS.tooLong);
  });

  it("성공: 글자 수는 코드포인트로 센다 (𠮷 20개는 통과, 21개는 오류)", () => {
    expect(parseHashtags("𠮷".repeat(20))).toEqual({ tags: ["𠮷".repeat(20)], error: null });
    expect(parseHashtags("𠮷".repeat(21)).error).toBe(HASHTAG_ERRORS.tooLong);
  });

  it("성공: 숫자만 있는 태그와 밑줄은 허용한다", () => {
    expect(parseHashtags("2024 _ a_1")).toEqual({ tags: ["2024", "_", "a_1"], error: null });
  });

  it("성공: 전각 ＃도 앞 표시로 떼고 ASCII #와 같은 태그로 본다", () => {
    expect(parseHashtags("＃여름 ＃＃린넨 #여름")).toEqual({ tags: ["여름", "린넨"], error: null });
  });

  it("성공: 분해된(NFD) 한글은 NFC로 바뀌고 글자 수도 NFC 기준이다", () => {
    const nfd = "한글".normalize("NFD");
    expect(nfd).not.toBe("한글");
    expect(parseHashtags(nfd)).toEqual({ tags: ["한글"], error: null });
    // 20 syllables are 60 jamo in NFD but must still count as 20.
    expect(parseHashtags("각".repeat(20).normalize("NFD")).error).toBeNull();
    expect(parseHashtags("각".repeat(21).normalize("NFD")).error).toBe(HASHTAG_ERRORS.tooLong);
    // NFD and NFC spellings of the same word are duplicates.
    expect(parseHashtags(`${nfd} 한글`).tags).toEqual(["한글"]);
  });

  it("성공: 결합 문자(악센트, 태국어 성조)는 허용한다", () => {
    expect(parseHashtags("café")).toEqual({ tags: ["café"], error: null });
    expect(parseHashtags("ก้").error).toBeNull();
  });

  it("실패: 허용되지 않는 문자는 오류 (쉼표, 하이픈, 이모지, 중간 #, 중간 ＃, 마침표)", () => {
    for (const bad of ["a,b", "a-b", "a#b", "a＃b", "😀", "a.b"]) {
      expect(parseHashtags(bad).error, bad).toBe(HASHTAG_ERRORS.badChars);
    }
  });

  it("실패: 제로폭 문자와 NBSP는 구분자도 허용 문자도 아니라서 오류", () => {
    for (const bad of ["a​b", "a‍b", "​", "a b", " "]) {
      expect(parseHashtags(bad).error, JSON.stringify(bad)).toBe(HASHTAG_ERRORS.badChars);
    }
  });
});
