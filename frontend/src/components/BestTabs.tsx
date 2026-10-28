import Link from "next/link";
import type { BestPeriod, ProductSort } from "@/lib/api";
import { BEST_OPTIONS, bestHref, listingHref } from "@/lib/storefront";

export function BestTabs({
  selected,
  sort,
  variant,
  basePath,
}: {
  selected: BestPeriod;
  sort?: ProductSort;
  variant?: "listing";
  basePath?: string;
}) {
  const hrefFor = (best: BestPeriod) =>
    basePath ? listingHref(basePath, { sort, best }) : bestHref(best, { sort });

  if (variant === "listing") {
    return (
      <nav aria-label="베스트 기간" className="mb-8 mt-4">
        <ul className="flex flex-wrap gap-y-2 text-sm">
          {BEST_OPTIONS.map((option) => {
            const isSelected = option.value === selected;
            return (
              <li key={option.value} className="border-l border-neutral-300 px-3 first:border-l-0 first:pl-0">
                <Link
                  href={hrefFor(option.value)}
                  scroll={false}
                  aria-current={isSelected ? "page" : undefined}
                  className={isSelected ? "font-bold text-neutral-900" : "text-neutral-500 hover:text-neutral-800"}
                >
                  {isSelected && <span aria-hidden="true">✓ </span>}
                  {option.label}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
    );
  }

  return (
    <nav aria-label="베스트 기간">
      <ul className="flex items-center text-sm">
        {BEST_OPTIONS.map((option) => (
          <li key={option.value} className="border-l border-neutral-300 px-3 first:border-l-0">
            <Link
              href={hrefFor(option.value)}
              scroll={false}
              aria-current={option.value === selected ? "true" : undefined}
              className={
                option.value === selected
                  ? "font-bold text-neutral-900"
                  : "text-neutral-400 hover:text-neutral-700"
              }
            >
              {option.value === selected && <span aria-hidden="true">✓ </span>}
              {option.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
