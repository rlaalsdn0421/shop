const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export class AuthError extends Error {
  constructor() {
    super("로그인이 필요합니다.");
  }
}

// Failed account withdrawal. Separate from AuthError on purpose: a 401 here means "wrong password", not "session expired".
export class WithdrawError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly code?: string
  ) {
    super(message);
  }
}

// Failed payment confirmation. `message` is already a short Korean sentence for the known server codes.
export class PaymentError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly code?: string
  ) {
    super(message);
  }
}

const PAYMENT_ERROR_MESSAGES: Record<string, string> = {
  PAYMENT_NOT_CONFIRMED: "아직 결제가 확인되지 않았어요. 잠시 뒤 다시 확인해 주세요.",
  PAYMENT_AMOUNT_MISMATCH: "결제 금액이 주문 금액과 달라 확인이 필요해요. 고객센터로 문의해 주세요.",
  PAYMENT_NEEDS_REVIEW: "결제는 확인됐지만 재고 문제로 주문을 확정하지 못했어요. 확인 후 취소·환불을 안내해 드릴게요.",
  PAYMENT_PROVIDER_UNAVAILABLE: "결제 확인이 지연되고 있어요. 잠시 뒤 다시 확인해 주세요.",
  PAYMENT_NOT_CONFIGURED: "결제 준비 중이에요. 잠시 후 다시 시도해 주세요.",
};

// Failed order cancel / admin order action. `message` is already a short Korean sentence for the known server codes.
export class OrderActionError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly code?: string
  ) {
    super(message);
  }
}

const CANCEL_UNAVAILABLE = "취소 요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
const ORDER_ACTION_ERROR_MESSAGES: Record<string, string> = {
  ORDER_NOT_CANCELLABLE: "배송이 시작되어 바로 취소할 수 없어요. 교환·환불은 연락처로 신청해 주세요.",
  CANCEL_PROVIDER_UNAVAILABLE: CANCEL_UNAVAILABLE,
  PAYMENT_NOT_CONFIGURED: CANCEL_UNAVAILABLE,
  INVALID_ORDER_STATE: "주문 상태가 바뀌어 처리할 수 없어요. 목록을 새로고침해 확인해 주세요.",
};

export type Product = {
  id: string;
  name: string;
  price: number;
  imageUrl: string;
  // Optional on purpose: an older backend does not send these yet (see lib/price.ts).
  originalPrice?: number | null;
  discountRate?: number | null;
  hashtags?: string[];
};

export type ProductPage = {
  items: Product[];
  hasMore: boolean;
};

export type ProductDetail = Product & {
  description: string;
  stock: number;
  // Optional on purpose: the detail endpoint does not send it yet (breadcrumb shows only when present).
  category?: string | null;
};

export type AdminProduct = {
  id: string;
  name: string;
  price: number;
  stock: number;
  createdAt: string;
};

export type OrderItem = {
  id: string;
  quantity: number;
  price: number;
  productName: string;
};

export type Order = {
  id: string;
  totalAmount: number; // includes shippingFee
  shippingFee: number; // an older backend response may omit it; treat as 0
  // Optional on purpose: an older backend does not send the payment fields yet.
  status?: string;
  expiresAt?: string | null;
  paidAt?: string | null;
  shippedAt?: string | null;
  deliveredAt?: string | null;
  cancelledAt?: string | null;
  customerName: string;
  customerPhone: string;
  customerAddress: string;
  items: OrderItem[];
};

export type OrderSummary = {
  id: string;
  createdAt: string;
  totalAmount: number;
  status: string;
  expiresAt?: string | null;
  itemCount: number;
  firstItemName: string;
};

export type OrderSummaryPage = {
  orders: OrderSummary[];
  nextCursor: string | null;
};

export type AdminOrder = {
  id: string;
  status: string;
  totalAmount: number; // includes shippingFee
  shippingFee: number;
  createdAt: string;
  paidAt: string | null;
  shippedAt: string | null;
  deliveredAt: string | null;
  customerName: string;
  customerPhone: string;
  customerAddress: string;
  itemCount: number;
  firstItemName: string;
};

