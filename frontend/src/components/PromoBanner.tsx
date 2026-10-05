"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useState } from "react";
import type { Product } from "@/lib/api";

const AUTO_ADVANCE_MS = 4000;

export function PromoBanner({ products }: { products: Product[] }) {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const count = products.length;

  useEffect(() => {
    if (paused || count < 2) return;
    const timer = setInterval(() => setIndex((i) => (i + 1) % count), AUTO_ADVANCE_MS);
    return () => clearInterval(timer);
  }, [paused, count]);

  if (count === 0) return null;

  const go = (n: number) => setIndex((n + count) % count);

  return (
    <div
      className="group relative h-[216px] sm:h-72 overflow-hidden rounded-lg bg-black"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
    >
      <div
        className="flex h-full transition-transform duration-500 ease-out"
        style={{ transform: `translateX(-${index * 100}%)` }}
      >
        {products.map((product, i) => (
          <Link
            key={product.id}
            href={`/products/${product.id}`}
            aria-hidden={i !== index}
            tabIndex={i === index ? 0 : -1}
            className="flex h-full w-full shrink-0 items-center gap-6 px-12 sm:px-16"
          >
            <div className="relative aspect-square w-32 sm:w-52 shrink-0 overflow-hidden rounded bg-gray-800">
              <Image
                src={product.imageUrl}
                alt={product.name}
                fill
                sizes="208px"
                className="object-cover"
                unoptimized
              />
            </div>
            <div className="flex min-w-0 flex-col gap-1">
              <span className="inline-flex w-fit items-center gap-1 text-xs sm:text-sm font-bold tracking-wide text-white">
                🔥 실시간 랭킹 {i + 1}위
              </span>
              <span className="truncate text-xl sm:text-3xl font-extrabold text-white">
                {product.name}
              </span>
              <span className="text-base sm:text-xl font-bold text-gray-300">
                {product.price.toLocaleString()}원
              </span>
            </div>
            <span className="ml-auto hidden sm:inline-block shrink-0 rounded-full border border-gray-600 px-4 py-2 text-xs font-semibold text-white">
              지금 보기
            </span>
          </Link>
        ))}
      </div>

      {count > 1 && (
        <>
          <button
            type="button"
            aria-label="이전 배너"
            onClick={() => go(index - 1)}
            className="absolute left-2 top-1/2 -translate-y-1/2 flex h-8 w-8 items-center justify-center rounded-full bg-white/15 text-white opacity-0 transition-opacity hover:bg-white/30 group-hover:opacity-100 focus-visible:opacity-100"
          >
            ‹
          </button>
          <button
            type="button"
            aria-label="다음 배너"
            onClick={() => go(index + 1)}
            className="absolute right-2 top-1/2 -translate-y-1/2 flex h-8 w-8 items-center justify-center rounded-full bg-white/15 text-white opacity-0 transition-opacity hover:bg-white/30 group-hover:opacity-100 focus-visible:opacity-100"
          >
            ›
          </button>
          <div className="absolute bottom-3 left-1/2 flex -translate-x-1/2 gap-1.5">
            {products.map((product, i) => (
              <button
                key={product.id}
                type="button"
                aria-label={`${i + 1}번째 배너로 이동`}
                aria-current={i === index}
                onClick={() => go(i)}
                className={`h-1.5 rounded-full transition-all ${
                  i === index ? "w-5 bg-white" : "w-1.5 bg-white/40"
                }`}
              />
            ))}
          </div>
        </>
      )}
    </div>
  );
}
