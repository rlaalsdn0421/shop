"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { listProducts, type Product } from "@/lib/api";
import { ProductCard } from "@/components/ProductCard";

type Props = {
  initialItems: Product[];
  initialHasMore: boolean;
  category?: string;
  showRank: boolean;
};

// Remounted via a `key={category}` from the caller on category change, so
// `initialItems`/`initialHasMore` only ever need to seed state once per mount.
export function InfiniteProductGrid({ initialItems, initialHasMore, category, showRank }: Props) {
  const [items, setItems] = useState(initialItems);
  const [hasMore, setHasMore] = useState(initialHasMore);
  const [loading, setLoading] = useState(false);
  const page = useRef(0);
  const sentinelRef = useRef<HTMLDivElement>(null);

  const loadMore = useCallback(async () => {
    if (loading || !hasMore) return;
    setLoading(true);
    try {
      const nextPage = page.current + 1;
      const result = await listProducts(category, nextPage);
      setItems((prev) => [...prev, ...result.items]);
      setHasMore(result.hasMore);
      page.current = nextPage;
    } catch {
      // ponytail: silent retry-on-next-scroll is enough for a demo feed; add a visible
      // error/retry affordance if this becomes a real product.
    } finally {
      setLoading(false);
    }
  }, [category, hasMore, loading]);

  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel) return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) loadMore();
      },
      { rootMargin: "400px" }
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [loadMore]);

  if (items.length === 0) {
    return <p className="text-sm text-gray-500">해당 카테고리에 상품이 없습니다.</p>;
  }

  return (
    <>
      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-x-4 gap-y-6">
        {items.map((p, i) => (
          <ProductCard
            key={p.id}
            id={p.id}
            name={p.name}
            price={p.price}
            imageUrl={p.imageUrl}
            rank={showRank ? i + 1 : undefined}
          />
        ))}
      </div>
      <div ref={sentinelRef} className="h-1" />
      {loading && <p className="text-center text-sm text-gray-400 py-4">불러오는 중...</p>}
    </>
  );
}
