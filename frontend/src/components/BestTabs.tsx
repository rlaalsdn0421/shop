import Link from "next/link";
import type { BestPeriod, ProductSort } from "@/lib/api";
import { BEST_OPTIONS, bestHref } from "@/lib/storefront";

export function BestTabs({ selected, sort }: { selected: BestPeriod; sort?: ProductSort }) {
  return (
    <nav aria-label="베스트 기간">
      <ul className="flex items-center text-sm">
        {BEST_OPTIONS.map((option) => (
          <li key={option.value} className="border-l border-neutral-300 px-3 first:border-l-0">
            <Link
              href={bestHref(option.value, { sort })}
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
