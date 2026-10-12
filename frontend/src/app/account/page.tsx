"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth, useRequireRole } from "@/auth/AuthContext";
import { useCart } from "@/cart/CartContext";
import { withdrawAccount, WithdrawError } from "@/lib/api";

const UNSURE = "처리됐는지 확인하지 못했어요. 다시 시도했는데 로그인 만료로 나오면 탈퇴가 이미 완료된 것일 수 있어요.";

function withdrawMessage(e: unknown) {
  if (e instanceof WithdrawError) {
    if (e.status === 401) return "비밀번호가 맞지 않아요.";
    if (e.status === 403) return "이 계정은 회원 탈퇴를 할 수 없어요.";
    if (e.status === 429) return "시도가 너무 많아요. 잠시 후 다시 시도해 주세요.";
    return "회원 탈퇴에 실패했어요. 잠시 후 다시 시도해 주세요.";
  }
  // AbortSignal.timeout() rejects with a TimeoutError DOMException; anything else here is a network failure.
  if (e instanceof DOMException && e.name === "TimeoutError") return `응답이 너무 오래 걸려요. ${UNSURE}`;
  return `네트워크에 연결하지 못했어요. ${UNSURE}`;
}

export default function AccountPage() {
  // Where we are heading after a final answer from the server. While set, useRequireRole must not redirect on its own.
  const [leaving, setLeaving] = useState<"home" | "login" | null>(null);
  const { session, ready } = useRequireRole(["USER"], leaving === null);
  const { logout } = useAuth();
  const { clear } = useCart();
  const router = useRouter();
  const [password, setPassword] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [passwordWrong, setPasswordWrong] = useState(false);
  const passwordInput = useRef<HTMLInputElement>(null);
  // Blocks a second click in the same tick, before the disabled button re-renders.
  const lock = useRef(false);

  useEffect(() => {
    if (leaving) router.replace(leaving === "home" ? "/?withdrawn=1" : "/login?reason=expired");
  }, [leaving, router]);

  if (leaving) {
    return (
      <p role="status" className="text-sm text-gray-600">
        {leaving === "home" ? "탈퇴 처리 중…" : "로그인 화면으로 이동 중…"}
      </p>
    );
  }

  // The hook only redirects; for a disallowed role it still returns the session, so never render it.
  if (!ready || !session || session.role !== "USER") return null;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!session || !confirmed || !password || lock.current) return;
    lock.current = true;
    setSubmitting(true);
    setError("");
    setPasswordWrong(false);
    try {
      await withdrawAccount(session.token, password);
      // The token is already invalid on the server; drop the local session and cart too.
      logout();
      clear();
      setLeaving("home");
      return; // keep the form locked while we navigate away
    } catch (err) {
      if (err instanceof WithdrawError && err.status === 401 && err.code === "AUTH_REQUIRED") {
        // The session is gone (expired, or the account was already withdrawn): not a wrong password.
        logout();
        setLeaving("login");
        return;
      }
      setError(withdrawMessage(err));
      if (err instanceof WithdrawError && err.status === 401) {
        setPasswordWrong(true);
        passwordInput.current?.focus();
        passwordInput.current?.select();
      }
    }
    lock.current = false;
    setSubmitting(false);
  }

  return (
    <div className="max-w-lg mx-auto flex flex-col gap-6">
      <h1 className="text-2xl font-bold">내 정보</h1>
      <div className="flex items-center justify-between text-sm">
        <p>
          <span className="text-gray-600">아이디</span> <span className="font-medium">{session.username}</span>
        </p>
        <Link href="/orders" className="underline">
          주문내역
        </Link>
      </div>

      <section aria-labelledby="withdraw-title" className="flex flex-col gap-3 border-t pt-6">
        <h2 id="withdraw-title" className="text-lg font-bold">
          회원 탈퇴
        </h2>
        <p className="text-sm text-gray-700">
          탈퇴하면 로그인할 수 없고, 아이디·이메일·생년월일 등 계정 정보는 삭제(익명 처리)돼요. 주문 내역은 관련
          법령에 따라 일정 기간 보관돼요. 작성한 리뷰는 남지만 작성자는 &quot;탈퇴한 회원&quot;으로 표시돼요. 되돌릴 수
          없어요.
        </p>
        <form onSubmit={handleSubmit} className="flex flex-col gap-3">
          <label htmlFor="withdraw-password" className="text-sm">
            비밀번호
          </label>
          <input
            id="withdraw-password"
            type="password"
            autoComplete="current-password"
            className="border rounded px-3 py-2 text-sm"
            ref={passwordInput}
            value={password}
            aria-invalid={passwordWrong || undefined}
            onChange={(e) => setPassword(e.target.value)}
            aria-describedby={error ? "withdraw-error" : undefined}
            required
          />
          {error && (
            <p id="withdraw-error" role="alert" className="text-sm text-red-600">
              {error}
            </p>
          )}
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
            위 내용을 확인했어요
          </label>
          {!confirmed && (
            <p id="withdraw-hint" className="text-xs text-gray-600">
              &apos;위 내용을 확인했어요&apos;에 체크하면 탈퇴할 수 있어요.
            </p>
          )}
          <button
            type="submit"
            aria-describedby={confirmed ? undefined : "withdraw-hint"}
            disabled={!confirmed || submitting}
            className="bg-red-600 text-white rounded py-2 text-sm disabled:opacity-50"
          >
            {submitting ? "처리 중..." : "회원 탈퇴"}
          </button>
        </form>
      </section>
    </div>
  );
}
