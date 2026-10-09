import Link from "next/link";
import type { BestPeriod, ProductSort } from "@/lib/api";
import { SORT_OPTIONS, sortHref } from "@/lib/storefront";

export function SortTabs({
  selected,
  category,
  best,
}: {
  selected: ProductSort;
  category?: string;
  best?: BestPeriod;
}) {
  return (
    <nav aria-label="정렬" className="mb-6 border-b border-neutral-200">
      <ul className="flex gap-x-5 overflow-x-auto whitespace-nowrap text-sm [scrollbar-width:none] [&::-webkit-scrollbar]:hidden sm:justify-center">
        {SORT_OPTIONS.map((option) => (
          <li key={option.value} className="shrink-0">
            <Link
              href={sortHref(option.value, { category, best })}
              scroll={false}
              aria-current={option.value === selected ? "true" : undefined}
              className={`block border-b-2 pb-2.5 ${
                option.value === selected
                  ? "border-neutral-900 font-bold text-neutral-900"
                  : "border-transparent text-neutral-500 hover:text-neutral-800"
              }`}
            >
              {option.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
