"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { useCart } from "@/cart/CartContext";
import { useAuth, useRequireRole } from "@/auth/AuthContext";
import { PaymentMethodSelect } from "@/components/PaymentMethodSelect";
import { AuthError } from "@/lib/api";
import { type Attempt, readAttempt, saveAttempt, sha256Hex } from "@/lib/orderAttempt";
import { FREE_SHIPPING_THRESHOLD } from "@/lib/constants";
import { formatWon } from "@/lib/price";
import { isValidPhone } from "@/lib/phone";
import { shippingFeeFor } from "@/lib/shipping";
import {
  PAYMENT_NOT_READY,
  type PayMethod,
  type PayMethodId,
  type PayResult,
  confirmPayment,
  getPaymentConfig,
  payOrder,
} from "@/lib/payment";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";
const ORDER_TIMEOUT_MS = 20_000;

export function CartView() {
  const { items, setQuantity, remove, totalAmount, clear } = useCart();
  const { session, ready } = useRequireRole(["ADMIN", "SELLER", "USER"]);
  const { logout } = useAuth();
  const router = useRouter();
  const [form, setForm] = useState({ name: "", phone: "", address: "" });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  // One key per order content: kept across retryable failures (and reloads) so a retry cannot create a second order.
  const attempt = useRef<Attempt | null>(null);
  // Blocks a second submit in the same tick, before the disabled button re-renders.
  const submitLock = useRef(false);
  const [methodId, setMethodId] = useState<PayMethodId | null>(null);
  // The payment window said "paid" for this order but the server could not confirm yet: until it does, no new order
  // and no new payment window are possible from this page.
  const [unconfirmedId, setUnconfirmedId] = useState<string | null>(null);
  // An order the server refused to take payment for (e.g. PAYMENT_REVIEW): the user is pointed at its detail page.
  const [stuckOrderId, setStuckOrderId] = useState<string | null>(null);

  // Coming back through the back/forward cache restores the page as it was while the browser was leaving for the payment page.
  useEffect(() => {
    const onShow = (e: PageTransitionEvent) => {
      if (!e.persisted) return;
      submitLock.current = false;
      setSubmitting(false);
    };
    window.addEventListener("pageshow", onShow);
    return () => window.removeEventListener("pageshow", onShow);
  }, []);

  if (!ready || !session) return null;
  const locked = submitting || unconfirmedId !== null;

  const config = getPaymentConfig();
  const method = config.ready ? (config.methods.find((m) => m.id === methodId) ?? config.methods[0]) : null;

  async function handleCheckout(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setStuckOrderId(null);

    if (!session || unconfirmedId) return;
    // An order nobody can pay would only hold stock, so nothing is created without a usable payment setup.
    if (!config.ready || !method) {
      setError(PAYMENT_NOT_READY);
      return;
    }
    if (!form.name.trim() || !form.phone.trim() || !form.address.trim()) {
      setError("이름, 연락처, 주소를 모두 입력해주세요.");
      return;
    }
    if (!isValidPhone(form.phone.trim())) {
      setError("연락처는 숫자 9~15자리로 입력해 주세요.");
      return;
    }

    if (submitLock.current) return;
    submitLock.current = true;
    setSubmitting(true);
    // A key only vouches for identical content, so different content gets a new key.
    const customerName = form.name.trim();
    const customerPhone = form.phone.trim();
    const customerAddress = form.address.trim();
    const orderItems = items.map((i) => ({ productId: i.productId, quantity: i.quantity }));
    const fingerprint = JSON.stringify([
      customerName,
      customerPhone,
      customerAddress,
      orderItems.map((i) => `${i.productId}:${i.quantity}`).sort(),
    ]);
    // True while the browser is leaving for the payment page: the form must stay locked until it is gone.
    let redirected = false;
    try {
      // A reply of EXPIRED means the stored key points at a lapsed order; one retry with a fresh key creates a new one.
      for (let round = 0; round < 2; round++) {
        // Without SubtleCrypto the plain fingerprint is compared in memory only and never written to sessionStorage.
        const hash = await sha256Hex(fingerprint);
        const saved = attempt.current ?? readAttempt();
        if (saved && saved.fingerprintHash === (hash ?? fingerprint)) attempt.current = saved;
        else {
          attempt.current = { key: crypto.randomUUID(), fingerprintHash: hash ?? fingerprint };
          if (hash) saveAttempt(attempt.current);
        }
        const res = await fetch(`${BACKEND_URL}/api/orders`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${session.token}`,
            "Idempotency-Key": attempt.current.key,
          },
          body: JSON.stringify({ customerName, customerPhone, customerAddress, items: orderItems }),
          // Without a timeout a hung connection would leave the button disabled forever.
          signal: AbortSignal.timeout(ORDER_TIMEOUT_MS),
        });
        // A non-JSON body (e.g. a 502 page from a proxy) must not look like a network failure.
        const data = await res.json().catch(() => null);
        // `orderId` is the field of the response from before payment existed.
        const orderId = typeof data?.id === "string" ? data.id : typeof data?.orderId === "string" ? data.orderId : null;
        if (res.ok && orderId) {
          if (data.status === "EXPIRED") {
            attempt.current = null;
            saveAttempt(null);
            if (round === 0) continue;
            setError("주문이 만료되었습니다. 다시 시도해주세요.");
            return;
          }
          // Remember which order this key created, so only a payment of that very order may empty the cart later.
          attempt.current = { ...attempt.current, orderId };
          if (hash) saveAttempt(attempt.current);
          redirected = await continueToPayment(orderId, data, session.token, method);
          return;
        }
        if (res.status === 401 || res.status === 403) {
          // Not a definitive refusal of the order: the stored attempt stays so a retry after login reuses the key.
          setError("로그인이 만료되었어요. 다시 로그인해 주세요.");
          logout({ keepOrderAttempt: true });
          return;
        }
        const serverMessage = typeof data?.error === "string" ? data.error : null;
        if (res.status >= 500 || res.ok) {
          // The outcome is unknown, so the key stays and a retry returns the same order if it was created.
          setError(
            res.status === 503
              ? "잠시 후 다시 시도해주세요."
              : (serverMessage ?? "서버 오류로 주문에 실패했습니다. 잠시 후 다시 시도해주세요.")
          );
          return;
        }
        // The server definitively refused this request, so the next attempt gets a new key.
        attempt.current = null;
        saveAttempt(null);
        setError(serverMessage ?? "주문에 실패했습니다.");
        return;
      }
    } catch (e) {
      setError(
        (e as { name?: string })?.name === "TimeoutError"
          ? "응답이 늦어 주문 결과를 확인하지 못했습니다. 같은 내용으로 다시 눌러주세요."
          : "네트워크 오류로 주문 결과를 확인하지 못했습니다. 다시 시도해주세요."
      );
    } finally {
      if (!redirected) {
        submitLock.current = false;
        setSubmitting(false);
      }
    }
  }

  // Asks the server again about a payment the window accepted; never opens a payment window.
  async function handleConfirm() {
    if (!session || !unconfirmedId || submitLock.current) return;
    submitLock.current = true;
    setSubmitting(true);
    setError("");
    try {
      const result = await confirmPayment(unconfirmedId, session.token);
      if (result.kind === "paid") finishPaid(unconfirmedId);
      else if (result.kind === "unconfirmed") setError(result.message);
    } catch (e) {
      if (!(e instanceof AuthError)) throw e;
      setError("로그인이 만료되었어요. 다시 로그인해 주세요.");
    } finally {
      submitLock.current = false;
      setSubmitting(false);
    }
  }

  // Only the checkout that created this very order may empty the cart; any other paid order leaves it alone.
  function finishPaid(orderId: string) {
    setUnconfirmedId(null);
    if (attempt.current?.orderId === orderId) {
      attempt.current = null;
      saveAttempt(null);
      clear();
    }
    router.push(`/orders/${encodeURIComponent(orderId)}?placed=1`);
  }

  // The cart is emptied only once the server confirmed PAID, so a payment that did not finish can be retried from it.
  // Resolves true when the browser is navigating to the payment page (keep the form locked).
  async function continueToPayment(
    orderId: string,
    data: { status?: unknown; orderName?: unknown; totalAmount?: unknown },
    token: string,
    method: PayMethod
  ): Promise<boolean> {
    if (data.status !== "PAID" && data.status !== "PENDING_PAYMENT") {
      // A replay of this key would return the same dead order forever, so the stored key goes too.
      attempt.current = null;
      saveAttempt(null);
      setStuckOrderId(orderId);
      setError("이 주문은 결제를 진행할 수 없는 상태예요.");
      return false;
    }
    let result: PayResult = { kind: "paid" };
    if (data.status === "PENDING_PAYMENT") {
      // The amount and name come from the server's order, never from the cart on screen.
      if (typeof data.orderName !== "string" || typeof data.totalAmount !== "number" || !config.ready) {
        setError("서버 오류로 주문에 실패했습니다. 잠시 후 다시 시도해주세요.");
        return false;
      }
      try {
        result = await payOrder({
          orderId,
          orderName: data.orderName,
          totalAmount: data.totalAmount,
          method,
          storeId: config.storeId,
          customer: { name: form.name.trim(), phone: form.phone.trim() },
          token,
        });
      } catch (e) {
        if (!(e instanceof AuthError)) throw e;
        setError("로그인이 만료되었어요. 다시 로그인해 주세요.");
        return false;
      }
    }
    if (result.kind === "paid") finishPaid(orderId);
    else if (result.kind === "failed") setError(`${result.message} 다시 시도하거나 주문내역에서 결제할 수 있어요.`);
    else if (result.kind === "unconfirmed") {
      setUnconfirmedId(orderId);
      setError(result.message);
    }
    return result.kind === "redirected";
  }

  if (items.length === 0) {
    return <p className="text-gray-600">장바구니가 비어있습니다.</p>;
  }
  const shippingFee = shippingFeeFor(totalAmount);

  return (
    <div className="grid sm:grid-cols-3 gap-8">
      <div className="sm:col-span-2 flex flex-col gap-4">
        {items.map((item) => (
          <div key={item.productId} className="flex items-center gap-4 border-b pb-4">
            <div className="relative w-20 h-20 bg-gray-100 rounded overflow-hidden shrink-0">
              <Image src={item.imageUrl} alt={item.name} fill className="object-cover" unoptimized />
            </div>
            <div className="flex-1">
              <p className="font-medium text-sm">{item.name}</p>
              <p className="text-sm text-gray-600">{item.price.toLocaleString()}원</p>
            </div>
            <div className="flex items-center gap-2">
              <button
                aria-label="수량 감소"
                disabled={locked}
                className="w-7 h-7 border rounded"
                onClick={() => setQuantity(item.productId, item.quantity - 1)}
              >
                -
              </button>
              <span className="w-6 text-center">{item.quantity}</span>
              <button
                aria-label="수량 증가"
                disabled={locked}
                className="w-7 h-7 border rounded"
                onClick={() => setQuantity(item.productId, item.quantity + 1)}
              >
                +
              </button>
            </div>
            <button
              disabled={locked}
              className="text-sm text-gray-400 hover:text-red-600"
              onClick={() => remove(item.productId)}
            >
              삭제
            </button>
          </div>
        ))}
      </div>

      <form onSubmit={handleCheckout} className="flex flex-col gap-3">
        <dl className="flex flex-col gap-1 text-sm">
          <div className="flex justify-between">
            <dt>상품 금액</dt>
            <dd>{formatWon(totalAmount)}</dd>
          </div>
          <div className="flex justify-between">
            <dt>배송비</dt>
            <dd>{shippingFee === 0 ? "무료" : formatWon(shippingFee)}</dd>
          </div>
          <div className="flex justify-between text-lg font-semibold border-t pt-2 mt-1">
            <dt>총 주문금액</dt>
            <dd>{formatWon(totalAmount + shippingFee)}</dd>
          </div>
        </dl>
        {shippingFee > 0 && (
          <p className="text-xs text-gray-600">
            {formatWon(FREE_SHIPPING_THRESHOLD - totalAmount)} 더 담으면 배송비 무료
          </p>
        )}
        {config.ready && method ? (
          <PaymentMethodSelect methods={config.methods} value={method.id} onChange={setMethodId} disabled={locked} />
        ) : (
          <p role="status" className="text-sm text-gray-600">{PAYMENT_NOT_READY}</p>
        )}
        <label htmlFor="checkout-name" className="sr-only">이름</label>
        <input
          id="checkout-name"
          readOnly={locked}
          className="border rounded px-3 py-2 text-sm"
          placeholder="이름"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
        />
        <label htmlFor="checkout-phone" className="sr-only">연락처</label>
        <input
          id="checkout-phone"
          readOnly={locked}
          className="border rounded px-3 py-2 text-sm"
          placeholder="연락처"
          value={form.phone}
          onChange={(e) => setForm({ ...form, phone: e.target.value })}
        />
        <label htmlFor="checkout-address" className="sr-only">배송 주소</label>
        <input
          id="checkout-address"
          readOnly={locked}
          className="border rounded px-3 py-2 text-sm"
          placeholder="배송 주소"
          value={form.address}
          onChange={(e) => setForm({ ...form, address: e.target.value })}
        />
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        {stuckOrderId && (
          <Link href={`/orders/${encodeURIComponent(stuckOrderId)}`} className="text-sm underline">
            주문 상세에서 확인하기
          </Link>
        )}
        {unconfirmedId ? (
          <>
            <p role="status" className="text-sm text-gray-600">이전 결제를 확인하고 있어요</p>
            <Link href={`/orders/${encodeURIComponent(unconfirmedId)}`} className="text-sm underline">
              주문 상세에서 확인하기
            </Link>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={submitting}
              className="bg-black text-white rounded py-3 disabled:opacity-50"
            >
              {submitting ? "확인 중..." : "결제 확인하기"}
            </button>
          </>
        ) : (
          <button
            type="submit"
            disabled={submitting || !config.ready}
            className="bg-black text-white rounded py-3 disabled:opacity-50"
          >
            {submitting ? "주문 처리 중..." : "주문하기"}
          </button>
        )}
      </form>
    </div>
  );
}
