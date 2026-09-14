"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export function AdminProductForm() {
  const router = useRouter();
  const [form, setForm] = useState({
    name: "",
    description: "",
    price: "",
    imageUrl: "",
    stock: "",
  });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      const res = await fetch("/api/admin/products", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: form.name,
          description: form.description,
          price: Number(form.price),
          imageUrl: form.imageUrl,
          stock: Number(form.stock),
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "등록에 실패했습니다.");
        return;
      }
      router.push("/admin/products");
      router.refresh();
    } catch {
      setError("네트워크 오류로 등록에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="max-w-md mx-auto flex flex-col gap-4">
      <h1 className="text-xl font-bold">상품 등록</h1>
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <label htmlFor="product-name" className="sr-only">상품명</label>
        <input
          id="product-name"
          className="border rounded px-3 py-2 text-sm"
          placeholder="상품명"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          maxLength={200}
          required
        />
        <label htmlFor="product-description" className="sr-only">상품 설명</label>
        <textarea
          id="product-description"
          className="border rounded px-3 py-2 text-sm"
          placeholder="상품 설명"
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          maxLength={2000}
          required
        />
        <label htmlFor="product-price" className="sr-only">가격</label>
        <input
          id="product-price"
          className="border rounded px-3 py-2 text-sm"
          placeholder="가격"
          type="number"
          min={0}
          step={1}
          value={form.price}
          onChange={(e) => setForm({ ...form, price: e.target.value })}
          required
        />
        <label htmlFor="product-image" className="sr-only">이미지 URL</label>
        <input
          id="product-image"
          className="border rounded px-3 py-2 text-sm"
          placeholder="이미지 URL"
          value={form.imageUrl}
          onChange={(e) => setForm({ ...form, imageUrl: e.target.value })}
          maxLength={2000}
          required
        />
        <label htmlFor="product-stock" className="sr-only">재고 수량</label>
        <input
          id="product-stock"
          className="border rounded px-3 py-2 text-sm"
          placeholder="재고 수량"
          type="number"
          min={0}
          step={1}
          value={form.stock}
          onChange={(e) => setForm({ ...form, stock: e.target.value })}
          required
        />
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="bg-black text-white rounded py-3 disabled:opacity-50"
        >
          {submitting ? "등록 중..." : "등록하기"}
        </button>
      </form>
    </div>
  );
}
