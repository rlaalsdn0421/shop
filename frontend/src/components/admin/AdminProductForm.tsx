"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useRequireRole } from "@/auth/AuthContext";
import { CATEGORIES } from "@/lib/categories";
import { parseHashtags } from "@/lib/hashtags";
import { MAX_PRICE } from "@/lib/price";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";
const MAX_IMAGES = 10;

// Inline field errors use a polite live region so typing "12900" does not interrupt a screen
// reader at every digit. It stays mounted (empty) so the announcement is reliable.
function FieldError({ id, message }: { id: string; message: string | null }) {
  return (
    <p id={id} role="status" aria-live="polite" className={message ? "mt-1 text-sm text-red-600" : undefined}>
      {message}
    </p>
  );
}

export function AdminProductForm() {
  const router = useRouter();
  const { session, ready } = useRequireRole(["ADMIN", "SELLER"]);
  const [form, setForm] = useState({
    name: "",
    description: "",
    price: "",
    originalPrice: "",
    hashtags: "",
    stock: "",
    category: "",
  });
  // Rows keep a stable key so deleting a middle row does not hand its input to the next one.
  const [imageRows, setImageRows] = useState([{ key: 0, url: "" }]);
  const nextRowKey = useRef(1);
  const imageInputs = useRef<(HTMLInputElement | null)[]>([]);
  const focusRow = useRef<number | null>(null);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  // The ref blocks a second submit in the same tick, before the disabled button re-renders.
  const submitLock = useRef(false);
  const priceRef = useRef<HTMLInputElement>(null);
  const originalPriceRef = useRef<HTMLInputElement>(null);
  const hashtagsRef = useRef<HTMLInputElement>(null);

  const { tags, error: hashtagError } = parseHashtags(form.hashtags);
  // Same rule and wording as the server; only checked once both prices are filled in.
  const minOriginalPrice = form.price !== "" ? Number(form.price) + 1 : undefined;
  const tooBig = `${MAX_PRICE.toLocaleString("ko-KR")}원 이하로 입력해 주세요.`;
  const priceError = Number(form.price) > MAX_PRICE ? `가격은 ${tooBig}` : null;
  const originalPriceError =
    form.originalPrice === ""
      ? null
      : Number(form.originalPrice) > MAX_PRICE
        ? `정가는 ${tooBig}`
        : form.price !== "" && !(Number(form.originalPrice) > Number(form.price))
          ? "정가는 판매가보다 커야 해요."
          : null;

  // Trimmed once; blank extra rows are dropped on submit, a repeated address is flagged on the later row.
  const imageUrls = imageRows.map((row) => row.url.trim());
  const duplicateRow = (i: number) => imageUrls[i] !== "" && imageUrls.indexOf(imageUrls[i]) < i;
  const firstDuplicate = imageUrls.findIndex((_, i) => duplicateRow(i));

  useEffect(() => {
    if (focusRow.current === null) return;
    imageInputs.current[focusRow.current]?.focus();
    focusRow.current = null;
  }, [imageRows]);

  function addImageRow() {
    if (imageRows.length >= MAX_IMAGES) return;
    focusRow.current = imageRows.length;
    setImageRows([...imageRows, { key: nextRowKey.current++, url: "" }]);
  }

  function removeImageRow(index: number) {
    focusRow.current = Math.max(index - 1, 0);
    setImageRows(imageRows.filter((_, i) => i !== index));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    // Never silent: put the cursor on the first invalid field (its message is already shown).
    const firstInvalid = priceError
      ? priceRef
      : originalPriceError
        ? originalPriceRef
        : hashtagError
          ? hashtagsRef
          : null;
    if (firstInvalid) {
      firstInvalid.current?.focus();
      return;
    }
    if (imageUrls[0] === "") {
      setError("첫 번째 이미지 주소를 입력해 주세요.");
      imageInputs.current[0]?.focus();
      return;
    }
    if (firstDuplicate >= 0) {
      imageInputs.current[firstDuplicate]?.focus();
      return;
    }
    const urls = imageUrls.filter(Boolean);
    if (submitLock.current) return;
    submitLock.current = true;
    setSubmitting(true);
    try {
      const res = await fetch(`${BACKEND_URL}/api/admin/products`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(session ? { Authorization: `Bearer ${session.token}` } : {}),
        },
        body: JSON.stringify({
          name: form.name,
          description: form.description,
          price: Number(form.price),
          ...(form.originalPrice !== "" ? { originalPrice: Number(form.originalPrice) } : {}),
          ...(tags.length > 0 ? { hashtags: tags } : {}),
          // Both on purpose: an older backend only reads imageUrl, a newer one reads imageUrls.
          imageUrl: urls[0],
          imageUrls: urls,
          stock: Number(form.stock),
          category: form.category,
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "등록에 실패했습니다.");
        submitLock.current = false;
        setSubmitting(false);
        return;
      }
      // Success: stay locked (button disabled) until the route changes, so no double POST.
      router.push("/admin/products");
      router.refresh();
    } catch {
      setError("네트워크 오류로 등록에 실패했습니다.");
      submitLock.current = false;
      setSubmitting(false);
    }
  }

  if (!ready || !session) return null;

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
        <div>
          <label htmlFor="product-price" className="sr-only">가격</label>
          <input
            id="product-price"
            ref={priceRef}
            className="w-full border rounded px-3 py-2 text-sm"
            placeholder="가격"
            type="number"
            min={0}
            max={MAX_PRICE}
            step={1}
            value={form.price}
            onChange={(e) => setForm({ ...form, price: e.target.value })}
            aria-invalid={priceError ? true : undefined}
            aria-describedby={priceError ? "product-price-error" : undefined}
            required
          />
          <FieldError id="product-price-error" message={priceError} />
        </div>
        <div>
          <label htmlFor="product-original-price" className="sr-only">정가 (선택)</label>
          <input
            id="product-original-price"
            ref={originalPriceRef}
            className="w-full border rounded px-3 py-2 text-sm"
            placeholder="정가 (선택, 판매가보다 커야 해요)"
            type="number"
            min={minOriginalPrice ?? 1}
            max={MAX_PRICE}
            step={1}
            value={form.originalPrice}
            onChange={(e) => setForm({ ...form, originalPrice: e.target.value })}
            aria-invalid={originalPriceError ? true : undefined}
            aria-describedby={originalPriceError ? "product-original-price-error" : undefined}
          />
          <FieldError id="product-original-price-error" message={originalPriceError} />
        </div>
        <div>
          <label htmlFor="product-hashtags" className="sr-only">해시태그 (최대 3개, 공백으로 구분)</label>
          <input
            id="product-hashtags"
            ref={hashtagsRef}
            className="w-full border rounded px-3 py-2 text-sm"
            placeholder="해시태그 (최대 3개, 공백으로 구분)"
            value={form.hashtags}
            onChange={(e) => setForm({ ...form, hashtags: e.target.value })}
            aria-invalid={hashtagError ? true : undefined}
            aria-describedby={hashtagError ? "product-hashtags-error" : undefined}
          />
          <FieldError id="product-hashtags-error" message={hashtagError} />
        </div>
        <fieldset className="flex flex-col gap-2">
          <legend className="sr-only">상품 이미지 (첫 번째가 대표 이미지, 최대 {MAX_IMAGES}장)</legend>
          {imageRows.map((row, i) => {
            const duplicate = duplicateRow(i);
            return (
              <div key={row.key}>
                <div className="flex gap-2">
                  <label htmlFor={`product-image-${row.key}`} className="sr-only">{`이미지 주소 ${i + 1}`}</label>
                  <input
                    id={`product-image-${row.key}`}
                    ref={(el) => {
                      imageInputs.current[i] = el;
                    }}
                    className="min-w-0 flex-1 border rounded px-3 py-2 text-sm"
                    placeholder={i === 0 ? "이미지 주소 (대표 이미지)" : `이미지 주소 ${i + 1}`}
                    value={row.url}
                    onChange={(e) =>
                      setImageRows(imageRows.map((r) => (r.key === row.key ? { ...r, url: e.target.value } : r)))
                    }
                    maxLength={2000}
                    required={i === 0}
                    aria-invalid={duplicate ? true : undefined}
                    aria-describedby={duplicate ? `product-image-${row.key}-error` : undefined}
                  />
                  {imageRows.length > 1 && (
                    <button
                      type="button"
                      onClick={() => removeImageRow(i)}
                      aria-label={`이미지 주소 ${i + 1} 삭제`}
                      className="shrink-0 border rounded px-3 text-sm"
                    >
                      삭제
                    </button>
                  )}
                </div>
                <FieldError id={`product-image-${row.key}-error`} message={duplicate ? "이미 입력한 이미지 주소예요." : null} />
              </div>
            );
          })}
          <button
            type="button"
            onClick={addImageRow}
            disabled={imageRows.length >= MAX_IMAGES}
            className="self-start border rounded px-3 py-2 text-sm disabled:opacity-50"
          >
            이미지 추가
          </button>
          {imageRows.length >= MAX_IMAGES && (
            <p role="status" className="text-sm text-gray-600">이미지는 최대 {MAX_IMAGES}장까지 등록할 수 있어요.</p>
          )}
        </fieldset>
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
        <label htmlFor="product-category" className="sr-only">카테고리</label>
        <select
          id="product-category"
          className="border rounded px-3 py-2 text-sm"
          value={form.category}
          onChange={(e) => setForm({ ...form, category: e.target.value })}
        >
          <option value="">카테고리 선택 안 함</option>
          {CATEGORIES.map((category) => (
            <option key={category} value={category}>
              {category}
            </option>
          ))}
        </select>
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
