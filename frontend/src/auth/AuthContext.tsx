"use client";

import { createContext, useContext, useEffect, useState, ReactNode } from "react";
import { useRouter } from "next/navigation";
import { clearOrderAttempt } from "@/lib/orderAttempt";

export type Role = "ADMIN" | "SELLER" | "USER";

type Session = {
  token: string;
  username: string;
  role: Role;
  expiresAt: number; // epoch ms
};

type AuthContextValue = {
  session: Session | null;
  ready: boolean;
  login: (token: string, username: string, role: Role) => void;
  logout: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);
const STORAGE_KEY = "shop-auth";
const TOKEN_TTL_MS = 2 * 60 * 60 * 1000; // 2 hours, matches the backend's JWT expiry

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed: Session = JSON.parse(raw);
        // Sessions saved before the username change have no `username`; treat them as logged out.
        if (parsed.username && parsed.expiresAt > Date.now()) {
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

  function login(token: string, username: string, role: Role) {
    setSession({ token, username, role, expiresAt: Date.now() + TOKEN_TTL_MS });
  }

  function logout() {
    // The stored order retry key belongs to this login; withdrawal also goes through here.
    clearOrderAttempt();
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

// Redirects to /login once localStorage has been checked and there's no
// session, or the session's role isn't allowed. Returns the session so
// callers can render nothing until `ready` (avoids a flash of protected
// content before the redirect fires).
// Pass enabled=false while the caller navigates somewhere else itself (e.g. after account withdrawal),
// so this redirect cannot override it.
export function useRequireRole(allowed: Role[], enabled = true) {
  const { session, ready } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!ready || !enabled) return;
    if (!session || !allowed.includes(session.role)) {
      router.replace("/login");
    }
    // `allowed` intentionally excluded: callers pass a fresh array literal
    // each render, which would otherwise retrigger this effect every time.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, session, router, enabled]);

  return { session, ready };
}
