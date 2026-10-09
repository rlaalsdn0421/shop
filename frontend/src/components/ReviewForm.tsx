"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/auth/AuthContext";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export function ReviewForm({ productId }: { productId: string }) {
  const router = useRouter();
  const { session, ready } = useAuth();
  const [reviewerName, setReviewerName] = useState("");
  const [rating, setRating] = useState(5);
  const [comment, setComment] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!session) return;
    setError("");
    setSubmitting(true);
    try {
      const res = await fetch(`${BACKEND_URL}/api/products/${productId}/reviews`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${session.token}`,
        },
        body: JSON.stringify({ reviewerName, rating, comment }),
      });
      if (res.status === 401) {
        setError("로그인이 필요해요. 다시 로그인해 주세요.");
        return;
      }
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        // 409: the server explains that this user already reviewed the product.
        setError(data?.error ?? "리뷰 등록에 실패했습니다.");
        return;
      }
      setReviewerName("");
      setRating(5);
      setComment("");
      router.refresh();
    } catch {
      setError("네트워크 오류로 리뷰 등록에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  if (!ready) return null;

  if (!session) {
    return (
      <p className="border-t pt-4 text-sm text-gray-600">
        리뷰는 로그인 후 작성할 수 있어요.{" "}
        <Link href="/login" className="underline">
          로그인
        </Link>
      </p>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-3 border-t pt-4">
      <p className="font-medium text-sm">리뷰 작성</p>

      <div role="radiogroup" aria-label="평점" className="flex gap-1">
        {[1, 2, 3, 4, 5].map((value) => (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={rating === value}
            aria-label={`${value}점`}
            onClick={() => setRating(value)}
            className={`text-xl ${value <= rating ? "opacity-100" : "opacity-30"}`}
          >
            ⭐
          </button>
        ))}
      </div>

      <label htmlFor="review-name" className="sr-only">이름</label>
      <input
        id="review-name"
        className="border rounded px-3 py-2 text-sm"
        placeholder="이름"
        value={reviewerName}
        onChange={(e) => setReviewerName(e.target.value)}
        maxLength={100}
        required
      />

      <label htmlFor="review-comment" className="sr-only">리뷰 내용</label>
      <textarea
        id="review-comment"
        className="border rounded px-3 py-2 text-sm"
        placeholder="상품은 어떠셨나요?"
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        maxLength={1000}
        required
      />

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      <button
        type="submit"
        disabled={submitting}
        className="bg-black text-white rounded py-2 text-sm disabled:opacity-50 self-start px-4"
      >
        {submitting ? "등록 중..." : "리뷰 등록"}
      </button>
    </form>
  );
}
