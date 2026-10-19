"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/auth/AuthContext";
import {
  AuthError,
  ProductEditError,
  STOCK_CHANGED,
  updateAdminProduct,
  type ProductDetail,
  type ProductEditBody,
} from "@/lib/api";
import { CATEGORIES } from "@/lib/categories";
import { parseHashtags } from "@/lib/hashtags";
import { validateOptionRows } from "@/lib/adminOptions";
import { optionLabel, optionsOf } from "@/lib/options";

const MAX_IMAGES = 10;
// The server rejects any other scheme.
const HTTP_URL = /^https?:\/\//i;

// One price/original price/stock line: the single line of a product without options, or one option.
type PriceRow = {
  id: string | null; // option id, null for the product itself
  label: string;
  price: string;
  originalPrice: string;
  stock: string;
  expectedStock: number; // stock as loaded from the server, sent back for the 409 check
};

// Reuses the option rules (same wording as the create form). Names are not editable here, so the rows get a
// distinct dummy size just to pass the name / duplicate checks.
const checkRows = (rows: PriceRow[]) =>
  validateOptionRows(
    rows.map((r, i) => ({ key: i, colorName: "", colorHex: "", sizeLabel: String(i + 1), price: r.price, originalPrice: r.originalPrice, stock: r.stock })),
    { useColor: false, useSize: true }
  );

function FieldError({ id, message }: { id: string; message?: string | null }) {
  return message ? (
    <p id={id} role="alert" className="mt-1 text-sm text-red-600">
      {message}
    </p>
  ) : null;
}

type Props = { product: ProductDetail; token: string; onReload: () => void };

