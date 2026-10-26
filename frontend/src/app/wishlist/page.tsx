"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useAuth, useRequireRole } from "@/auth/AuthContext";
import { listWishlist, AuthError, type Product } from "@/lib/api";
import { ProductCard } from "@/components/ProductCard";
import { useWishlist } from "@/wishlist/WishlistContext";

export default function WishlistPage() {
  const { session, ready } = useRequireRole(["ADMIN", "SELLER", "USER"]);

  if (!ready || !session) return null;
  // The key remounts the list, so a different login never shows the previous user's wishes.
  return <WishlistItems key={session.token} token={session.token} />;
}

function WishlistItems({ token }: { token: string }) {
  const { logout } = useAuth();
  const { ids } = useWishlist();
  // null = still loading.
  const [items, setItems] = useState<Product[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  // The fetched list is the truth. Only ids un-wished on this page are hidden, never because `ids` is stale or still loading.
  const [removed, setRemoved] = useState<ReadonlySet<string>>(new Set());
  const [prevIds, setPrevIds] = useState(ids);
  const title = useRef<HTMLHeadingElement>(null);
  const removedCount = useRef(0);

  // Adjusting state while rendering (React's pattern for "state derived from a changed value"): an id that left `ids`
  // is removed; one that came back (a failed un-wish rolled back) is shown again.
  if (ids !== prevIds) {
    setPrevIds(ids);
    const next = new Set(removed);
    prevIds.forEach((id) => {
      if (!ids.has(id)) next.add(id);
    });
    ids.forEach((id) => next.delete(id));
    setRemoved(next);
  }

  // The clicked heart disappears with its card, so move focus to the title instead of dropping it on <body>.
  useEffect(() => {
    if (removed.size > removedCount.current) title.current?.focus();
    removedCount.current = removed.size;
  }, [removed]);

  useEffect(() => {
    // Ignore a response that arrives after unmount or a retry.
    let cancelled = false;
    listWishlist(token)
      .then((list) => {
        if (!cancelled) setItems(list);
      })
      .catch((e) => {
        if (cancelled) return;
        // An expired or invalid token: logging out makes useRequireRole send the user to /login.
        if (e instanceof AuthError) logout({ keepOrderAttempt: true });
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
    setItems(null);
    setAttempt((a) => a + 1);
  }

  const loading = !failed && items === null;
  const visible = (items ?? []).filter((p) => !removed.has(p.id));

  let body;
  if (failed) {
    body = (
      <div className="flex flex-col items-start gap-3">
        <p role="alert" className="text-sm text-red-600">
          찜한 상품을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
        </p>
        <button onClick={retry} className="border rounded px-3 py-2 text-sm">
          다시 불러오기
        </button>
      </div>
    );
  } else if (loading) {
    body = null; // the status line below says "loading"
  } else if (visible.length === 0) {
    body = (
      <div className="flex flex-col items-start gap-3">
        <p className="text-gray-600">아직 찜한 상품이 없어요.</p>
        <Link href="/" className="text-sm underline">
          홈으로
        </Link>
      </div>
    );
  } else {
    body = (
      <div className="grid grid-cols-2 gap-x-4 gap-y-8 sm:grid-cols-3 lg:grid-cols-5">
        {visible.map((p) => (
          <ProductCard key={p.id} {...p} />
        ))}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 ref={title} tabIndex={-1} className="text-2xl font-bold outline-none">
        찜한 상품
      </h1>
      {/* Always mounted so the text is announced when it appears; empty until something is un-wished here. */}
      <p role="status" aria-live="polite" className="sr-only">
        {removed.size > 0 ? "찜을 해제했어요" : ""}
      </p>
      {loading && (
        <p role="status" className="text-gray-600">
          불러오는 중...
        </p>
      )}
      {body}
    </div>
  );
}
