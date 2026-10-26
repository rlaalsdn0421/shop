"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, ReactNode } from "react";
import { useAuth } from "@/auth/AuthContext";
import { addWish, AuthError, getWishlistIds, removeWish, WishlistError } from "@/lib/api";

type WishlistContextValue = {
  // Ids of the wished products. Empty while logged out or while they are loading.
  ids: Set<string>;
  // Auth has been checked and, when logged in, the ids have been loaded. Hearts stay disabled until then.
  ready: boolean;
  // Logged in and the ids could not be loaded (even after one automatic retry). `ready` stays false.
  loadFailed: boolean;
  isWished: (productId: string) => boolean;
  // Optimistic: flips at once. A server answer that refuses rolls it back and sets `lastError`; an unknown outcome
  // (network error, timeout) refetches the ids instead. Ignored while that id is pending.
  toggle: (productId: string) => Promise<void>;
  pending: Set<string>;
  // Short Korean message of the last failed toggle, with the product it belongs to. Cleared by the next toggle,
  // after ERROR_SHOW_MS, and when the login changes.
  lastError: { productId: string; message: string } | null;
};

const WishlistContext = createContext<WishlistContextValue | null>(null);
const NO_IDS: Set<string> = new Set();
const ID_RETRY_MS = 3000;
const ERROR_SHOW_MS = 5000;
const GENERIC_ERROR = "찜을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.";
const LOAD_FAILED = "찜 정보를 불러오지 못했어요. 페이지를 새로고침해 주세요.";

export function WishlistProvider({ children }: { children: ReactNode }) {
  const { session, ready: authReady, logout } = useAuth();
  const token = session?.token ?? null;
  // The ids belong to one login. A different or missing token never sees them (this is also how logout clears them).
  const [loaded, setLoaded] = useState<{ token: string; ids: Set<string> } | null>(null);
  // Token whose ids failed to load twice. Compared with the current token, so a new login starts as "not failed".
  const [failedToken, setFailedToken] = useState<string | null>(null);
  const [pending, setPending] = useState<Set<string>>(NO_IDS);
  // Tied to the token it happened under, so another login never sees it.
  const [errorState, setErrorState] = useState<{ token: string; productId: string; message: string } | null>(null);
  // Blocks a second toggle in the same tick, before `pending` re-renders.
  const lock = useRef(new Set<string>());
  // Latest token / logout for async callbacks that outlive a render.
  const tokenRef = useRef(token);
  const logoutRef = useRef(logout);
  useEffect(() => {
    tokenRef.current = token;
    logoutRef.current = logout;
  });
  const onAuthError = useCallback(() => logoutRef.current({ keepOrderAttempt: true }), []);

  useEffect(() => {
    if (!authReady || !token) return;
    // Ignore a response that arrives after logout or a different login.
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = (canRetry: boolean) =>
      getWishlistIds(token)
        .then((list) => {
          if (!cancelled) setLoaded({ token, ids: new Set(list) });
        })
        .catch((e) => {
          if (cancelled) return;
          if (e instanceof AuthError) onAuthError();
          // One automatic retry, then give up: `ready` stays false so the hearts stay disabled instead of showing wrong states.
          else if (canRetry) timer = setTimeout(() => void load(false), ID_RETRY_MS);
          else setFailedToken(token);
        });
    void load(true);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [authReady, token, onAuthError]);

  // The error notice goes away by itself.
  useEffect(() => {
    if (!errorState) return;
    const timer = setTimeout(() => setErrorState(null), ERROR_SHOW_MS);
    return () => clearTimeout(timer);
  }, [errorState]);

  const mine = loaded && loaded.token === token ? loaded.ids : null;
  const ids = mine ?? NO_IDS;
  const lastError = useMemo(
    () => (errorState && errorState.token === token ? { productId: errorState.productId, message: errorState.message } : null),
    [errorState, token]
  );
  const loadFailed = token !== null && failedToken === token && mine === null;

  const setWished = useCallback((forToken: string, productId: string, wished: boolean) => {
    setLoaded((cur) => {
      if (!cur || cur.token !== forToken) return cur;
      const next = new Set(cur.ids);
      if (wished) next.add(productId);
      else next.delete(productId);
      return { token: forToken, ids: next };
    });
  }, []);

  const toggle = useCallback(
    async (productId: string) => {
      if (!token || !mine || lock.current.has(productId)) return;
      const forToken = token;
      const wasWished = mine.has(productId);
      lock.current.add(productId);
      setPending(new Set(lock.current));
      setErrorState(null);
      setWished(forToken, productId, !wasWished);
      // No message for a login that is already gone.
      const fail = (message: string) => {
        if (tokenRef.current === forToken) setErrorState({ token: forToken, productId, message });
      };
      try {
        await (wasWished ? removeWish(productId, forToken) : addWish(productId, forToken));
      } catch (e) {
        if (e instanceof AuthError) onAuthError();
        else if (e instanceof WishlistError) {
          // The server answered no: the change did not happen.
          setWished(forToken, productId, wasWished);
          fail(e.message);
        } else {
          // No answer (network error, timeout): the change may or may not have happened, so ask the server.
          const others = [...lock.current].filter((id) => id !== productId);
          try {
            const server = new Set(await getWishlistIds(forToken));
            setLoaded((cur) => {
              if (!cur || cur.token !== forToken) return cur;
              // Other toggles still in flight keep their optimistic state.
              for (const id of others) {
                if (cur.ids.has(id)) server.add(id);
                else server.delete(id);
              }
              return { token: forToken, ids: server };
            });
            // The server still has the old state: the change did not go through.
            if (server.has(productId) === wasWished) fail(GENERIC_ERROR);
          } catch (e2) {
            if (e2 instanceof AuthError) onAuthError();
            else {
              setWished(forToken, productId, wasWished);
              fail(GENERIC_ERROR);
            }
          }
        }
      } finally {
        lock.current.delete(productId);
        setPending(new Set(lock.current));
      }
    },
    [token, mine, setWished, onAuthError]
  );

  const isWished = useCallback((productId: string) => ids.has(productId), [ids]);
  const ready = authReady && (!token || mine !== null);
  const value = useMemo(
    () => ({ ids, ready, loadFailed, isWished, toggle, pending, lastError }),
    [ids, ready, loadFailed, isWished, toggle, pending, lastError]
  );
  const notice = lastError?.message ?? (loadFailed ? LOAD_FAILED : null);

  return (
    <WishlistContext.Provider value={value}>
      {children}
      {/* The one live region for wishlist messages; always mounted so a new message is announced. */}
      <div
        role="status"
        aria-live="polite"
        className="pointer-events-none fixed inset-x-0 bottom-16 z-30 flex justify-center px-4 md:bottom-6"
      >
        {notice && <span className="rounded-full bg-neutral-900 px-4 py-2 text-sm text-white">{notice}</span>}
      </div>
    </WishlistContext.Provider>
  );
}

export function useWishlist() {
  const ctx = useContext(WishlistContext);
  if (!ctx) throw new Error("useWishlist must be used within WishlistProvider");
  return ctx;
}
