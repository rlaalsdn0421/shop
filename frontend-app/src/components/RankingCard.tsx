import Image from "next/image";
import Link from "next/link";
import type { Product } from "@/lib/api";

export function RankingCard({ product, rank }: { product: Product; rank: number }) {
  return (
    <Link href={`/products/${product.id}`} className="flex flex-col gap-1.5">
      <div className="relative aspect-square bg-gray-100 overflow-hidden">
        <Image
          src={product.imageUrl}
          alt={product.name}
          fill
          className="object-cover"
          unoptimized
        />
        <span className="absolute top-1.5 left-1.5 w-6 h-6 rounded-full bg-black text-white text-xs font-bold flex items-center justify-center">
          {rank}
        </span>
      </div>
      <p className="text-[13px] font-bold leading-snug line-clamp-2">{product.name}</p>
      <p className="text-[13px] font-semibold">{product.price.toLocaleString()}원</p>
    </Link>
  );
}