export type AdminOrderPage = {
  orders: AdminOrder[];
  nextCursor: string | null;
};

export type Review = {
  id: string;
  reviewerName: string;
  rating: number;
  comment: string;
  createdAt: string;
};

export type ReviewList = {
  averageRating: number | null;
  reviewCount: number;
  reviews: Review[];
};

export type LoginResponse = {
  token: string;
  role: "ADMIN" | "SELLER" | "USER";
  username: string;
};

async function apiFetch(path: string, init?: RequestInit) {
  return fetch(`${BACKEND_URL}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
    cache: "no-store",
  });
}

export const PRODUCT_SORTS = [
  "newest",
  "popular",
  "price_asc",
  "price_desc",
  "reviews",
  "rating",
  "sales",
  "discount",
] as const;
export type ProductSort = (typeof PRODUCT_SORTS)[number];

export const BEST_PERIODS = ["realtime", "weekly", "monthly"] as const;
export type BestPeriod = (typeof BEST_PERIODS)[number];

export async function listProducts(
  category?: string,
  page = 0,
  size = 8,
  sort?: ProductSort
): Promise<ProductPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (category) params.set("category", category);
  if (sort) params.set("sort", sort);
  const res = await apiFetch(`/api/products?${params}`);
  if (!res.ok) throw new Error("상품 목록을 불러오지 못했습니다.");
  return res.json();
}

export async function listBestProducts(period: BestPeriod, size = 4): Promise<Product[]> {
  const params = new URLSearchParams({ period, size: String(size) });
  const res = await apiFetch(`/api/products/best?${params}`);
  const data = await res.json().catch(() => null);
  if (!res.ok || !Array.isArray(data?.items)) throw new Error("베스트 상품을 불러오지 못했습니다.");
  return data.items;
}

export async function getProduct(id: string): Promise<ProductDetail | null> {
  const res = await apiFetch(`/api/products/${id}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error("상품 정보를 불러오지 못했습니다.");
  return res.json();
}

export async function listAdminProducts(token: string): Promise<AdminProduct[]> {
  const res = await apiFetch("/api/admin/products", {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (res.status === 401 || res.status === 403) throw new AuthError();
  if (!res.ok) throw new Error("상품 목록을 불러오지 못했습니다.");
  return res.json();
}

export async function getOrder(id: string, token: string): Promise<Order | null> {
  const res = await apiFetch(`/api/orders/${encodeURIComponent(id)}`, {
    headers: { Authorization: `Bearer ${token}` },
    // A hung request would otherwise leave the page on "loading" forever.
    signal: AbortSignal.timeout(15_000),
  });
  if (res.status === 401 || res.status === 403) throw new AuthError();
  if (res.status === 404) return null;
  if (!res.ok) throw new Error("주문 정보를 불러오지 못했습니다.");
  return res.json();
}

// Returns the order status the server confirmed. 401/403 is a session problem (AuthError), anything else a PaymentError.
export async function completeOrderPayment(id: string, token: string): Promise<string> {
  const res = await apiFetch(`/api/orders/${encodeURIComponent(id)}/payment/complete`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(30_000),
  });
  if (res.status === 401 || res.status === 403) throw new AuthError();
  const data = await res.json().catch(() => null);
  if (res.ok && typeof data?.status === "string") return data.status;
  const code = typeof data?.code === "string" ? data.code : undefined;
  const serverMessage = typeof data?.error === "string" ? data.error : typeof data?.message === "string" ? data.message : null;
  throw new PaymentError(
    res.status,
    (code && PAYMENT_ERROR_MESSAGES[code]) || serverMessage || "결제 확인에 실패했어요. 잠시 뒤 다시 확인해 주세요.",
    code
  );
}

export async function listMyOrders(token: string, cursor?: string | null): Promise<OrderSummaryPage> {
  const query = cursor ? `&cursor=${encodeURIComponent(cursor)}` : "";
  const res = await apiFetch(`/api/orders?size=10${query}`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(15_000),
  });
  if (res.status === 401 || res.status === 403) throw new AuthError();
  const data = await res.json().catch(() => null);
  if (!res.ok || !Array.isArray(data?.orders)) throw new Error("주문 내역을 불러오지 못했습니다.");
  return { orders: data.orders, nextCursor: data.nextCursor ?? null };
}

// POST that returns the order status the server settled on. 401/403 is a session problem (AuthError), anything else an OrderActionError.
// The timeout is long on purpose: cancel and refund call the payment provider before answering.
async function postOrderAction(path: string, token: string, body?: object): Promise<string> {
  const res = await apiFetch(path, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(30_000),
  });
  if (res.status === 401 || res.status === 403) throw new AuthError();
  const data = await res.json().catch(() => null);
  if (res.ok && typeof data?.status === "string") return data.status;
  const code = typeof data?.code === "string" ? data.code : undefined;
  throw new OrderActionError(
    res.status,
    (code && ORDER_ACTION_ERROR_MESSAGES[code]) ||
      (res.status === 404 ? "주문을 찾을 수 없어요." : "요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요."),
    code
  );
}

