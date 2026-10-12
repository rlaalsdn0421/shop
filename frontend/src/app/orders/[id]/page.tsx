"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { useAuth, useRequireRole } from "@/auth/AuthContext";
import { getOrder, AuthError, type Order } from "@/lib/api";
import { formatWon } from "@/lib/price";

export default function OrderPage() {
  const { id } = useParams<{ id: string }>();
  const { session, ready } = useRequireRole(["ADMIN", "SELLER", "USER"]);

  if (!ready || !session) return null;
  // The key remounts the loader, so a different order or login never shows the previous state.
  // useSearchParams needs a Suspense boundary so the build can prerender this route's shell.
  return (
    <Suspense fallback={<p role="status" className="text-gray-600">불러오는 중...</p>}>
      <OrderComplete key={`${id}:${session.token}`} id={id} token={session.token} />
    </Suspense>
  );
}

function OrderComplete({ id, token }: { id: string; token: string }) {
  const { logout } = useAuth();
  // Only the redirect right after checkout carries ?placed=1; reopening an old order is just a detail view.
  const placed = useSearchParams().get("placed") === "1";
  // undefined = still loading, null = no such order (or not the caller's).
  const [order, setOrder] = useState<Order | null | undefined>(undefined);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    // Ignore a response that arrives after unmount or a retry.
    let cancelled = false;
    getOrder(id, token)
      .then((o) => {
        if (cancelled) return;
        // A body for some other order must never be shown as this one.
        if (o && String(o.id).toLowerCase() !== id.toLowerCase()) setFailed(true);
        else setOrder(o);
      })
      .catch((e) => {
        if (cancelled) return;
        // An expired or invalid token: logging out makes useRequireRole send the user to /login.
        if (e instanceof AuthError) logout();
        else setFailed(true);
      });
    return () => {
      cancelled = true;
    };
    // `logout` is a fresh function every render; including it would refetch on each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, token, attempt]);

  function retry() {
    setFailed(false);
    setOrder(undefined);
    setAttempt((a) => a + 1);
  }

  const home = (
    <Link href="/" className="text-sm underline">
      홈으로
    </Link>
  );

  if (failed) {
    return (
      <div className="flex flex-col items-start gap-3">
        <p role="alert" className="text-sm text-red-600">
          주문은 접수되었을 수 있어요. 잠시 후 다시 확인해 주세요.
        </p>
        <button onClick={retry} className="border rounded px-3 py-2 text-sm">
          다시 불러오기
        </button>
        {home}
      </div>
    );
  }
  if (order === undefined) return <p role="status" className="text-gray-600">불러오는 중...</p>;
  if (order === null) {
    return (
      <div className="flex flex-col items-start gap-3">
        <p className="text-gray-600">주문을 찾을 수 없습니다.</p>
        {home}
      </div>
    );
  }

  // An older backend response has no shippingFee; anything but a positive number counts as free.
  const shippingFee = typeof order.shippingFee === "number" && order.shippingFee > 0 ? order.shippingFee : 0;

  return (
    <div className="max-w-lg mx-auto flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">{placed ? "주문이 접수되었습니다" : "주문 상세"}</h1>
        <p className="text-gray-600 text-sm mt-1">주문번호 {order.id}</p>
      </div>

      <div className="flex flex-col gap-2 border-t pt-4">
        {order.items.map((item) => (
          <div key={item.id} className="flex justify-between text-sm">
            <span>
              {item.productName} x {item.quantity}
            </span>
            <span>{(item.price * item.quantity).toLocaleString()}원</span>
          </div>
        ))}
      </div>

      <dl className="flex flex-col gap-1 text-sm border-t pt-4">
        <div className="flex justify-between">
          <dt>상품 금액</dt>
          <dd>{formatWon(order.totalAmount - shippingFee)}</dd>
        </div>
        <div className="flex justify-between">
          <dt>배송비</dt>
          <dd>{shippingFee === 0 ? "무료" : formatWon(shippingFee)}</dd>
        </div>
        <div className="flex justify-between font-semibold text-base border-t pt-2 mt-1">
          <dt>총 주문금액</dt>
          <dd>{formatWon(order.totalAmount)}</dd>
        </div>
      </dl>

      <div className="border-t pt-4 text-sm text-gray-600 flex flex-col gap-1">
        <p>받는 사람: {order.customerName}</p>
        <p>연락처: {order.customerPhone}</p>
        <p>배송지: {order.customerAddress}</p>
      </div>

      <div className="flex gap-4 text-sm">
        <Link href="/orders" className="underline">
          주문내역으로
        </Link>
        {home}
      </div>
    </div>
  );
}
