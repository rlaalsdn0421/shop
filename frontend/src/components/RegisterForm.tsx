"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export function RegisterForm() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      const res = await fetch(`${BACKEND_URL}/api/auth/register`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "회원가입에 실패했습니다.");
        return;
      }
      router.push("/login");
    } catch {
      setError("네트워크 오류로 회원가입에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="max-w-sm mx-auto flex flex-col gap-4">
      <h1 className="text-xl font-bold">회원가입</h1>
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <label htmlFor="register-email" className="sr-only">이메일</label>
        <input
          id="register-email"
          type="email"
          className="border rounded px-3 py-2 text-sm"
          placeholder="이메일"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
        <label htmlFor="register-password" className="sr-only">비밀번호</label>
        <input
          id="register-password"
          type="password"
          className="border rounded px-3 py-2 text-sm"
          placeholder="비밀번호 (8자 이상)"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          minLength={8}
          required
        />
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="bg-black text-white rounded py-2 text-sm disabled:opacity-50"
        >
          {submitting ? "가입 중..." : "회원가입"}
        </button>
      </form>
      <p className="text-sm text-gray-600">
        이미 계정이 있으신가요? <Link href="/login" className="underline">로그인</Link>
      </p>
    </div>
  );
}
