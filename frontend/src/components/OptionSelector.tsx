"use client";

import type { ProductOption } from "@/lib/api";
import { distinctValues, inStock, safeHex } from "@/lib/options";

type Props = {
  options: ProductOption[];
  color: string | null;
  size: string | null;
  onColor: (name: string) => void;
  onSize: (label: string) => void;
};

const focusRing =
  "focus:outline-none focus-visible:ring-2 focus-visible:ring-black focus-visible:ring-offset-2";

// Colour swatches and size buttons of the product page. Only the dimensions the product has are shown.
// Sold-out rules: a colour is dimmed when all of its options are out; a size is struck through when the
// option (chosen colour, size) is out (before a colour is chosen: when that size is out in every colour).
export function OptionSelector({ options, color, size, onColor, onSize }: Props) {
  const colors = distinctValues(options, "colorName");
  const sizes = distinctValues(options, "sizeLabel");
  const colorSoldOut = (c: string) => !options.some((o) => o.colorName === c && inStock(o));
  const sizeSoldOut = (s: string) =>
    !options.some((o) => o.sizeLabel === s && (colors.length === 0 || color === null || o.colorName === color) && inStock(o));

  return (
    <div className="flex flex-col gap-4">
      {colors.length > 0 && (
        <div>
          <p className="mb-2 text-sm font-medium">
            색상 <span className="font-normal text-gray-600">{color ?? ""}</span>
          </p>
          <div role="group" aria-label="색상" className="flex flex-wrap gap-2">
            {colors.map((name) => {
              const hex = safeHex(options.find((o) => o.colorName === name)?.colorHex);
              const soldOut = colorSoldOut(name);
              const selected = color === name;
              return (
                <button
                  key={name}
                  type="button"
                  aria-label={name}
                  aria-pressed={selected}
                  disabled={soldOut}
                  title={soldOut ? `${name} (품절)` : name}
                  onClick={() => onColor(name)}
                  style={hex ? { backgroundColor: hex } : undefined}
                  className={`${hex ? "h-9 w-9" : "h-9 px-3 text-sm"} rounded-full border border-gray-300 disabled:cursor-not-allowed disabled:opacity-30 ${
                    selected ? "ring-2 ring-black ring-offset-2" : ""
                  } ${focusRing}`}
                >
                  {hex ? null : name}
                </button>
              );
            })}
          </div>
        </div>
      )}
      {sizes.length > 0 && (
        <div>
          <p className="mb-2 text-sm font-medium">
            사이즈 <span className="font-normal text-gray-600">{size ?? ""}</span>
          </p>
          <div role="group" aria-label="사이즈" className="flex flex-wrap gap-2">
            {sizes.map((label) => {
              const soldOut = sizeSoldOut(label);
              const selected = size === label;
              return (
                <button
                  key={label}
                  type="button"
                  aria-pressed={selected}
                  disabled={soldOut}
                  title={soldOut ? `${label} (품절)` : undefined}
                  onClick={() => onSize(label)}
                  className={`min-w-12 rounded border px-3 py-2 text-sm disabled:cursor-not-allowed disabled:text-gray-400 disabled:line-through disabled:opacity-60 ${
                    selected ? "border-black bg-black text-white" : "border-gray-300"
                  } ${focusRing}`}
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