export function AdminProductEditForm({ product, token, onReload }: Props) {
  const router = useRouter();
  const { logout } = useAuth();
  const options = optionsOf(product.options);
  const hasOptions = options.length > 0;
  const [name, setName] = useState(product.name);
  const [description, setDescription] = useState(product.description);
  const [category, setCategory] = useState(product.category ?? "");
  const [hashtags, setHashtags] = useState((product.hashtags ?? []).join(" "));
  // Rows keep a stable key so deleting a middle row does not hand its input to the next one.
  const initialImages = product.images && product.images.length > 0 ? product.images : [product.imageUrl];
  const nextRowKey = useRef(initialImages.length);
  const [imageRows, setImageRows] = useState(() => initialImages.map((url, key) => ({ key, url })));
  const [rows, setRows] = useState<PriceRow[]>(() =>
    hasOptions
      ? options.map((o) => ({
          id: o.id,
          label: optionLabel(o),
          price: String(o.price),
          originalPrice: o.originalPrice == null ? "" : String(o.originalPrice),
          stock: String(o.stock),
          expectedStock: o.stock,
        }))
      : [
          {
            id: null,
            label: "",
            price: String(product.price),
            originalPrice: product.originalPrice == null ? "" : String(product.originalPrice),
            stock: String(product.stock),
            expectedStock: product.stock,
          },
        ]
  );
  // Field errors stay hidden until the first save attempt.
  const [tried, setTried] = useState(false);
  const [error, setError] = useState("");
  // canReload: offer "새로고침". stale: the loaded values can no longer be saved (stock moved, or the save may
  // already have gone through), so saving stays off until the reload.
  const [canReload, setCanReload] = useState(false);
  const [stale, setStale] = useState(false);
  const [saved, setSaved] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  // The ref blocks a second submit in the same tick, before the disabled button re-renders.
  const submitLock = useRef(false);
  // Resolves the element id to focus from the rows as they are after the update (never from a stale snapshot).
  const focusAfter = useRef<((rows: { key: number }[]) => string | undefined) | null>(null);

  useEffect(() => {
    if (!focusAfter.current) return;
    const id = focusAfter.current(imageRows);
    focusAfter.current = null;
    if (id) document.getElementById(id)?.focus();
  }, [imageRows]);

  const { tags, error: hashtagError } = parseHashtags(hashtags);
  const nameError = name.trim() === "" ? "상품명을 입력해 주세요." : name.length > 200 ? "상품명은 200자 이하로 입력해 주세요." : null;
  const descriptionError =
    description.trim() === "" ? "상품 설명을 입력해 주세요." : description.length > 2000 ? "상품 설명은 2000자 이하로 입력해 주세요." : null;
  const rowErrors = checkRows(rows);
  // Trimmed once; blank extra rows are dropped on save, a repeated address is flagged on the later row.
  const imageUrls = imageRows.map((row) => row.url.trim());
  const duplicateRow = (i: number) => imageUrls[i] !== "" && imageUrls.indexOf(imageUrls[i]) < i;
  const badScheme = (i: number) => imageUrls[i] !== "" && !HTTP_URL.test(imageUrls[i]);
  const firstImageProblem = imageUrls.findIndex((_, i) => badScheme(i) || duplicateRow(i));
  const firstImageError = imageUrls[0] === "" ? "첫 번째 이미지 주소를 입력해 주세요." : null;

  const rowId = (i: number, field: string) => (hasOptions ? `edit-option-${i}-${field}` : `edit-${field}`);
  const show = <T,>(v: T) => (tried ? v : undefined);

  function updateRow(i: number, patch: Partial<PriceRow>) {
    setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  }

  function addImageRow() {
    const key = nextRowKey.current++;
    focusAfter.current = (rs) => `edit-image-${rs[rs.length - 1].key}`;
    setImageRows((rs) => (rs.length >= MAX_IMAGES ? rs : [...rs, { key, url: "" }]));
  }

  function removeImageRow(key: number) {
    // Set by the updater, which has run by the time the focus callback does (after the re-render).
    let removedAt = 0;
    focusAfter.current = (rs) => (rs.length > 0 ? `edit-image-${rs[Math.max(removedAt - 1, 0)].key}` : undefined);
    setImageRows((rs) => {
      removedAt = rs.findIndex((r) => r.key === key);
      return rs.filter((r) => r.key !== key);
    });
  }

  function changeImageUrl(key: number, url: string) {
    setImageRows((rs) => rs.map((r) => (r.key === key ? { ...r, url } : r)));
  }

  function moveImageRow(key: number, dir: -1 | 1) {
    // Focus stays on the moved row's button; at the new end that button is disabled, so the other one takes it.
    focusAfter.current = (rs) => {
      const i = rs.findIndex((r) => r.key === key);
      const want = dir < 0 ? (i > 0 ? "up" : "down") : i < rs.length - 1 ? "down" : "up";
      return `edit-image-${key}-${want}`;
    };
    setImageRows((rs) => {
      const i = rs.findIndex((r) => r.key === key);
      const j = i + dir;
      if (i < 0 || j < 0 || j >= rs.length) return rs;
      const next = [...rs];
      [next[i], next[j]] = [next[j], next[i]];
      return next;
    });
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (submitLock.current) return;
    setTried(true);
    setError("");
    setCanReload(false);
    // Never silent: put the cursor on the first invalid field (its message is shown now).
    const firstRow = rowErrors.findIndex((r) => r.price || r.originalPrice || r.stock);
    const firstRowField = firstRow >= 0 ? (["price", "originalPrice", "stock"] as const).find((f) => rowErrors[firstRow][f]) : undefined;
    const firstInvalid = nameError
      ? "edit-name"
      : descriptionError
        ? "edit-description"
        : hashtagError
          ? "edit-hashtags"
          : firstImageError
            ? `edit-image-${imageRows[0].key}`
            : firstImageProblem >= 0
              ? `edit-image-${imageRows[firstImageProblem].key}`
              : firstRowField
                ? rowId(firstRow, firstRowField)
                : null;
    if (firstInvalid) {
      document.getElementById(firstInvalid)?.focus();
      return;
    }
    submitLock.current = true;
    setSubmitting(true);
    const common = {
      name,
      description,
      category: category || null,
      hashtags: tags,
      imageUrls: imageUrls.filter(Boolean),
    };
    const line = (r: PriceRow) => ({
      price: Number(r.price),
      originalPrice: r.originalPrice.trim() === "" ? null : Number(r.originalPrice),
      stock: Number(r.stock),
      expectedStock: r.expectedStock,
    });
    const body: ProductEditBody = hasOptions
      ? { ...common, options: rows.map((r) => ({ id: r.id as string, ...line(r) })) }
      : { ...common, ...line(rows[0]) };
    try {
      await updateAdminProduct(product.id, body, token);
      // Success: stay locked (button disabled) until the route changes, so no double PUT.
      setSaved(true);
      router.push("/admin/products");
      router.refresh();
    } catch (err) {
      if (err instanceof ProductEditError) {
        setError(err.message);
        // 400 (e.g. the option list changed) and 404 are fixed by loading the product again.
        setCanReload(err.status === 400 || err.status === 404 || err.code === STOCK_CHANGED);
        setStale(err.code === STOCK_CHANGED);
      } else if (err instanceof AuthError) {
        setError(err.message);
        // An expired or invalid token: logging out makes useRequireRole send the user to /login.
        logout();
      } else {
        // No server answer: the save may have gone through, and a retry with the old expectedStock would answer 409,
        // so only a reload helps.
        setError("저장됐는지 확인하지 못했어요. 새로고침해서 확인해 주세요.");
        setCanReload(true);
        setStale(true);
      }
      submitLock.current = false;
      setSubmitting(false);
    }
  }

  const rowInput = (i: number, field: "price" | "originalPrice" | "stock", label: string) => {
    const id = rowId(i, field);
    const message = show(rowErrors[i][field]);
    return (
      <div>
        <label htmlFor={id} className={hasOptions ? "sr-only" : "block text-sm font-medium mb-1"}>
          {hasOptions ? `${rows[i].label || `옵션 ${i + 1}`} ${label}` : label}
        </label>
        <input
          id={id}
          type="number"
          inputMode="numeric"
          min={0}
          step={1}
          className="w-full min-w-24 border rounded px-3 py-2 text-sm"
          value={rows[i][field]}
          onChange={(e) => updateRow(i, { [field]: e.target.value })}
          // A stray mouse wheel must not change the number.
          onWheel={(e) => e.currentTarget.blur()}
          aria-invalid={message ? true : undefined}
          aria-describedby={message ? `${id}-error` : undefined}
        />
        <FieldError id={`${id}-error`} message={message} />
      </div>
    );
  };

  const fieldLabel = "block text-sm font-medium mb-1";
  const inputClass = "w-full border rounded px-3 py-2 text-sm";

  return (
    <div className="max-w-2xl mx-auto flex flex-col gap-4">
      <h1 className="text-xl font-bold">상품 수정</h1>
      <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-4">
        <div>
          <label htmlFor="edit-name" className={fieldLabel}>상품명</label>
          <input
            id="edit-name"
            className={inputClass}
            value={name}
            onChange={(e) => setName(e.target.value)}
            aria-invalid={show(nameError) ? true : undefined}
            aria-describedby={show(nameError) ? "edit-name-error" : undefined}
          />
          <FieldError id="edit-name-error" message={show(nameError)} />
        </div>
        <div>
          <label htmlFor="edit-description" className={fieldLabel}>상품 설명</label>
          <textarea
            id="edit-description"
            className={inputClass}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            aria-invalid={show(descriptionError) ? true : undefined}
            aria-describedby={show(descriptionError) ? "edit-description-error" : undefined}
          />
          <FieldError id="edit-description-error" message={show(descriptionError)} />
        </div>
        <div>
          <label htmlFor="edit-category" className={fieldLabel}>카테고리</label>
          <select id="edit-category" className={inputClass} value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">카테고리 선택 안 함</option>
            {/* A stored category outside the list stays visible instead of silently showing the first choice. */}
            {category && !CATEGORIES.includes(category) && <option value={category}>{category}</option>}
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="edit-hashtags" className={fieldLabel}>해시태그 (최대 3개, 공백으로 구분)</label>
          <input
            id="edit-hashtags"
            className={inputClass}
            value={hashtags}
            onChange={(e) => setHashtags(e.target.value)}
            aria-invalid={show(hashtagError) ? true : undefined}
            aria-describedby={show(hashtagError) ? "edit-hashtags-error" : undefined}
          />
          <FieldError id="edit-hashtags-error" message={show(hashtagError)} />
        </div>
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium mb-1">상품 이미지 (첫 번째가 대표 이미지, 최대 {MAX_IMAGES}장)</legend>
          <p className="text-sm text-gray-600">위로·아래로 버튼으로 순서를 바꿀 수 있어요.</p>
          {imageRows.map((row, i) => {
            const message =
              show(i === 0 ? firstImageError : null) ??
              (show(badScheme(i))
                ? "이미지 주소는 http:// 또는 https://로 시작해야 해요."
                : show(duplicateRow(i))
                  ? "이미 입력한 이미지 주소예요."
                  : null);
            return (
              <div key={row.key}>
                <div className="flex gap-2">
                  <label htmlFor={`edit-image-${row.key}`} className="sr-only">{`이미지 주소 ${i + 1}`}</label>
                  <input
                    id={`edit-image-${row.key}`}
                    className="min-w-0 flex-1 border rounded px-3 py-2 text-sm"
                    placeholder={i === 0 ? "이미지 주소 (대표 이미지)" : `이미지 주소 ${i + 1}`}
                    value={row.url}
                    onChange={(e) => changeImageUrl(row.key, e.target.value)}
                    maxLength={2000}
                    aria-invalid={message ? true : undefined}
                    aria-describedby={message ? `edit-image-${row.key}-error` : undefined}
                  />
                  {imageRows.length > 1 && (
                    <>
                      <button
                        type="button"
                        id={`edit-image-${row.key}-up`}
                        onClick={() => moveImageRow(row.key, -1)}
                        disabled={i === 0}
                        aria-label={`이미지 ${i + 1} 위로 이동`}
                        className="shrink-0 border rounded px-3 text-sm disabled:opacity-50"
                      >
                        위로
                      </button>
                      <button
                        type="button"
                        id={`edit-image-${row.key}-down`}
                        onClick={() => moveImageRow(row.key, 1)}
                        disabled={i === imageRows.length - 1}
                        aria-label={`이미지 ${i + 1} 아래로 이동`}
                        className="shrink-0 border rounded px-3 text-sm disabled:opacity-50"
                      >
                        아래로
                      </button>
                      <button
                        type="button"
                        onClick={() => removeImageRow(row.key)}
                        aria-label={`이미지 주소 ${i + 1} 삭제`}
                        className="shrink-0 border rounded px-3 text-sm"
                      >
                        삭제
                      </button>
                    </>
                  )}
                </div>
                <FieldError id={`edit-image-${row.key}-error`} message={message} />
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
        {hasOptions ? (
          <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="text-left text-sm font-medium mb-1">옵션별 가격·재고 (옵션 이름은 바꿀 수 없어요)</caption>
            <thead>
              <tr className="text-left text-gray-500 border-b">
                <th scope="col" className="py-2">옵션</th>
                <th scope="col" className="py-2">가격</th>
                <th scope="col" className="py-2">정가 (선택)</th>
                <th scope="col" className="py-2">재고</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={r.id} className="border-b align-top">
                  <th scope="row" className="py-2 pr-2 text-left font-normal whitespace-nowrap">{r.label || `옵션 ${i + 1}`}</th>
                  <td className="py-2 pr-2">{rowInput(i, "price", "가격")}</td>
                  <td className="py-2 pr-2">{rowInput(i, "originalPrice", "정가 (선택)")}</td>
                  <td className="py-2">{rowInput(i, "stock", "재고")}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        ) : (
          <>
            {rowInput(0, "price", "가격")}
            {rowInput(0, "originalPrice", "정가 (선택)")}
            {rowInput(0, "stock", "재고 수량")}
          </>
        )}
        {saved && <p role="status" className="text-sm text-green-700">저장했어요. 상품 목록으로 돌아가요.</p>}
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        {canReload && (
          <button type="button" onClick={onReload} className="self-start border rounded px-3 py-2 text-sm">
            새로고침
          </button>
        )}
        <button type="submit" disabled={submitting || stale} className="bg-black text-white rounded py-3 disabled:opacity-50">
          {submitting ? "저장 중..." : "저장하기"}
        </button>
      </form>
    </div>
  );
}
