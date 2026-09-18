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
    <header className="border-b sticky top-0 bg-white z-10">
      <div className="max-w-5xl mx-auto px-4 h-16 flex items-center justify-between gap-4">
        <Link href="/" className="text-lg font-bold">
          쇼핑몰
        </Link>
        <div className="flex items-center gap-4 text-sm font-medium">
          {session ? (
            <>
              {(session.role === "ADMIN" || session.role === "SELLER") && (
                <Link href="/admin/products">상품 관리</Link>
              )}
              <span className="text-gray-500">{session.email}</span>
              <button
                onClick={() => {
                  logout();
                  router.push("/");
                }}
              >
                로그아웃
              </button>
            </>
          ) : (
            <Link href="/login">로그인</Link>
          )}
          <Link href="/cart">장바구니 ({totalCount})</Link>
        </div>
      </div>
    </header>
  );
}
