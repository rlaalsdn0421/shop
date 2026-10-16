import { MAX_PRICE } from "@/lib/price";

// Mirrors the server's ProductValidation.normalizeOptions so mistakes show up before the request.
export const MAX_OPTIONS = 50;
export const MAX_OPTION_NAME = 30;

export type OptionRow = {
  key: number;
  colorName: string;
  colorHex: string; // "" = no swatch colour (the product page then shows a text chip)
  sizeLabel: string;
  price: string;
  originalPrice: string;
  stock: string;
};

export type OptionField = "colorName" | "sizeLabel" | "price" | "originalPrice" | "stock";
export type OptionRowErrors = Partial<Record<OptionField, string>>;
export type OptionDims = { useColor: boolean; useSize: boolean };

// Same cleaning as the server: Unicode NFC, trim, runs of spaces become one.
export const cleanOptionText = (v: string) => v.normalize("NFC").trim().replace(/ {2,}/g, " ");

const isCount = (v: string) => /^\d+$/.test(v.trim());
const tooBig = (what: string, unit: string) => `${what}는 ${MAX_PRICE.toLocaleString("ko-KR")}${unit} 이하로 입력해 주세요.`;

function nameError(cleaned: string, empty: string): string | null {
  if (cleaned === "") return empty;
  // Only the plain space may stay inside; control, zero-width and other separator characters are refused.
  if (/[\p{C}\p{Z}]/u.test(cleaned.replace(/ /g, ""))) return "옵션 이름에 쓸 수 없는 문자가 있어요.";
  if (cleaned.length > MAX_OPTION_NAME) return `색상 이름과 사이즈는 ${MAX_OPTION_NAME}자 이하로 입력해 주세요.`;
  if (!/[\p{L}\p{N}]/u.test(cleaned)) return "옵션 이름에는 글자나 숫자가 하나는 있어야 해요.";
  return null;
}

export function validateOptionRows(rows: OptionRow[], { useColor, useSize }: OptionDims): OptionRowErrors[] {
  const seen = new Set<string>();
  return rows.map((row) => {
    const errors: OptionRowErrors = {};
    const color = cleanOptionText(row.colorName);
    const size = cleanOptionText(row.sizeLabel);
    if (useColor) {
      const e = nameError(color, "색상 이름을 입력해 주세요.");
      if (e) errors.colorName = e;
    }
    if (useSize) {
      const e = nameError(size, "사이즈를 입력해 주세요.");
      if (e) errors.sizeLabel = e;
    }
    if (!isCount(row.price)) errors.price = "가격은 0 이상의 정수로 입력해 주세요.";
    else if (Number(row.price) > MAX_PRICE) errors.price = tooBig("가격", "원");
    const original = row.originalPrice.trim();
    if (original !== "") {
      if (!isCount(original)) errors.originalPrice = "정가는 정수로 입력해 주세요.";
      else if (Number(original) > MAX_PRICE) errors.originalPrice = tooBig("정가", "원");
      else if (!errors.price && !(Number(original) > Number(row.price))) errors.originalPrice = "정가는 판매가보다 커야 해요.";
    }
    if (!isCount(row.stock)) errors.stock = "재고는 0 이상의 정수로 입력해 주세요.";
    else if (Number(row.stock) > MAX_PRICE) errors.stock = tooBig("재고", "개");
    // A repeated combination is flagged on the later row; rows with a name problem are reported for that first.
    if (!errors.colorName && !errors.sizeLabel) {
      const combo = JSON.stringify([useColor ? color.toLowerCase() : "", useSize ? size.toLowerCase() : ""]);
      if (seen.has(combo)) errors[useColor ? "colorName" : "sizeLabel"] = "같은 색상과 사이즈 조합의 옵션이 이미 있어요.";
      seen.add(combo);
    }
    return errors;
  });
}

// Field order on screen, used to focus the first problem.
export const OPTION_FIELD_ORDER: OptionField[] = ["colorName", "sizeLabel", "price", "originalPrice", "stock"];

// Count, lowest price and stock total over the rows that already hold valid numbers (live note while typing).
export function summarizeOptions(rows: OptionRow[]) {
  const prices = rows.filter((r) => isCount(r.price)).map((r) => Number(r.price));
  const stock = rows.filter((r) => isCount(r.stock)).reduce((sum, r) => sum + Number(r.stock), 0);
  return { count: rows.length, lowest: prices.length > 0 ? Math.min(...prices) : null, stock };
}

// Request lines: colour fields only when colours are used, size only when sizes are used, originalPrice only when set.
export function buildOptionLines(rows: OptionRow[], { useColor, useSize }: OptionDims) {
  return rows.map((r) => ({
    ...(useColor ? { colorName: cleanOptionText(r.colorName), ...(r.colorHex ? { colorHex: r.colorHex } : {}) } : {}),
    ...(useSize ? { sizeLabel: cleanOptionText(r.sizeLabel) } : {}),
    price: Number(r.price),
    ...(r.originalPrice.trim() !== "" ? { originalPrice: Number(r.originalPrice) } : {}),
    stock: Number(r.stock),
  }));
}
