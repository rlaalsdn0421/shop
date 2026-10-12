import { vi } from "vitest";

// Obviously fake values; the real ones are set in Vercel.
export const FAKE_PAYMENT_ENV = {
  NEXT_PUBLIC_PORTONE_STORE_ID: "store-test-0000",
  NEXT_PUBLIC_PORTONE_CHANNEL_KEY_CARD: "channel-card-test",
  NEXT_PUBLIC_PORTONE_CHANNEL_KEY_KAKAOPAY: "channel-kakaopay-test",
  NEXT_PUBLIC_PORTONE_CHANNEL_KEY_NAVERPAY: "channel-naverpay-test",
  NEXT_PUBLIC_PORTONE_CHANNEL_KEY_TOSSPAY: "channel-tosspay-test",
} as const;

export function stubPaymentEnv(values: Record<string, string>) {
  for (const [k, v] of Object.entries(values)) vi.stubEnv(k, v);
}

// Make sure a developer's local .env does not leak into the "not set" case.
export function clearPaymentEnv() {
  stubPaymentEnv(Object.fromEntries(Object.keys(FAKE_PAYMENT_ENV).map((k) => [k, ""])));
}
