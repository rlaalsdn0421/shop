"use client";

import { useState } from "react";
import { useCart } from "@/lib/cart";

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
    return <p className="text-red-600 font-medium">품절된 상품입니다.</p>;
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <button
          aria-label="수량 감소"
          className="w-8 h-8 border rounded"
          onClick={() => setQuantity((q) => Math.max(1, q - 1))}
        >
          -
        </button>
        <span className="w-8 text-center">{quantity}</span>
        <button
          aria-label="수량 증가"
          className="w-8 h-8 border rounded"
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
        className="bg-black text-white rounded py-3 hover:opacity-90"
      >
        장바구니 담기
      </button>
      {added && (
        <p role="status" className="text-sm text-green-700">
          장바구니에 담았습니다.
        </p>
      )}
    </div>
  );
}
