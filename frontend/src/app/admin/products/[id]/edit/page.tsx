"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useRequireRole } from "@/auth/AuthContext";
import { AdminProductEditForm } from "@/components/admin/AdminProductEditForm";
import { getProduct, type ProductDetail } from "@/lib/api";

export default function AdminProductEditPage() {
  const { id } = useParams<{ id: string }>();
  const { session, ready } = useRequireRole(["ADMIN", "SELLER"]);
  // undefined = still loading, null = no such product.
  const [product, setProduct] = useState<ProductDetail | null | undefined>(undefined);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const signedIn = ready && !!session;

  useEffect(() => {
    if (!signedIn) return;
    let cancelled = false;
    getProduct(id)
      .then((p) => !cancelled && setProduct(p))
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [id, signedIn, attempt]);

  // Reload discards the edits: the form is rebuilt from the fresh product.
  function reload() {
    setProduct(undefined);
    setFailed(false);
    setAttempt((a) => a + 1);
  }

  if (!ready || !session || session.role === "USER") return null;

  return (
    <div className="flex flex-col gap-4">
      <Link href="/admin/products" className="text-sm text-gray-600 underline self-start">
        상품 관리로 돌아가기
      </Link>
      {failed ? (
        <div className="flex flex-col items-start gap-2">
          <p role="alert" className="text-sm text-red-600">상품 정보를 불러오지 못했습니다.</p>
          <button type="button" onClick={reload} className="border rounded px-3 py-2 text-sm">
            다시 시도
          </button>
        </div>
      ) : product === undefined ? (
        <p role="status" className="text-gray-600">불러오는 중...</p>
      ) : product === null ? (
        <p role="alert" className="text-sm text-red-600">상품을 찾을 수 없어요.</p>
      ) : (
        <AdminProductEditForm key={attempt} product={product} token={session.token} onReload={reload} />
      )}
    </div>
  );
}
