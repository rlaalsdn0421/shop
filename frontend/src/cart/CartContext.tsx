"use client";

import { createContext, useContext, useEffect, useState, ReactNode } from "react";

export type CartItem = {
  productId: string;
  name: string;
  price: number;
  imageUrl: string;
  quantity: number;
  // Only for products sold by option. Items stored before options existed have neither and keep working.
  optionId?: string;
  optionLabel?: string;
};

type CartContextValue = {
  items: CartItem[];
  add: (item: Omit<CartItem, "quantity">, quantity?: number) => void;
  remove: (productId: string, optionId?: string) => void;
  setQuantity: (productId: string, quantity: number, optionId?: string) => void;
  clear: () => void;
  totalCount: number;
  totalAmount: number;
};

const CartContext = createContext<CartContextValue | null>(null);
const STORAGE_KEY = "shop-cart";

// A cart line is identified by product + option; no option is the same as a stored item without optionId.
const sameLine = (i: CartItem, productId: string, optionId?: string) =>
  i.productId === productId && (i.optionId ?? null) === (optionId ?? null);
export const cartLineKey = (i: Pick<CartItem, "productId" | "optionId">) => `${i.productId}:${i.optionId ?? ""}`;

export function CartProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<CartItem[]>([]);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      // localStorage isn't available during SSR, so a lazy useState initializer
      // would throw; this one-time hydration read has to happen in an effect.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      if (raw) setItems(JSON.parse(raw));
    } catch {
      // ignore malformed storage
    }
    setHydrated(true);
  }, []);

  useEffect(() => {
    if (hydrated) localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
  }, [items, hydrated]);

  const add: CartContextValue["add"] = (item, quantity = 1) => {
    setItems((prev) => {
      const existing = prev.find((i) => sameLine(i, item.productId, item.optionId));
      if (existing) {
        return prev.map((i) =>
          sameLine(i, item.productId, item.optionId)
            ? { ...i, quantity: i.quantity + quantity }
            : i
        );
      }
      return [...prev, { ...item, quantity }];
    });
  };

  const remove = (productId: string, optionId?: string) => {
    setItems((prev) => prev.filter((i) => !sameLine(i, productId, optionId)));
  };

  const setQuantity = (productId: string, quantity: number, optionId?: string) => {
    setItems((prev) =>
      quantity <= 0
        ? prev.filter((i) => !sameLine(i, productId, optionId))
        : prev.map((i) => (sameLine(i, productId, optionId) ? { ...i, quantity } : i))
    );
  };

  const clear = () => setItems([]);

  const totalCount = items.reduce((sum, i) => sum + i.quantity, 0);
  const totalAmount = items.reduce((sum, i) => sum + i.quantity * i.price, 0);

  return (
    <CartContext.Provider
      value={{ items, add, remove, setQuantity, clear, totalCount, totalAmount }}
    >
      {children}
    </CartContext.Provider>
  );
}

export function useCart() {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error("useCart must be used within CartProvider");
  return ctx;
}
