"use client";

import Image from "next/image";
import Link from "next/link";
import { useCart } from "@/frontend/cart/CartContext";

type Props = {
  id: string;
  name: string;
  price: number;
  imageUrl: string;
};

export function ProductCard({ id, name, price, imageUrl }: Props) {
  const { add } = useCart();

  return (
    <div className="border rounded-lg overflow-hidden flex flex-col">
      <Link href={`/products/${id}`}>
        <div className="relative aspect-square bg-gray-100">
          <Image src={imageUrl} alt={name} fill className="object-cover" unoptimized />
        </div>
      </Link>
      <div className="p-3 flex flex-col gap-2 flex-1">
        <Link href={`/products/${id}`} className="font-medium text-sm">
          {name}
        </Link>
        <span className="text-sm text-gray-700">{price.toLocaleString()}원</span>
        <button
          onClick={() => add({ productId: id, name, price, imageUrl })}
          className="mt-auto text-sm bg-black text-white rounded py-2 hover:opacity-90"
        >
          장바구니 담기
        </button>
      </div>
    </div>
  );
}
