"use client";

import Link from "next/link";
import { useCart } from "@/lib/cart";

export function Header() {
  const { totalCount } = useCart();

  return (
    <header className="border-b sticky top-0 bg-white z-10">
      <div className="max-w-5xl mx-auto px-4 h-16 flex items-center justify-between">
        <Link href="/" className="text-lg font-bold">
          쇼핑몰
        </Link>
        <Link href="/cart" className="text-sm font-medium">
          장바구니 ({totalCount})
        </Link>
      </div>
    </header>
  );
}
