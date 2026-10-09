export function formatWon(n: number) {
  return `${n.toLocaleString("ko-KR")}원`;
}

// Largest price the backend can store (Java int / Jackson Integer).
export const MAX_PRICE = 2147483647;

type Pricing = {
  price: number;
  originalPrice?: number | null;
  discountRate?: number | null;
};

// An older (or inconsistent) backend may omit or garble these fields, so the badge shows only
// when everything agrees: a finite rate of 1..100 AND a finite original price above the sale
// price. Otherwise there is no discount at all (never a red % without the struck price).
export function discountView({ price, originalPrice, discountRate }: Pricing) {
  const ok =
    typeof discountRate === "number" &&
    Number.isFinite(discountRate) &&
    discountRate >= 1 &&
    discountRate <= 100 &&
    typeof originalPrice === "number" &&
    Number.isFinite(originalPrice) &&
    originalPrice > price;
  return ok ? { rate: discountRate, original: originalPrice } : { rate: null, original: null };
}

export const MAX_HASHTAGS_SHOWN = 3;

// Same idea for tags: a missing or non-array value is an empty list.
export function visibleHashtags(hashtags: unknown): string[] {
  return Array.isArray(hashtags)
    ? hashtags.filter((t): t is string => typeof t === "string" && t !== "").slice(0, MAX_HASHTAGS_SHOWN)
    : [];
}
