import type { CartItem } from "@/cart/CartContext";
import { type ProductDetail, getProduct } from "@/lib/api";

export type PriceChange = {
  productId: string;
  optionId?: string;
  name: string;
  optionLabel?: string;
  oldPrice: number;
  newPrice: number;
};

const FETCH_TIMEOUT_MS = 5_000;

// Cart lines whose price differs from the product's current price. A line is left alone when its product
// could not be fetched or its option is gone (the server refuses those itself when the order is made).
export function findPriceChanges(items: CartItem[], details: Map<string, ProductDetail>): PriceChange[] {
  const changes: PriceChange[] = [];
  for (const i of items) {
    const detail = details.get(i.productId);
    if (!detail) continue;
    // A line without its option on an option product is refused by the server with its own message.
    if (!i.optionId && (detail.options?.length ?? 0) > 0) continue;
    const newPrice = i.optionId ? detail.options?.find((o) => o.id === i.optionId)?.price : detail.price;
    if (typeof newPrice !== "number" || newPrice === i.price) continue;
    changes.push({
      productId: i.productId,
      ...(i.optionId ? { optionId: i.optionId } : {}),
      name: i.name,
      ...(i.optionLabel ? { optionLabel: i.optionLabel } : {}),
      oldPrice: i.price,
      newPrice,
    });
  }
  return changes;
}

// One request per distinct product; a failed, missing (404) or slow (5s) product is simply absent from the result.
export async function fetchCurrentPrices(items: CartItem[], signal?: AbortSignal): Promise<Map<string, ProductDetail>> {
  const ids = [...new Set(items.map((i) => i.productId))];
  const settled = await Promise.allSettled(
    ids.map((id) => {
      // Aborted on timeout and when the caller gives up, which also cancels the request itself.
      const call = new AbortController();
      const abort = () => call.abort();
      const timer = setTimeout(abort, FETCH_TIMEOUT_MS);
      if (signal?.aborted) abort();
      else signal?.addEventListener("abort", abort, { once: true });
      // The race also settles a fetch that ignores the signal.
      const aborted = new Promise<never>((_, reject) =>
        call.signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true })
      );
      return Promise.race([getProduct(id, call.signal), aborted]).finally(() => {
        clearTimeout(timer);
        signal?.removeEventListener("abort", abort);
      });
    })
  );
  const details = new Map<string, ProductDetail>();
  settled.forEach((r, n) => {
    if (r.status === "fulfilled" && r.value) details.set(ids[n], r.value);
  });
  return details;
}
