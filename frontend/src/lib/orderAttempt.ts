const ATTEMPT_STORAGE_KEY = "shop-order-attempt";

// Only a random key and a SHA-256 hash of the order content are persisted, never the name, phone or address.
export type Attempt = { key: string; fingerprintHash: string };

// sessionStorage keeps the key across a reload; it can throw (blocked, quota), so every access is guarded.
export function readAttempt(): Attempt | null {
  try {
    const a = JSON.parse(sessionStorage.getItem(ATTEMPT_STORAGE_KEY) ?? "null");
    if (typeof a?.key === "string" && typeof a?.fingerprintHash === "string") return a;
    // Entries from before the hash change hold the plain fingerprint; do not leave personal data behind.
    if (a) sessionStorage.removeItem(ATTEMPT_STORAGE_KEY);
    return null;
  } catch {
    return null;
  }
}

export function saveAttempt(attempt: Attempt | null) {
  try {
    if (attempt) sessionStorage.setItem(ATTEMPT_STORAGE_KEY, JSON.stringify(attempt));
    else sessionStorage.removeItem(ATTEMPT_STORAGE_KEY);
  } catch {
    // The in-memory ref still protects retries without a reload.
  }
}

export const clearOrderAttempt = () => saveAttempt(null);

// Null when SubtleCrypto is unavailable (e.g. a non-secure context); the caller then keeps the attempt in memory only.
export async function sha256Hex(text: string): Promise<string | null> {
  try {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
  } catch {
    return null;
  }
}
