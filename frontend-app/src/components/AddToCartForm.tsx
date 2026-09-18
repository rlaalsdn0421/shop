"use client";

import { useState } from "react";
import { useCart } from "@/cart/CartContext";

type Props = {
  id: string;
  name: string;
  price: number;
  imageUrl: string;
  stock: number;
};

export function AddToCartForm({ id, name, price, imageUrl, stock }: Props) {
  const { add } = useCart();
  const [quantity, setQuantity] = useState(1);
  const [added, setAdded] = useState(false);

  if (stock <= 0) {
    return <p className="text-red-600 font-medium text-sm">품절된 상품입니다.</p>;
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-3">
        <button
          aria-label="수량 감소"
          className="w-8 h-8 border border-black/20 rounded-full"
          onClick={() => setQuantity((q) => Math.max(1, q - 1))}
        >
          -
        </button>
        <span className="w-6 text-center text-sm font-semibold">{quantity}</span>
        <button
          aria-label="수량 증가"
          className="w-8 h-8 border border-black/20 rounded-full"
          onClick={() => setQuantity((q) => Math.min(stock, q + 1))}
        >
          +
        </button>
      </div>
      <button
        onClick={() => {
          add({ productId: id, name, price, imageUrl }, quantity);
          setAdded(true);
        }}
        className="bg-black text-white rounded-full py-3 text-sm font-bold"
      >
        장바구니 담기
      </button>
      {added && (
        <p role="status" className="text-xs text-black/60">
          장바구니에 담았습니다.
        </p>
      )}
    </div>
  );
}
