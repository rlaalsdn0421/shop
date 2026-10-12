"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import Image from "next/image";
import { useCart } from "@/cart/CartContext";
import { useRequireRole } from "@/auth/AuthContext";
import { type Attempt, readAttempt, saveAttempt, sha256Hex } from "@/lib/orderAttempt";
import { FREE_SHIPPING_THRESHOLD } from "@/lib/constants";
import { formatWon } from "@/lib/price";
import { shippingFeeFor } from "@/lib/shipping";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";
const ORDER_TIMEOUT_MS = 20_000;

export function CartView() {
  const { items, setQuantity, remove, totalAmount, clear } = useCart();
  const { session, ready } = useRequireRole(["ADMIN", "SELLER", "USER"]);
  const router = useRouter();
  const [form, setForm] = useState({ name: "", phone: "", address: "" });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  // One key per order content: kept across retryable failures (and reloads) so a retry cannot create a second order.
  const attempt = useRef<Attempt | null>(null);
  // Blocks a second submit in the same tick, before the disabled button re-renders.
  const submitLock = useRef(false);

  if (!ready || !session) return null;

  async function handleCheckout(e: React.FormEvent) {
    e.preventDefault();
    setError("");

    if (!session) return;
    if (!form.name.trim() || !form.phone.trim() || !form.address.trim()) {
      setError("이름, 연락처, 주소를 모두 입력해주세요.");
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
    try {
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
      if (res.ok && typeof data?.orderId === "string") {
        attempt.current = null;
        saveAttempt(null);
        clear();
        router.push(`/orders/${encodeURIComponent(data.orderId)}?placed=1`);
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
    } catch (e) {
      setError(
        (e as { name?: string })?.name === "TimeoutError"
          ? "응답이 늦어 주문 결과를 확인하지 못했습니다. 같은 내용으로 다시 눌러주세요."
          : "네트워크 오류로 주문 결과를 확인하지 못했습니다. 다시 시도해주세요."
      );
    } finally {
      submitLock.current = false;
      setSubmitting(false);
    }
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
                disabled={submitting}
                className="w-7 h-7 border rounded"
                onClick={() => setQuantity(item.productId, item.quantity - 1)}
              >
                -
              </button>
              <span className="w-6 text-center">{item.quantity}</span>
              <button
                aria-label="수량 증가"
                disabled={submitting}
                className="w-7 h-7 border rounded"
                onClick={() => setQuantity(item.productId, item.quantity + 1)}
              >
                +
              </button>
            </div>
            <button
              disabled={submitting}
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
        <label htmlFor="checkout-name" className="sr-only">이름</label>
        <input
          id="checkout-name"
          readOnly={submitting}
          className="border rounded px-3 py-2 text-sm"
          placeholder="이름"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
        />
        <label htmlFor="checkout-phone" className="sr-only">연락처</label>
        <input
          id="checkout-phone"
          readOnly={submitting}
          className="border rounded px-3 py-2 text-sm"
          placeholder="연락처"
          value={form.phone}
          onChange={(e) => setForm({ ...form, phone: e.target.value })}
        />
        <label htmlFor="checkout-address" className="sr-only">배송 주소</label>
        <input
          id="checkout-address"
          readOnly={submitting}
          className="border rounded px-3 py-2 text-sm"
          placeholder="배송 주소"
          value={form.address}
          onChange={(e) => setForm({ ...form, address: e.target.value })}
        />
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="bg-black text-white rounded py-3 disabled:opacity-50"
        >
          {submitting ? "주문 처리 중..." : "주문하기"}
        </button>
      </form>
    </div>
  );
}
