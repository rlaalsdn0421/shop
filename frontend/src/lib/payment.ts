import * as PortOne from "@portone/browser-sdk/v2";
import { AuthError, PaymentError, completeOrderPayment } from "./api";
import { phoneDigits } from "./phone";

// PortOne ids are public values set in Vercel (inlined at build time). Read at call time so tests can stub them.
const clean = (value: string | undefined) => (value ?? "").trim();

export type PayMethodId = "CARD" | "KAKAOPAY" | "NAVERPAY" | "TOSSPAY";
export type PayMethod = { id: PayMethodId; label: string; channelKey: string };

export type PaymentConfig = { ready: false } | { ready: true; storeId: string; methods: PayMethod[] };

// Only methods whose channel key is set are offered; no store id or no method at all means payment is not ready.
export function getPaymentConfig(): PaymentConfig {
  const storeId = clean(process.env.NEXT_PUBLIC_PORTONE_STORE_ID);
  const all: PayMethod[] = [
    { id: "CARD", label: "카드", channelKey: clean(process.env.NEXT_PUBLIC_PORTONE_CHANNEL_KEY_CARD) },
    { id: "KAKAOPAY", label: "카카오페이", channelKey: clean(process.env.NEXT_PUBLIC_PORTONE_CHANNEL_KEY_KAKAOPAY) },
    { id: "NAVERPAY", label: "네이버페이", channelKey: clean(process.env.NEXT_PUBLIC_PORTONE_CHANNEL_KEY_NAVERPAY) },
    { id: "TOSSPAY", label: "토스페이", channelKey: clean(process.env.NEXT_PUBLIC_PORTONE_CHANNEL_KEY_TOSSPAY) },
  ];
  const methods = all.filter((m) => m.channelKey);
  return storeId && methods.length > 0 ? { ready: true, storeId, methods } : { ready: false };
}

export const PAYMENT_NOT_READY = "결제 준비 중이에요. 준비가 끝나면 주문할 수 있어요.";

const STATUS_LABELS: Record<string, string> = {
  PENDING_PAYMENT: "결제 대기",
  PAID: "결제 완료",
  CANCELING: "취소 처리 중",
  CANCELLED: "주문 취소됨(환불 완료)",
  SHIPPED: "배송 중",
  DELIVERED: "배송 완료",
  REFUNDED: "환불 완료",
  EXPIRED: "주문 만료(결제하지 않아 취소됨)",
  PAYMENT_REVIEW: "확인 중(결제 확인 후 취소·환불을 안내해 드려요)",
};

// An unknown or missing status (older backend) shows nothing rather than a guess.
export const statusLabel = (status: string | undefined) => (status ? (STATUS_LABELS[status] ?? "") : "");

// Same shape as the backend's orderName: first product, "외 N건" for more, capped at 100 characters.
export function orderNameFor(names: string[]) {
  const name = names.length > 1 ? `${names[0]} 외 ${names.length - 1}건` : (names[0] ?? "주문");
  return name.slice(0, 100);
}

// Null when the deadline has passed or is unknown, so callers show the pay button only while it can still work.
export function remainingLabel(expiresAt: string | null | undefined, now: number) {
  const ms = expiresAt ? new Date(expiresAt).getTime() - now : NaN;
  if (!(ms > 0)) return null;
  const minutes = Math.ceil(ms / 60_000);
  return minutes <= 1 ? "1분 이내" : `${minutes}분`;
}

export type PayResult =
  | { kind: "paid" }
  // The browser is leaving for the payment page; the result arrives on the redirect URL.
  | { kind: "redirected" }
  // Cancelled or failed before any money moved: the order stays payable.
  | { kind: "failed"; message: string }
  // The window said "paid" but the server could not confirm yet: do not open the payment window again.
  | { kind: "unconfirmed"; message: string };

// Throws AuthError only; every other failure becomes a result the caller can show.
export async function confirmPayment(orderId: string, token: string): Promise<PayResult> {
  try {
    const status = await completeOrderPayment(orderId, token);
    if (status === "PAID") return { kind: "paid" };
    return { kind: "unconfirmed", message: "결제 상태를 확인하지 못했어요. 주문내역에서 다시 확인해 주세요." };
  } catch (e) {
    if (e instanceof AuthError) throw e;
    if (e instanceof PaymentError) return { kind: "unconfirmed", message: e.message };
    return { kind: "unconfirmed", message: "결제 확인이 지연되고 있어요. 잠시 뒤 다시 확인해 주세요." };
  }
}

export type PayRequest = {
  orderId: string;
  orderName: string;
  totalAmount: number;
  method: PayMethod;
  storeId: string;
  customer: { name: string; phone: string };
  token: string;
};

export async function payOrder(req: PayRequest): Promise<PayResult> {
  let response;
  const phone = phoneDigits(req.customer.phone);
  try {
    response = await PortOne.requestPayment({
      storeId: req.storeId,
      channelKey: req.method.channelKey,
      paymentId: req.orderId,
      orderName: req.orderName,
      totalAmount: req.totalAmount,
      currency: "KRW",
      payMethod: req.method.id === "CARD" ? "CARD" : "EASY_PAY",
      ...(req.method.id === "CARD"
        ? {}
        : {
            easyPay: {
              easyPayProvider: req.method.id,
              // Nice V2 requires the rendered payment method for these two providers.
              ...(req.method.id === "NAVERPAY" || req.method.id === "TOSSPAY" ? { availablePayMethods: ["CARD" as const] } : {}),
            },
          }),
      redirectUrl: `${window.location.origin}/orders/${encodeURIComponent(req.orderId)}`,
      // The SDK throws on a phone number without digits, so it is sent digits-only and left out when empty.
      customer: { fullName: req.customer.name, ...(phone ? { phoneNumber: phone } : {}) },
    });
  } catch (error) {
    // Only the thrown error, never the token or customer data.
    console.error("[payment] requestPayment threw", error);
    return { kind: "failed", message: "결제창을 열지 못했어요. 잠시 후 다시 시도해 주세요." };
  }
  if (!response) return { kind: "redirected" };
  if (response.code) return { kind: "failed", message: response.message || "결제가 취소되었거나 실패했어요." };
  return confirmPayment(req.orderId, req.token);
}
