export const MIN_AGE = 14;
export const MIN_BIRTH_DATE = "1900-01-01";
export const INVALID_BIRTH_DATE = "생년월일이 올바르지 않아요.";
export const UNDER_AGE = `만 ${MIN_AGE}세 이상만 가입할 수 있어요.`;

const pad = (n: number, len = 2) => String(n).padStart(len, "0");

/** 브라우저(로컬) 기준 날짜를 yyyy-MM-dd로 만든다. toISOString은 UTC라 쓰지 않는다. */
export function toLocalDateString(d: Date): string {
  return `${pad(d.getFullYear(), 4)}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * 생년월일(yyyy-MM-dd)을 검사해 오류 메시지를 돌려준다. 통과하면 null.
 * 나이는 밀리초 나눗셈이 아니라 연/월/일 비교로 계산한다(윤일생 포함).
 */
export function validateBirthDate(value: string, today: Date = new Date()): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!m) return INVALID_BIRTH_DATE;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  // 2월 30일 같은 존재하지 않는 날짜는 Date가 넘겨 버리므로 되돌려 확인한다.
  const real = new Date(y, mo - 1, d);
  if (real.getFullYear() !== y || real.getMonth() !== mo - 1 || real.getDate() !== d) {
    return INVALID_BIRTH_DATE;
  }
  const todayStr = toLocalDateString(today);
  if (value < MIN_BIRTH_DATE || value > todayStr) return INVALID_BIRTH_DATE;
  // 오늘 기준 14년 전 같은 월/일 이하여야 만 14세 이상 (zero-padded 문자열이라 사전순 비교가 날짜순과 같다).
  const cutoff = `${pad(today.getFullYear() - MIN_AGE, 4)}-${todayStr.slice(5)}`;
  return value <= cutoff ? null : UNDER_AGE;
}
