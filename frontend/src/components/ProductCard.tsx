import Image from "next/image";
import Link from "next/link";
import { SHIPPING_FEE } from "@/lib/constants";

type Props = {
  id: string;
  name: string;
  price: number;
  imageUrl: string;
  rank?: number;
};

// The whole card is one link. A heart button (name row, right side) and a hashtag row
// (bottom) are planned; they will need to sit outside the link, so keep the text block
// as its own element.
export function ProductCard({ id, name, price, imageUrl, rank }: Props) {
  return (
    <Link href={`/products/${id}`} className="group flex flex-col">
      <span className="relative block aspect-[3/4] overflow-hidden bg-neutral-100">
        <Image
          src={imageUrl}
          alt=""
          fill
          className="object-cover transition-transform duration-300 group-hover:scale-105"
          unoptimized
        />
        {rank !== undefined && (
          <span className="absolute left-0 top-0 flex h-6 min-w-6 items-center justify-center bg-neutral-900 px-1 text-xs font-semibold text-white">
            <span className="sr-only">순위 </span>
            {rank}
          </span>
        )}
      </span>
      <span className="flex flex-col gap-1 pt-2.5">
        <span className="flex items-center justify-between gap-2">
          <span className="truncate text-[13px] text-neutral-800">{name}</span>
        </span>
        <span className="text-[15px] font-bold text-neutral-900">
          {price.toLocaleString("ko-KR")}원
        </span>
        <span className="w-fit border border-neutral-200 px-1.5 py-0.5 text-[11px] leading-none text-neutral-500">
          배송비 {SHIPPING_FEE.toLocaleString("ko-KR")}원
        </span>
      </span>
    </Link>
  );
}
