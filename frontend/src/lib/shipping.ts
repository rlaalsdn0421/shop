import { FREE_SHIPPING_THRESHOLD, SHIPPING_FEE } from "./constants";

// Same rule as the backend: free at or above the threshold, flat fee below it.
export function shippingFeeFor(itemsAmount: number) {
  return itemsAmount >= FREE_SHIPPING_THRESHOLD ? 0 : SHIPPING_FEE;
}
