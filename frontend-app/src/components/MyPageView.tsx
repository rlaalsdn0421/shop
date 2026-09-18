"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/auth/AuthContext";

export function MyPageView() {
  const { session, ready, logout } = useAuth();
  const router = useRouter();
  const [orderId, setOrderId] = useState("");

  function handleLookup(e: React.FormEvent) {
    e.preventDefault();
    if (orderId.trim()) router.push(`/orders/${orderId.trim()}`);
  }

  if (!ready) return null;

  return (
    <div className="flex flex-col gap-6 px-4 pt-6">
      <h1 className="text-lg font-extrabold">마이</h1>

      {session ? (
        <div className="flex items-center justify-between border-b border-black/10 pb-4">
          <p className="text-sm font-semibold">{session.email}</p>
          <button onClick={logout} className="text-xs text-black/50 underline">
            로그아웃
          </button>
        </div>
      ) : (
        <div className="flex flex-col gap-3 border-b border-black/10 pb-4">
          <p className="text-sm text-black/60">로그인이 필요합니다.</p>
          <div className="flex gap-2">
            <Link
              href="/login"
              className="flex-1 text-center bg-black text-white rounded-full py-2.5 text-sm font-bold"
            >
              로그인
            </Link>
            <Link
              href="/register"
              className="flex-1 text-center border border-black/20 rounded-full py-2.5 text-sm font-bold"
            >
              회원가입
            </Link>
          </div>
        </div>
      )}

      <form onSubmit={handleLookup} className="flex flex-col gap-2">
        <p className="text-sm font-bold">주문 조회</p>
        <div className="flex gap-2">
          <label htmlFor="order-lookup" className="sr-only">주문번호</label>
          <input
            id="order-lookup"
            className="flex-1 border border-black/20 rounded px-3 py-2 text-sm"
            placeholder="주문번호 입력"
            value={orderId}
            onChange={(e) => setOrderId(e.target.value)}
          />
          <button
            type="submit"
            className="border border-black/20 rounded px-4 text-sm font-bold"
          >
            조회
          </button>
        </div>
      </form>
    </div>
  );
}
