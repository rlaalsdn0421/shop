"use client";

import Image from "next/image";
import Link from "next/link";
import { useCart } from "@/cart/CartContext";

type Props = {
  id: string;
  name: string;
  price: number;
  imageUrl: string;
  rank?: number;
};

export function ProductCard({ id, name, price, imageUrl, rank }: Props) {
  const { add } = useCart();

  return (
    <div className="group flex flex-col">
      <Link href={`/products/${id}`} className="relative block aspect-square bg-gray-100 overflow-hidden">
        <Image
          src={imageUrl}
          alt={name}
          fill
          className="object-cover transition-transform group-hover:scale-105"
          unoptimized
        />
        {rank !== undefined && (
          <span className="absolute top-2 left-2 flex h-6 min-w-6 items-center justify-center rounded-full bg-black/80 px-1.5 text-xs font-bold text-white">
            {rank}
          </span>
        )}
        <button
          onClick={(e) => {
            e.preventDefault();
            add({ productId: id, name, price, imageUrl });
          }}
          className="absolute inset-x-2 bottom-2 rounded bg-black py-2 text-xs font-semibold text-white opacity-0 transition-opacity hover:opacity-90 group-hover:opacity-100"
        >
          장바구니 담기
        </button>
      </Link>
      <div className="pt-2 flex flex-col gap-0.5">
        <Link href={`/products/${id}`} className="text-sm text-gray-900 line-clamp-1">
          {name}
        </Link>
        <span className="text-sm font-bold text-gray-900">{price.toLocaleString()}원</span>
      </div>
    </div>
  );
}
