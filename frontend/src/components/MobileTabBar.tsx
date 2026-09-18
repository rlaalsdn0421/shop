"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCart } from "@/cart/CartContext";
import { useAuth } from "@/auth/AuthContext";

export function MobileTabBar() {
  const pathname = usePathname();
  const { totalCount } = useCart();
  const { session, logout } = useAuth();
  const router = useRouter();

  return (
    <nav className="md:hidden fixed bottom-0 inset-x-0 z-20 bg-black text-gray-400 border-t border-gray-800">
      <div className="flex items-stretch h-14">
        <Link
          href="/"
          className={`flex-1 flex flex-col items-center justify-center gap-0.5 text-[11px] ${
            pathname === "/" ? "text-white" : ""
          }`}
        >
          <span className="text-base">🏠</span>
          홈
        </Link>
        <Link
          href="/cart"
          className={`flex-1 flex flex-col items-center justify-center gap-0.5 text-[11px] ${
            pathname === "/cart" ? "text-white" : ""
          }`}
        >
          <span className="text-base">🛒</span>
          장바구니{totalCount > 0 ? ` (${totalCount})` : ""}
        </Link>
        {session ? (
          <button
            onClick={() => {
              logout();
              router.push("/");
            }}
            className="flex-1 flex flex-col items-center justify-center gap-0.5 text-[11px]"
          >
            <span className="text-base">👤</span>
            로그아웃
          </button>
        ) : (
          <Link
            href="/login"
            className={`flex-1 flex flex-col items-center justify-center gap-0.5 text-[11px] ${
              pathname === "/login" ? "text-white" : ""
            }`}
          >
            <span className="text-base">👤</span>
            로그인
          </Link>
        )}
      </div>
    </nav>
  );
}
