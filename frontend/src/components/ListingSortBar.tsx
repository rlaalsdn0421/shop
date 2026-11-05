"use client";

import { useRouter } from "next/navigation";
import type { BestPeriod, ProductSort } from "@/lib/api";
import { BEST_OPTIONS, SORT_OPTIONS, listingHref } from "@/lib/storefront";

// `total` is optional so an older backend without it only loses the count.
export function ListingCount({ total }: { total?: number }) {
  if (total === undefined) return <span />;
  return (
    <p className="text-sm text-neutral-700">
      총 <b>{total.toLocaleString("ko-KR")}</b>개
    </p>
  );
}

// Count on the left, plain-text dropdown on the right. kind "sort" (default) picks the sort order,
// kind "best" picks the best-ranking period (/best); only options, label and URL differ.
export function ListingSortBar({
  total,
  selected,
  category,
  best,
  basePath,
  kind = "sort",
}: {
  total?: number;
  selected: ProductSort | BestPeriod;
  category?: string;
  best?: BestPeriod;
  basePath: string;
  kind?: "sort" | "best";
}) {
  const router = useRouter();
  const isBest = kind === "best";
  const options: { value: string; label: string }[] = isBest ? BEST_OPTIONS : SORT_OPTIONS;
  const hrefFor = (value: string) =>
    isBest ? listingHref(basePath, { best: value }) : listingHref(basePath, { category, best, sort: value });

  return (
    <div className="mb-6 flex items-center justify-between gap-4">
      <ListingCount total={total} />
      <div className="relative">
        <select
          aria-label={isBest ? "베스트 기간" : "정렬"}
          value={selected}
          onChange={(e) => router.push(hrefFor(e.target.value), { scroll: false })}
          className="cursor-pointer appearance-none bg-transparent py-1 pl-2 pr-6 text-left text-sm text-neutral-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-900"
        >
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <svg
          aria-hidden="true"
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          className="pointer-events-none absolute right-0 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-500"
        >
          <path d="M5 8l5 5 5-5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </div>
    </div>
  );
}
