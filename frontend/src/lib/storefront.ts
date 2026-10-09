import {
  BEST_PERIODS,
  PRODUCT_SORTS,
  type BestPeriod,
  type ProductSort,
} from "@/lib/api";
import { CATEGORIES } from "@/lib/categories";

export const DEFAULT_SORT: ProductSort = "popular";
export const DEFAULT_BEST_PERIOD: BestPeriod = "realtime";

export const SORT_OPTIONS: { value: ProductSort; label: string }[] = [
  { value: "popular", label: "인기도순" },
  { value: "newest", label: "최신 등록순" },
  { value: "price_asc", label: "낮은 가격순" },
  { value: "price_desc", label: "높은 가격순" },
  { value: "discount", label: "할인율순" },
  { value: "sales", label: "누적 판매순" },
  { value: "reviews", label: "리뷰 많은순" },
  { value: "rating", label: "평점 높은순" },
];

export const BEST_OPTIONS: { value: BestPeriod; label: string }[] = [
  { value: "realtime", label: "실시간" },
  { value: "weekly", label: "주간" },
  { value: "monthly", label: "월간" },
];

// Query values come from the URL, so anything unknown (or repeated) falls back to the default.
function first(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

export function parseSort(value: string | string[] | undefined): ProductSort {
  const v = first(value);
  return PRODUCT_SORTS.find((s) => s === v) ?? DEFAULT_SORT;
}

export function parseBestPeriod(value: string | string[] | undefined): BestPeriod {
  const v = first(value);
  return BEST_PERIODS.find((p) => p === v) ?? DEFAULT_BEST_PERIOD;
}

// Same rule as the menu: a category that is not in the list counts as no category,
// so arbitrary URL text is never echoed into the page heading.
export function parseCategory(value: string | string[] | undefined): string | undefined {
  const v = first(value);
  return v && CATEGORIES.includes(v) ? v : undefined;
}

type Selection = { category?: string; sort?: ProductSort; best?: BestPeriod };

// The best tabs and the sort tabs share one URL, so each link keeps the other's choice.
function homeHref({ category, sort, best }: Selection, hash: string) {
  const params = new URLSearchParams();
  if (category) params.set("category", category);
  if (sort) params.set("sort", sort);
  if (best) params.set("best", best);
  return `/?${params}#${hash}`;
}

export function sortHref(sort: ProductSort, keep: Pick<Selection, "category" | "best"> = {}) {
  return homeHref({ ...keep, sort }, "all");
}

export function bestHref(best: BestPeriod, keep: Pick<Selection, "category" | "sort"> = {}) {
  return homeHref({ ...keep, best }, "best");
}
