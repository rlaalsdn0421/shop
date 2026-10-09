import Image from "next/image";
import Link from "next/link";
import { SHIPPING_FEE } from "@/lib/constants";
import { discountView, formatWon, visibleHashtags } from "@/lib/price";

type Props = {
  id: string;
  name: string;
  price: number;
  imageUrl: string;
  originalPrice?: number | null;
  discountRate?: number | null;
  hashtags?: string[];
  rank?: number;
};

// The whole card is one link. A heart button (name row, right side) is planned; it will
// need to sit outside the link, so keep the text block as its own element.
export function ProductCard({ id, name, price, imageUrl, originalPrice, discountRate, hashtags, rank }: Props) {
  const { rate, original } = discountView({ price, originalPrice, discountRate });
  const tags = visibleHashtags(hashtags);
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
        <span className="flex flex-wrap items-baseline gap-x-1.5">
          {rate !== null && (
            <span className="text-[15px] font-bold text-red-600">
              <span className="sr-only">할인율 </span>
              {rate}%
            </span>
          )}
          <span className="text-[15px] font-bold text-neutral-900">{formatWon(price)}</span>
          {original !== null && (
            <del className="text-xs text-neutral-400">
              <span className="sr-only">정가 </span>
              {formatWon(original)}
            </del>
          )}
        </span>
        <span className="w-fit border border-neutral-200 px-1.5 py-0.5 text-[11px] leading-none text-neutral-500">
          배송비 {SHIPPING_FEE.toLocaleString("ko-KR")}원
        </span>
        {tags.length > 0 && (
          <span className="flex flex-wrap gap-x-1.5 gap-y-0.5">
            {tags.map((tag) => (
              <span key={tag} className="max-w-full truncate text-[11px] text-neutral-500">
                {`#${tag}`}
              </span>
            ))}
          </span>
        )}
      </span>
    </Link>
  );
}
