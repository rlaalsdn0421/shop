"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import Image from "next/image";
import { useCart } from "@/cart/CartContext";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export function CartView() {
  const { items, setQuantity, remove, totalAmount, clear } = useCart();
  const router = useRouter();
  const [form, setForm] = useState({ name: "", phone: "", address: "" });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleCheckout(e: React.FormEvent) {
    e.preventDefault();
    setError("");

    if (!form.name.trim() || !form.phone.trim() || !form.address.trim()) {
      setError("이름, 연락처, 주소를 모두 입력해주세요.");
      return;
    }

    setSubmitting(true);
    try {
      const res = await fetch(`${BACKEND_URL}/api/orders`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          customerName: form.name,
          customerPhone: form.phone,
          customerAddress: form.address,
          items: items.map((i) => ({ productId: i.productId, quantity: i.quantity })),
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "주문에 실패했습니다.");
        return;
      }
      clear();
      router.push(`/orders/${data.orderId}`);
    } catch {
      setError("네트워크 오류로 주문에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  if (items.length === 0) {
    return <p className="text-gray-600">장바구니가 비어있습니다.</p>;
  }

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
                className="w-7 h-7 border rounded"
                onClick={() => setQuantity(item.productId, item.quantity - 1)}
              >
                -
              </button>
              <span className="w-6 text-center">{item.quantity}</span>
              <button
                aria-label="수량 증가"
                className="w-7 h-7 border rounded"
                onClick={() => setQuantity(item.productId, item.quantity + 1)}
              >
                +
              </button>
            </div>
            <button
              className="text-sm text-gray-400 hover:text-red-600"
              onClick={() => remove(item.productId)}
            >
              삭제
            </button>
          </div>
        ))}
      </div>

      <form onSubmit={handleCheckout} className="flex flex-col gap-3">
        <p className="text-lg font-semibold">총 {totalAmount.toLocaleString()}원</p>
        <label htmlFor="checkout-name" className="sr-only">이름</label>
        <input
          id="checkout-name"
          className="border rounded px-3 py-2 text-sm"
          placeholder="이름"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
        />
        <label htmlFor="checkout-phone" className="sr-only">연락처</label>
        <input
          id="checkout-phone"
          className="border rounded px-3 py-2 text-sm"
          placeholder="연락처"
          value={form.phone}
          onChange={(e) => setForm({ ...form, phone: e.target.value })}
        />
        <label htmlFor="checkout-address" className="sr-only">배송 주소</label>
        <input
          id="checkout-address"
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
