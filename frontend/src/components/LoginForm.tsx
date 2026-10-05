"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/auth/AuthContext";
import type { LoginResponse } from "@/lib/api";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export function LoginForm() {
  const router = useRouter();
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      const res = await fetch(`${BACKEND_URL}/api/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "로그인에 실패했습니다.");
        return;
      }
      const { token, role, email: loggedInEmail } = data as LoginResponse;
      login(token, loggedInEmail, role);
      router.push(role === "ADMIN" || role === "SELLER" ? "/admin/products" : "/");
    } catch {
      setError("네트워크 오류로 로그인에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="max-w-sm mx-auto flex flex-col gap-4">
      <h1 className="text-xl font-bold">로그인</h1>
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <label htmlFor="login-email" className="sr-only">아이디 또는 이메일</label>
        <input
          id="login-email"
          type="text"
          autoComplete="username"
          className="border rounded px-3 py-2 text-sm"
          placeholder="아이디 또는 이메일"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
        <label htmlFor="login-password" className="sr-only">비밀번호</label>
        <input
          id="login-password"
          type="password"
          className="border rounded px-3 py-2 text-sm"
          placeholder="비밀번호"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="bg-black text-white rounded py-2 text-sm disabled:opacity-50"
        >
          {submitting ? "로그인 중..." : "로그인"}
        </button>
      </form>
      <p className="text-sm text-gray-600">
        계정이 없으신가요? <Link href="/register" className="underline">회원가입</Link>
      </p>
    </div>
  );
}
