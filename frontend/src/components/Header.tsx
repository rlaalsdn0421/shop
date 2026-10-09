"use client";

import { Suspense } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCart } from "@/cart/CartContext";
import { useAuth } from "@/auth/AuthContext";
import { MainMenu } from "@/components/MainMenu";

const utilityClass = "hover:text-neutral-900";

export function Header() {
  const { totalCount } = useCart();
  const { session, logout } = useAuth();
  const router = useRouter();

  return (
    <>
      <header className="bg-white">
        <div className="mx-auto grid max-w-7xl grid-cols-1 px-4 md:grid-cols-[1fr_auto_1fr] md:items-center">
          {/* Small screens: utility links on their own line above the logo, so neither is squeezed. */}
          <Link
            href="/"
            className="row-start-2 py-3 text-center text-2xl font-extrabold tracking-tight text-neutral-900 md:col-start-2 md:row-start-1 md:py-6 md:text-3xl"
          >
            쇼핑몰
          </Link>
          <ul className="row-start-1 flex flex-wrap items-center justify-end gap-y-1 pt-2 text-xs text-neutral-600 md:col-start-3 md:pt-0 [&>li]:border-l [&>li]:border-neutral-300 [&>li]:px-2.5 [&>li:first-child]:border-l-0 [&>li:last-child]:pr-0">
            {session ? (
              <>
                {(session.role === "ADMIN" || session.role === "SELLER") && (
                  <li>
                    <Link href="/admin/products" className={utilityClass}>
                      상품 관리
                    </Link>
                  </li>
                )}
                <li className="text-neutral-500">{session.username}</li>
                <li>
                  <button
                    onClick={() => {
                      logout();
                      router.push("/");
                    }}
                    className={utilityClass}
                  >
                    로그아웃
                  </button>
                </li>
              </>
            ) : (
              <>
                <li>
                  <Link href="/login" className={utilityClass}>
                    로그인
                  </Link>
                </li>
                <li>
                  <Link href="/register" className={utilityClass}>
                    회원가입
                  </Link>
                </li>
              </>
            )}
            <li>
              <Link href="/cart" className={utilityClass}>
                장바구니 ({totalCount})
              </Link>
            </li>
          </ul>
        </div>
      </header>
      {/* useSearchParams needs a Suspense boundary so static pages can still prerender. */}
      <Suspense fallback={<div className="h-11 border-y border-neutral-200" />}>
        <MainMenu />
      </Suspense>
    </>
  );
}
