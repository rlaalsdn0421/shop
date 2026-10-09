"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { listProducts, type Product, type ProductSort } from "@/lib/api";
import { PRODUCT_PAGE_SIZE } from "@/lib/constants";
import { ProductCard } from "@/components/ProductCard";

type Props = {
  initialItems: Product[];
  initialHasMore: boolean;
  category?: string;
  sort: ProductSort;
};

// Remounted via a `key` (category + sort) from the caller when either changes, so
// `initialItems`/`initialHasMore` only ever need to seed state once per mount.
export function InfiniteProductGrid({ initialItems, initialHasMore, category, sort }: Props) {
  const [items, setItems] = useState(initialItems);
  const [hasMore, setHasMore] = useState(initialHasMore);
  const [loading, setLoading] = useState(false);
  // After a failed request, automatic loading stops until the user presses 다시 시도.
  const [failed, setFailed] = useState(false);
  const page = useRef(0);
  const sentinelRef = useRef<HTMLDivElement>(null);

  const loadMore = useCallback(async () => {
    if (loading || !hasMore) return;
    setLoading(true);
    try {
      const nextPage = page.current + 1;
      const result = await listProducts(category, nextPage, PRODUCT_PAGE_SIZE, sort);
      // Offset paging can repeat an item when rankings change between requests.
      setItems((prev) => {
        const seen = new Set(prev.map((p) => p.id));
        return [...prev, ...result.items.filter((p) => !seen.has(p.id))];
      });
      setHasMore(result.hasMore);
      page.current = nextPage;
      setFailed(false);
    } catch {
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }, [category, sort, hasMore, loading]);

  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel || failed) return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) loadMore();
      },
      { rootMargin: "400px" }
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [loadMore, failed]);

  if (items.length === 0) {
    return <p className="py-12 text-center text-sm text-neutral-500">상품이 없습니다.</p>;
  }

  return (
    <>
      <div className="grid grid-cols-2 gap-x-4 gap-y-8 sm:grid-cols-3 lg:grid-cols-5">
        {items.map((p) => (
          <ProductCard key={p.id} id={p.id} name={p.name} price={p.price} imageUrl={p.imageUrl} />
        ))}
      </div>
      <div ref={sentinelRef} className="h-1" />
      {loading && <p className="text-center text-sm text-neutral-400 py-4">불러오는 중...</p>}
      {failed && !loading && (
        <div role="alert" className="flex flex-col items-center gap-2 py-4 text-sm text-neutral-500">
          <p>더 불러오지 못했어요</p>
          <button
            type="button"
            onClick={loadMore}
            className="border border-neutral-300 px-4 py-1.5 text-neutral-800 hover:bg-neutral-50"
          >
            다시 시도
          </button>
        </div>
      )}
    </>
  );
}
