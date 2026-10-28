import { CATEGORIES } from "@/lib/categories";

export type MenuItem = { key: string; label: string; href: string };

export const MENU_ITEMS: MenuItem[] = [
  { key: "best", label: "베스트", href: "/best" },
  { key: "all", label: "전체상품", href: "/products" },
  { key: "new", label: "신상품", href: "/new" },
  ...CATEGORIES.map((name) => ({
    key: `category:${name}`,
    label: name,
    href: `/?category=${encodeURIComponent(name)}`,
  })),
];

// 베스트/전체상품/신상품 have their own pages (matched exactly, so /products/<id> is not 전체상품).
// The home page (even with its own ?best= / ?sort= section tabs) is none of them; only a category query marks a menu item.
export function activeMenuKey(pathname: string, params: URLSearchParams): string | undefined {
  if (pathname === "/best") return "best";
  if (pathname === "/products") return "all";
  if (pathname === "/new") return "new";
  if (pathname !== "/") return undefined;
  const category = params.get("category");
  return category && CATEGORIES.includes(category) ? `category:${category}` : undefined;
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
