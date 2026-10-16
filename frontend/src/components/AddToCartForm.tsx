"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useCart } from "@/cart/CartContext";
import { useAuth } from "@/auth/AuthContext";
import type { ProductOption } from "@/lib/api";
import { OptionSelector } from "@/components/OptionSelector";
import { inStock, lowestPrice, optionLabel, optionsOf, resolveOption } from "@/lib/options";
import { discountView, formatWon } from "@/lib/price";

type Props = {
  id: string;
  name: string;
  price: number;
  imageUrl: string;
  stock: number;
  // Missing (older backend, product without options) = sold by price/stock as before.
  options?: ProductOption[];
  priceVaries?: boolean;
};

// Shown as "N개 남음" when this few are left.
export const LOW_STOCK_LIMIT = 5;

const focusRing =
  "focus:outline-none focus-visible:ring-2 focus-visible:ring-black focus-visible:ring-offset-2";

// Purchase block of the product page: stock note, quantity, total, and the buy / wishlist / cart buttons.
export function AddToCartForm({ id, name, price, imageUrl, stock: productStock, options: rawOptions, priceVaries }: Props) {
  const { add } = useCart();
  const { session } = useAuth();
  const router = useRouter();
  const [quantity, setQuantity] = useState(1);
  const [added, setAdded] = useState(false);
  const options = optionsOf(rawOptions);
  const hasOptions = options.length > 0;
  const [color, setColor] = useState<string | null>(null);
  const [size, setSize] = useState<string | null>(null);
  // With options only the chosen option's price and stock count; products.stock is not used for selling then.
  const selected = hasOptions ? resolveOption(options, color, size) : null;
  const stock = hasOptions ? (selected?.stock ?? 0) : productStock;
  const unitPrice = selected?.price ?? price;
  const soldOut = hasOptions ? options.every((o) => !inStock(o)) : stock <= 0;
  // Options: nothing can be bought until one option is fully chosen.
  const ready = !hasOptions || (selected !== null && selected.stock > 0);
  const discount = selected ? discountView(selected) : null;

  function chooseColor(next: string) {
    setColor(next);
    setQuantity(1);
    // The size that was chosen may be sold out in this colour; the shopper picks again.
    if (size !== null && !options.some((o) => o.colorName === next && o.sizeLabel === size && inStock(o))) setSize(null);
  }

  function chooseSize(next: string) {
    setSize(next);
    setQuantity(1);
  }

  // Not logged in: go to the login page and add nothing. Returns whether the item was added.
  function addToCart() {
    if (!session) {
      router.push("/login");
      return false;
    }
    add(
      selected
        ? { productId: id, optionId: selected.id, optionLabel: optionLabel(selected), name, price: selected.price, imageUrl }
        : { productId: id, name, price, imageUrl },
      quantity
    );
    return true;
  }

  return (
    <div className="flex flex-col gap-4">
      {soldOut && <p className="text-red-600 font-medium">품절된 상품입니다.</p>}
      {!soldOut && ready && stock <= LOW_STOCK_LIMIT && (
        <p className="text-sm font-medium text-orange-600">{stock}개 남음 · 거의 매진</p>
      )}

      {hasOptions && (
        <>
          <OptionSelector options={options} color={color} size={size} onColor={chooseColor} onSize={chooseSize} />
          {selected ? (
            <div>
              <p className="text-sm text-gray-600">
                선택한 옵션 <strong className="text-black">{optionLabel(selected)}</strong>
              </p>
              <div className="flex flex-wrap items-baseline gap-x-2">
                {discount?.rate != null && (
                  <span className="text-lg font-bold text-red-600">
                    <span className="sr-only">할인율 </span>
                    {discount.rate}%
                  </span>
                )}
                <p className="text-2xl font-bold">{formatWon(selected.price)}</p>
                {discount?.original != null && (
                  <del className="text-sm text-gray-400">
                    <span className="sr-only">정가 </span>
                    {formatWon(discount.original)}
                  </del>
                )}
              </div>
            </div>
          ) : (
            <div>
              <p className="text-2xl font-bold">
                {formatWon(lowestPrice(options, price))}
                {priceVaries ? "~" : ""}
              </p>
              {!soldOut && <p className="text-sm text-gray-600">옵션을 선택해 주세요</p>}
            </div>
          )}
        </>
      )}

      {!soldOut && ready && (
          <div className="flex items-center justify-between gap-4 border-t pt-4">
            <div role="group" aria-label="수량" className="flex items-center gap-2">
              <button
                type="button"
                aria-label="수량 감소"
                disabled={quantity <= 1}
                className={`w-8 h-8 border rounded disabled:opacity-40 ${focusRing}`}
                onClick={() => setQuantity((q) => Math.max(1, q - 1))}
              >
                -
              </button>
              <span className="w-8 text-center" aria-live="polite">
                {quantity}
              </span>
              <button
                type="button"
                aria-label="수량 증가"
                disabled={quantity >= stock}
                className={`w-8 h-8 border rounded disabled:opacity-40 ${focusRing}`}
                onClick={() => setQuantity((q) => Math.min(stock, q + 1))}
              >
                +
              </button>
            </div>
            <p className="text-sm text-gray-600">
              총 상품금액 <strong className="text-xl text-black">{formatWon(unitPrice * quantity)}</strong>
            </p>
          </div>
      )}

      <div className="flex flex-col gap-2">
        <button
          type="button"
          disabled={soldOut || !ready}
          onClick={() => {
            if (addToCart()) router.push("/cart");
          }}
          className={`w-full bg-black text-white rounded py-4 text-lg font-semibold hover:opacity-90 disabled:opacity-40 disabled:hover:opacity-40 ${focusRing}`}
        >
          구매하기
        </button>
        <div className="grid grid-cols-2 gap-2">
          <button
            type="button"
            disabled
            aria-disabled="true"
            title="준비 중이에요"
            className="flex flex-wrap items-center justify-center gap-x-1 border rounded py-3 text-gray-400 cursor-not-allowed"
          >
            <svg aria-hidden="true" viewBox="0 0 24 24" className="w-5 h-5 shrink-0 fill-none stroke-current" strokeWidth={2}>
              <path d="M12 21s-7-4.4-9.3-9A5.3 5.3 0 0 1 12 6.6 5.3 5.3 0 0 1 21.3 12C19 16.6 12 21 12 21z" />
            </svg>
            <span className="whitespace-nowrap">찜하기</span>{" "}
            <span className="text-xs whitespace-nowrap">(준비 중이에요)</span>
          </button>
          <button
            type="button"
            disabled={soldOut || !ready}
            onClick={() => setAdded(addToCart())}
            className={`border border-black rounded py-3 font-medium hover:bg-gray-50 disabled:opacity-40 disabled:hover:bg-transparent ${focusRing}`}
          >
            장바구니
          </button>
        </div>
      </div>
      {added && (
        <p role="status" className="text-sm text-green-700">
          장바구니에 담았습니다.
        </p>
      )}
    </div>
  );
}
