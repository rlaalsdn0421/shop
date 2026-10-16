import type { ProductOption } from "@/lib/api";

// A missing, null or non-array value (older backend) is simply "no options".
export function optionsOf(value: unknown): ProductOption[] {
  return Array.isArray(value) ? value : [];
}

// "화이트 / M": same wording the backend stores in order_items.option_label.
export function optionLabel(o: { colorName?: string | null; sizeLabel?: string | null }): string {
  return [o.colorName, o.sizeLabel].filter(Boolean).join(" / ");
}

export const inStock = (o: ProductOption) => o.stock > 0;

// Distinct values in first-seen order; options without that dimension are skipped.
export function distinctValues(options: ProductOption[], key: "colorName" | "sizeLabel"): string[] {
  return [...new Set(options.map((o) => o[key]).filter((v): v is string => !!v))];
}

// The option picked by a colour/size choice. A dimension the product does not have is ignored; one it has but
// the shopper has not chosen yet means "not resolved" (null).
export function resolveOption(options: ProductOption[], color: string | null, size: string | null): ProductOption | null {
  const hasColor = options.some((o) => o.colorName);
  const hasSize = options.some((o) => o.sizeLabel);
  if ((hasColor && color === null) || (hasSize && size === null)) return null;
  return (
    options.find((o) => (!hasColor || o.colorName === color) && (!hasSize || o.sizeLabel === size)) ?? null
  );
}

export const lowestPrice = (options: ProductOption[], fallback: number) =>
  options.length > 0 ? Math.min(...options.map((o) => o.price)) : fallback;

// Only a plain #RRGGBB goes into a style attribute.
export const safeHex = (hex: string | null | undefined) => (hex && /^#[0-9a-fA-F]{6}$/.test(hex) ? hex : null);
