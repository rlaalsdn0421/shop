"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCart } from "@/cart/CartContext";
import { useAuth } from "@/auth/AuthContext";

export function Header() {
  const { totalCount } = useCart();
  const { session, logout } = useAuth();
  const router = useRouter();

  return (
    <header className="sticky top-0 z-10 bg-black text-white">
      <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between gap-6">
        <Link href="/" className="text-lg font-extrabold tracking-tight">
          쇼핑몰
        </Link>
        <div className="flex items-center gap-5 text-[13px] font-medium text-gray-300">
          {session ? (
            <>
              {(session.role === "ADMIN" || session.role === "SELLER") && (
                <Link href="/admin/products" className="hover:text-white">
                  상품 관리
                </Link>
              )}
              <span className="text-gray-500">{session.email}</span>
              <button
                onClick={() => {
                  logout();
                  router.push("/");
                }}
                className="hover:text-white"
              >
                로그아웃
              </button>
            </>
          ) : (
            <Link href="/login" className="hover:text-white">
              로그인
            </Link>
          )}
          <Link href="/cart" className="hover:text-white">
            장바구니 ({totalCount})
          </Link>
        </div>
      </div>
    </header>
  );
}
