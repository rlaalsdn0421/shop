import Image from "next/image";
import Link from "next/link";
import type { Product } from "@/lib/api";

export function PromoBanner({ product }: { product: Product }) {
  return (
    <Link
      href={`/products/${product.id}`}
      className="group relative flex items-center gap-6 overflow-hidden rounded-lg bg-black px-6 py-6 sm:py-8"
    >
      <div className="relative aspect-square w-24 sm:w-32 shrink-0 overflow-hidden rounded bg-gray-800">
        <Image
          src={product.imageUrl}
          alt={product.name}
          fill
          sizes="128px"
          className="object-cover transition-transform duration-300 group-hover:scale-105"
          unoptimized
        />
      </div>
      <div className="flex flex-col gap-1 min-w-0">
        <span className="inline-flex w-fit items-center gap-1 text-xs font-bold tracking-wide text-white">
          🔥 실시간 랭킹 1위
        </span>
        <span className="truncate text-lg sm:text-2xl font-extrabold text-white">
          {product.name}
        </span>
        <span className="text-base sm:text-lg font-bold text-gray-300">
          {product.price.toLocaleString()}원
        </span>
      </div>
      <span className="ml-auto hidden sm:inline-block shrink-0 rounded-full border border-gray-600 px-4 py-2 text-xs font-semibold text-white transition-colors group-hover:bg-white group-hover:text-black">
        지금 보기
      </span>
    </Link>
  );
}