const orderPath = (id: string) => `/api/orders/${encodeURIComponent(id)}`;
const adminOrderPath = (id: string) => `/api/admin/orders/${encodeURIComponent(id)}`;

export const cancelOrder = (id: string, token: string, reason?: string) =>
  postOrderAction(`${orderPath(id)}/cancel`, token, reason?.trim() ? { reason: reason.trim() } : {});

export async function listAdminOrders(token: string, status?: string, cursor?: string | null): Promise<AdminOrderPage> {
  const params = new URLSearchParams({ size: "20" });
  if (status) params.set("status", status);
  if (cursor) params.set("cursor", cursor);
  const res = await apiFetch(`/api/admin/orders?${params}`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(15_000),
  });
  if (res.status === 401 || res.status === 403) throw new AuthError();
  const data = await res.json().catch(() => null);
  if (!res.ok || !Array.isArray(data?.orders)) throw new Error("주문 목록을 불러오지 못했습니다.");
  return { orders: data.orders, nextCursor: data.nextCursor ?? null };
}

export const shipOrder = (id: string, token: string) => postOrderAction(`${adminOrderPath(id)}/ship`, token);
export const deliverOrder = (id: string, token: string) => postOrderAction(`${adminOrderPath(id)}/deliver`, token);
export const refundOrder = (id: string, token: string, reason: string) =>
  postOrderAction(`${adminOrderPath(id)}/refund`, token, { reason });

export async function withdrawAccount(token: string, password: string): Promise<void> {
  const res = await apiFetch("/api/users/me", {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({ password }),
    signal: AbortSignal.timeout(15_000),
  });
  if (res.ok) return;
  const data = await res.json().catch(() => null);
  throw new WithdrawError(
    res.status,
    (typeof data?.error === "string" && data.error) || "회원 탈퇴에 실패했습니다.",
    typeof data?.code === "string" ? data.code : undefined
  );
}

export async function listReviews(productId: string): Promise<ReviewList | null> {
  const res = await apiFetch(`/api/products/${productId}/reviews`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error("리뷰를 불러오지 못했습니다.");
  return res.json();
}

export type ChatResponse = {
  answer: string;
  suggestions: string[];
};

export async function askChat(message: string): Promise<ChatResponse> {
  const res = await apiFetch("/api/chat", {
    method: "POST",
    body: JSON.stringify({ message }),
  });
  const data = await res.json().catch(() => null);
  const valid =
    typeof data?.answer === "string" &&
    data.answer.trim() !== "" &&
    (data.suggestions === undefined ||
      (Array.isArray(data.suggestions) && data.suggestions.every((s: unknown) => typeof s === "string")));
  if (!res.ok || !valid) throw new Error((!res.ok && data?.error) || "답변을 가져오지 못했습니다.");
  return { answer: data.answer, suggestions: data.suggestions ?? [] };
}
