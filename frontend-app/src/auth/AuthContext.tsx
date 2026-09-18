"use client";

import { createContext, useContext, useEffect, useState, ReactNode } from "react";

export type Role = "ADMIN" | "SELLER" | "USER";

type Session = {
  token: string;
  email: string;
  role: Role;
  expiresAt: number; // epoch ms
};

type AuthContextValue = {
  session: Session | null;
  ready: boolean;
  login: (token: string, email: string, role: Role) => void;
  logout: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);
const STORAGE_KEY = "shop-app-auth";
const TOKEN_TTL_MS = 2 * 60 * 60 * 1000; // 2 hours, matches the backend's JWT expiry

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed: Session = JSON.parse(raw);
        if (parsed.expiresAt > Date.now()) {
          // eslint-disable-next-line react-hooks/set-state-in-effect
          setSession(parsed);
        } else {
          localStorage.removeItem(STORAGE_KEY);
        }
      }
    } catch {
      // ignore malformed storage
    }
    setHydrated(true);
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    else localStorage.removeItem(STORAGE_KEY);
  }, [session, hydrated]);

  function login(token: string, email: string, role: Role) {
    setSession({ token, email, role, expiresAt: Date.now() + TOKEN_TTL_MS });
  }

  function logout() {
    setSession(null);
  }

  return (
    <AuthContext.Provider value={{ session, ready: hydrated, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
