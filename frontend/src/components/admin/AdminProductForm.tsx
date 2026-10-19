"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useRequireRole } from "@/auth/AuthContext";
import { CATEGORIES } from "@/lib/categories";
import { parseHashtags } from "@/lib/hashtags";
import {
  MAX_OPTIONS,
  MAX_STOCK,
  OPTION_FIELD_ORDER,
  buildOptionLines,
  summarizeOptions,
  validateOptionRows,
  type OptionField,
  type OptionRow,
} from "@/lib/adminOptions";
import { MAX_PRICE, formatWon } from "@/lib/price";

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

const emptyOptionRow = (key: number): OptionRow => ({
  key,
  colorName: "",
  colorHex: "",
  sizeLabel: "",
  price: "",
  originalPrice: "",
  stock: "",
});

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
  // Options: when on, the rows below replace the single price / original price / stock inputs.
  const [useOptions, setUseOptions] = useState(false);
  const [useColor, setUseColor] = useState(true);
  const [useSize, setUseSize] = useState(true);
  const [optionRows, setOptionRows] = useState<OptionRow[]>([emptyOptionRow(0)]);
  const nextOptionKey = useRef(1);
  const optionInputs = useRef(new Map<string, HTMLInputElement>());
  const focusOption = useRef<string | null>(null);
  const colorToggleRef = useRef<HTMLInputElement>(null);
  // Row errors stay hidden until the first submit, so an empty new row is not scolded while the admin is still typing.
  const [optionsTried, setOptionsTried] = useState(false);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  // The ref blocks a second submit in the same tick, before the disabled button re-renders.
  const submitLock = useRef(false);
  const priceRef = useRef<HTMLInputElement>(null);
  const originalPriceRef = useRef<HTMLInputElement>(null);
  const stockRef = useRef<HTMLInputElement>(null);
  const hashtagsRef = useRef<HTMLInputElement>(null);

  const { tags, error: hashtagError } = parseHashtags(form.hashtags);
  // Same rule and wording as the server; only checked once both prices are filled in.
  const minOriginalPrice = form.price !== "" ? Number(form.price) + 1 : undefined;
  const tooBig = `${MAX_PRICE.toLocaleString("ko-KR")}원 이하로 입력해 주세요.`;
  const priceError = Number(form.price) > MAX_PRICE ? `가격은 ${tooBig}` : null;
  const stockError = Number(form.stock) > MAX_STOCK ? `재고는 ${MAX_STOCK.toLocaleString("ko-KR")}개 이하로 입력해 주세요.` : null;
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

  const dims = { useColor, useSize };
  const optionErrors = useOptions ? validateOptionRows(optionRows, dims) : [];
  const optionSummary = summarizeOptions(optionRows);
  const noDimension = useOptions && !useColor && !useSize;

  useEffect(() => {
    if (focusOption.current === null) return;
    optionInputs.current.get(focusOption.current)?.focus();
    focusOption.current = null;
  }, [optionRows]);

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

  function addOptionRow() {
    if (optionRows.length >= MAX_OPTIONS) return;
    const key = nextOptionKey.current++;
    focusOption.current = `${key}:${useColor ? "colorName" : "sizeLabel"}`;
    setOptionRows([...optionRows, emptyOptionRow(key)]);
  }

  function removeOptionRow(index: number) {
    const target = optionRows[index > 0 ? index - 1 : 1];
    focusOption.current = target ? `${target.key}:${useColor ? "colorName" : "sizeLabel"}` : null;
    setOptionRows(optionRows.filter((_, i) => i !== index));
  }

  function updateOptionRow(key: number, patch: Partial<OptionRow>) {
    setOptionRows((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    if (useOptions) {
      setOptionsTried(true);
      if (noDimension) {
        colorToggleRef.current?.focus();
        return;
      }
      for (let i = 0; i < optionRows.length; i++) {
        const field = OPTION_FIELD_ORDER.find((f) => optionErrors[i]?.[f]);
        if (field) {
          optionInputs.current.get(`${optionRows[i].key}:${field}`)?.focus();
          return;
        }
      }
    }
    // Never silent: put the cursor on the first invalid field (its message is already shown).
    const firstInvalid = !useOptions && priceError
      ? priceRef
      : !useOptions && originalPriceError
        ? originalPriceRef
        : !useOptions && stockError
          ? stockRef
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
          // With options the top-level price (lowest option) and stock (sum) are still sent, so a backend that
          // does not know options yet validates and stores the product the way it always did.
          price: useOptions ? optionSummary.lowest : Number(form.price),
          ...(!useOptions && form.originalPrice !== "" ? { originalPrice: Number(form.originalPrice) } : {}),
          ...(tags.length > 0 ? { hashtags: tags } : {}),
          // Both on purpose: an older backend only reads imageUrl, a newer one reads imageUrls.
          imageUrl: urls[0],
          imageUrls: urls,
          stock: useOptions ? Math.min(optionSummary.stock, MAX_STOCK) : Number(form.stock),
          category: form.category,
          ...(useOptions ? { options: buildOptionLines(optionRows, dims) } : {}),
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
    <div className={`${useOptions ? "max-w-2xl" : "max-w-md"} mx-auto flex flex-col gap-4`}>
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
        <fieldset className="flex flex-col gap-2">
          <legend className="sr-only">판매 옵션</legend>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={useOptions} onChange={(e) => setUseOptions(e.target.checked)} />
            옵션 사용
          </label>
          {useOptions && (
            <div className="flex flex-wrap gap-4 pl-6 text-sm">
              <label className="flex items-center gap-2">
                <input ref={colorToggleRef} type="checkbox" checked={useColor} onChange={(e) => setUseColor(e.target.checked)} />
                색상 옵션 사용
              </label>
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={useSize} onChange={(e) => setUseSize(e.target.checked)} />
                사이즈 옵션 사용
              </label>
            </div>
          )}
          {noDimension && (
            <p role="alert" className="pl-6 text-sm text-red-600">
              색상 또는 사이즈 중 하나는 사용해야 해요.
            </p>
          )}
        </fieldset>
        {useOptions ? (
          <fieldset className="flex flex-col gap-3">
            <legend className="sr-only">옵션 (최대 {MAX_OPTIONS}개)</legend>
            {optionRows.map((row, i) => {
              const n = i + 1;
              const errors = optionsTried ? (optionErrors[i] ?? {}) : {};
              const text = (field: OptionField, label: string, placeholder: string) => {
                const id = `product-option-${row.key}-${field}`;
                const message = errors[field];
                return (
                  <div>
                    <label htmlFor={id} className="sr-only">{`옵션 ${n} ${label}`}</label>
                    <input
                      id={id}
                      ref={(el) => {
                        if (el) optionInputs.current.set(`${row.key}:${field}`, el);
                        else optionInputs.current.delete(`${row.key}:${field}`);
                      }}
                      className="w-full border rounded px-3 py-2 text-sm"
                      placeholder={placeholder}
                      inputMode={field === "colorName" || field === "sizeLabel" ? undefined : "numeric"}
                      value={row[field]}
                      onChange={(e) => updateOptionRow(row.key, { [field]: e.target.value })}
                      aria-invalid={message ? true : undefined}
                      aria-describedby={message ? `${id}-error` : undefined}
                    />
                    {message && (
                      <p id={`${id}-error`} role="alert" className="mt-1 text-sm text-red-600">
                        {message}
                      </p>
                    )}
                  </div>
                );
              };
              return (
                <div key={row.key} className="flex flex-col gap-2 rounded border p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium">옵션 {n}</span>
                    {optionRows.length > 1 && (
                      <button
                        type="button"
                        onClick={() => removeOptionRow(i)}
                        aria-label={`옵션 ${n} 삭제`}
                        className="border rounded px-3 py-1 text-sm"
                      >
                        삭제
                      </button>
                    )}
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    {useColor && text("colorName", "색상 이름", "색상 이름")}
                    {useColor && (
                      <div className="flex items-center gap-2 text-sm">
                        <label htmlFor={`product-option-${row.key}-hex`} className="sr-only">{`옵션 ${n} 색상 선택`}</label>
                        <input
                          id={`product-option-${row.key}-hex`}
                          type="color"
                          value={row.colorHex || "#000000"}
                          onChange={(e) => updateOptionRow(row.key, { colorHex: e.target.value })}
                          className="h-9 w-12 shrink-0 cursor-pointer"
                        />
                        <span>{row.colorHex || "색 선택 안 함 (글자 칩)"}</span>
                        {row.colorHex && (
                          <button
                            type="button"
                            onClick={() => updateOptionRow(row.key, { colorHex: "" })}
                            aria-label={`옵션 ${n} 색상 지우기`}
                            className="border rounded px-2 py-1 text-xs"
                          >
                            지우기
                          </button>
                        )}
                      </div>
                    )}
                    {useSize && text("sizeLabel", "사이즈", "사이즈")}
                    {text("price", "가격", "가격")}
                    {text("originalPrice", "정가 (선택)", "정가 (선택)")}
                    {text("stock", "재고", "재고 수량")}
                  </div>
                </div>
              );
            })}
            <button
              type="button"
              onClick={addOptionRow}
              disabled={optionRows.length >= MAX_OPTIONS}
              className="self-start border rounded px-3 py-2 text-sm disabled:opacity-50"
            >
              옵션 추가
            </button>
            {optionRows.length >= MAX_OPTIONS && (
              <p role="status" className="text-sm text-gray-600">옵션은 최대 {MAX_OPTIONS}개까지 등록할 수 있어요.</p>
            )}
            <p role="status" className="text-sm text-gray-600">
              {`${optionSummary.count}개 옵션 · 최저가 ${optionSummary.lowest === null ? "-" : formatWon(optionSummary.lowest)} · 재고 합계 ${optionSummary.stock.toLocaleString("ko-KR")}개`}
            </p>
          </fieldset>
        ) : (
          <>
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
          </>
        )}
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
        {!useOptions && (
          <div className="flex flex-col">
            <label htmlFor="product-stock" className="sr-only">재고 수량</label>
            <input
              id="product-stock"
              ref={stockRef}
              className="border rounded px-3 py-2 text-sm"
              placeholder="재고 수량"
              type="number"
              min={0}
              max={MAX_STOCK}
              step={1}
              value={form.stock}
              onChange={(e) => setForm({ ...form, stock: e.target.value })}
              aria-invalid={stockError ? true : undefined}
              aria-describedby={stockError ? "product-stock-error" : undefined}
              required
            />
            <FieldError id="product-stock-error" message={stockError} />
          </div>
        )}
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
