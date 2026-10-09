import { CATEGORIES } from "@/lib/categories";

export type MenuItem = { key: string; label: string; href: string };

export const MENU_ITEMS: MenuItem[] = [
  { key: "best", label: "베스트", href: "/#best" },
  { key: "all", label: "전체상품", href: "/#all" },
  { key: "new", label: "신상품", href: "/#new" },
  ...CATEGORIES.map((name) => ({
    key: `category:${name}`,
    label: name,
    href: `/?category=${encodeURIComponent(name)}`,
  })),
];

// The hash isn't visible to React, so the section links count as active when the
// URL carries the query their own tabs/"전체보기" links add (?best=, ?sort=).
export function activeMenuKey(pathname: string, params: URLSearchParams): string | undefined {
  if (pathname !== "/") return undefined;
  const category = params.get("category");
  if (category) return CATEGORIES.includes(category) ? `category:${category}` : undefined;
  if (params.has("best")) return "best";
  if (params.has("sort")) return "all";
  return undefined;
}

// How many items fit on one line next to the "더보기" button. When everything fits
// the button isn't shown, so it takes no room.
export function fitCount(widths: number[], available: number, gap: number, moreWidth: number) {
  const total = widths.reduce((sum, w, i) => sum + w + (i > 0 ? gap : 0), 0);
  if (total <= available) return widths.length;
  let used = moreWidth;
  let count = 0;
  for (const w of widths) {
    if (used + gap + w > available) break;
    used += gap + w;
    count++;
  }
  return count;
}
