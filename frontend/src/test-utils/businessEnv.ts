import { vi } from "vitest";

// Obviously fake values; the real ones are set in Vercel.
export const FAKE_BIZ_ENV = {
  NEXT_PUBLIC_BIZ_NAME: "테스트상점",
  NEXT_PUBLIC_BIZ_CEO: "홍길동",
  NEXT_PUBLIC_BIZ_REG_NO: "000-00-00000",
  NEXT_PUBLIC_BIZ_MAIL_ORDER_NO: "0000-테스트-0000",
  NEXT_PUBLIC_BIZ_ADDRESS: "테스트시 테스트구 테스트로 1",
  NEXT_PUBLIC_BIZ_PHONE: "000-0000-0000",
  NEXT_PUBLIC_BIZ_EMAIL: "biz@example.com",
} as const;

export const FAKE_OFFICER_ENV = {
  NEXT_PUBLIC_PRIVACY_OFFICER: "김책임",
  NEXT_PUBLIC_PRIVACY_OFFICER_CONTACT: "privacy@example.com",
} as const;

export function stubEnv(values: Record<string, string>) {
  for (const [k, v] of Object.entries(values)) vi.stubEnv(k, v);
}

// Make sure a developer's local .env does not leak into the "not set" case.
export function clearBusinessEnv() {
  stubEnv(Object.fromEntries([...Object.keys(FAKE_BIZ_ENV), ...Object.keys(FAKE_OFFICER_ENV)].map((k) => [k, ""])));
}
