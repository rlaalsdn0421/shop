"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCart } from "@/cart/CartContext";

const TABS = [
  { href: "/", label: "홈", icon: "🏠" },
  { href: "/cart", label: "장바구니", icon: "🛒" },
  { href: "/my", label: "마이", icon: "👤" },
] as const;

export function BottomTabBar() {
  const pathname = usePathname();
  const { totalCount } = useCart();

  return (
    <nav className="fixed bottom-0 inset-x-0 z-20 bg-white border-t border-black/10">
      <div className="max-w-md mx-auto grid grid-cols-3">
        {TABS.map((tab) => {
          const active = tab.href === "/" ? pathname === "/" : pathname.startsWith(tab.href);
          return (
            <Link
              key={tab.href}
              href={tab.href}
              className={`relative flex flex-col items-center justify-center gap-0.5 py-2.5 text-[11px] font-medium ${
                active ? "text-black" : "text-black/40"
              }`}
            >
              <span className="text-lg leading-none">{tab.icon}</span>
              <span>{tab.label}</span>
              {tab.href === "/cart" && totalCount > 0 && (
                <span className="absolute top-1 right-[calc(50%-22px)] min-w-[16px] h-4 px-1 rounded-full bg-black text-white text-[10px] font-bold flex items-center justify-center">
                  {totalCount}
                </span>
              )}
            </Link>
          );
        })}
      </div>
    </nav>
  );
}
