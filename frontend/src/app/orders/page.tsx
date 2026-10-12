"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useAuth, useRequireRole } from "@/auth/AuthContext";
import { listMyOrders, AuthError, type OrderSummary } from "@/lib/api";
import { formatKoreanDate } from "@/lib/date";
import { remainingLabel, statusLabel } from "@/lib/payment";
import { formatWon } from "@/lib/price";
import { useNow } from "@/lib/useNow";

export default function OrdersPage() {
  const { session, ready } = useRequireRole(["ADMIN", "SELLER", "USER"]);

  if (!ready || !session) return null;
  // The key remounts the list, so a different login never shows the previous user's orders.
  return <OrderList key={session.token} token={session.token} />;
}

function OrderList({ token }: { token: string }) {
  const { logout } = useAuth();
  const now = useNow();
  // null = first page still loading.
  const [orders, setOrders] = useState<OrderSummary[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  // Screen-reader announcement after "more" finishes (see the single role="status" below).
  const [announce, setAnnounce] = useState("");
  // Set false on unmount so a late "more" response is ignored.
  const alive = useRef(true);
  // Blocks a second click in the same tick, before the disabled button re-renders.
  const moreLock = useRef(false);
  // Where focus goes after "more" settles: the first new order, the list itself, or the button again.
  const pendingFocus = useRef<string | null>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const moreButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  // The button is disabled while loading and may disappear on the last page; without this focus falls back to body.
  useEffect(() => {
    const target = pendingFocus.current;
    if (!target || loadingMore) return;
    pendingFocus.current = null;
    if (target === "button") moreButton.current?.focus();
    else {
      const links = Array.from(listRef.current?.querySelectorAll<HTMLElement>("a[data-order-id]") ?? []);
      (links.find((a) => a.dataset.orderId === target) ?? listRef.current)?.focus();
    }
  }, [orders, loadingMore, moreFailed]);

  useEffect(() => {
    // Ignore a response that arrives after unmount or a retry.
    let cancelled = false;
    listMyOrders(token)
      .then((page) => {
        if (cancelled) return;
        setOrders(page.orders);
        setNextCursor(page.nextCursor);
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
  }, [token, attempt]);

  function retry() {
    setFailed(false);
    setOrders(null);
    setAttempt((a) => a + 1);
  }

  async function loadMore() {
    if (!nextCursor || moreLock.current) return;
    moreLock.current = true;
    setLoadingMore(true);
    setMoreFailed(false);
    setAnnounce("");
    try {
      const page = await listMyOrders(token, nextCursor);
      if (!alive.current) return;
      // Keep the list as is and only append orders we do not already show.
      const seen = new Set((orders ?? []).map((o) => o.id));
      const fresh = page.orders.filter((o) => !seen.has(o.id));
      setOrders([...(orders ?? []), ...fresh]);
      setNextCursor(page.nextCursor);
      setAnnounce(fresh.length > 0 ? `${fresh.length}건 더 불러왔어요` : "더 불러올 주문이 없어요");
      pendingFocus.current = fresh[0]?.id ?? "list";
    } catch (e) {
      if (!alive.current) return;
      if (e instanceof AuthError) logout();
      else {
        setMoreFailed(true);
        pendingFocus.current = "button";
      }
    } finally {
      moreLock.current = false;
      if (alive.current) setLoadingMore(false);
    }
  }

  let body;
  if (failed) {
    body = (
      <div className="flex flex-col items-start gap-3">
        <p role="alert" className="text-sm text-red-600">
          주문 내역을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
        </p>
        <button onClick={retry} className="border rounded px-3 py-2 text-sm">
          다시 불러오기
        </button>
      </div>
    );
  } else if (orders === null) {
    body = null; // the status line below says "loading"
  } else if (orders.length === 0) {
    body = (
      <div className="flex flex-col items-start gap-3">
        <p className="text-gray-600">아직 주문 내역이 없어요.</p>
        <Link href="/" className="text-sm underline">
          홈으로
        </Link>
      </div>
    );
  } else {
    body = (
      <>
        <ul ref={listRef} tabIndex={-1} className="flex flex-col gap-3 outline-none">
          {orders.map((o) => {
            const remaining = o.status === "PENDING_PAYMENT" ? remainingLabel(o.expiresAt, now) : null;
            return (
              <li key={o.id}>
                {/* DOM order is name, price, then date/number so the accessible name starts with the product; `order-first` keeps the date row on top visually. */}
                <Link
                  href={`/orders/${o.id}`}
                  data-order-id={o.id}
                  className="flex flex-col border rounded p-4 hover:bg-gray-50"
                >
                  <div className="font-medium">
                    {o.firstItemName}
                    {o.itemCount > 1 ? ` 외 ${o.itemCount - 1}건` : ""}
                  </div>
                  <div className="mt-1 text-sm">{formatWon(o.totalAmount)}</div>
                  {statusLabel(o.status) && <div className="mt-1 text-xs text-gray-600">{statusLabel(o.status)}</div>}
                  <div className="order-first mb-2 flex justify-between text-xs text-gray-500">
                    <span>{formatKoreanDate(o.createdAt)}</span>
                    <span>주문번호 {o.id.slice(0, 8)}</span>
                  </div>
                </Link>
                {/* A sibling link, not a button inside the card link; payment itself happens on the detail page. */}
                {remaining && (
                  <Link
                  href={`/orders/${o.id}`}
                  aria-label={`결제하기 (남은 시간 ${remaining}) - ${o.firstItemName}`}
                  className="mt-1 inline-block text-sm underline"
                >
                    결제하기 (남은 시간 {remaining})
                  </Link>
                )}
              </li>
            );
          })}
        </ul>
        {moreFailed && (
          <p role="alert" className="text-sm text-red-600">
            주문 내역을 더 불러오지 못했어요.
          </p>
        )}
        {nextCursor && (
          <button
            ref={moreButton}
            onClick={loadMore}
            disabled={loadingMore}
            className="border rounded px-3 py-2 text-sm disabled:opacity-50"
          >
            {loadingMore ? "불러오는 중..." : moreFailed ? "다시 시도" : "더 보기"}
          </button>
        )}
      </>
    );
  }

  return (
    <div className="max-w-lg mx-auto flex flex-col gap-4">
      <h1 className="text-2xl font-bold">주문내역</h1>
      {/* One persistent live region: visible "loading" first, then a screen-reader-only result of "more". */}
      <p role="status" className={orders === null && !failed ? "text-gray-600" : "sr-only"}>
        {orders === null && !failed ? "불러오는 중..." : announce}
      </p>
      {body}
    </div>
  );
}
