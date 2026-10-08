const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export class AuthError extends Error {
  constructor() {
    super("로그인이 필요합니다.");
  }
}

export type Product = {
  id: string;
  name: string;
  price: number;
  imageUrl: string;
};

export type ProductPage = {
  items: Product[];
  hasMore: boolean;
};

export type ProductDetail = Product & {
  description: string;
  stock: number;
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
  totalAmount: number;
  customerName: string;
  customerPhone: string;
  customerAddress: string;
  items: OrderItem[];
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

export async function listProducts(category?: string, page = 0, size = 8): Promise<ProductPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (category) params.set("category", category);
  const res = await apiFetch(`/api/products?${params}`);
  if (!res.ok) throw new Error("상품 목록을 불러오지 못했습니다.");
  return res.json();
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

export async function getOrder(id: string): Promise<Order | null> {
  const res = await apiFetch(`/api/orders/${id}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error("주문 정보를 불러오지 못했습니다.");
  return res.json();
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
