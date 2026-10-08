"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { MIN_BIRTH_DATE, validateBirthDate } from "@/lib/birthDate";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export function RegisterForm() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [birthDateError, setBirthDateError] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const [submitTried, setSubmitTried] = useState(false);

  // 입력 중에는 올바른 앞부분(접두사)이면 조용하고, 틀린 글자나 더 긴 값이면 안내한다. 제출하면 다르기만 해도 항상 안내한다.
  const differs = passwordConfirm !== password;
  const mismatch =
    differs && (submitTried || (passwordConfirm !== "" && !password.startsWith(passwordConfirm)));

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setSubmitTried(true);
    const ageError = validateBirthDate(birthDate);
    setBirthDateError(ageError ?? "");
    if (differs || ageError) return;
    setSubmitting(true);
    try {
      const res = await fetch(`${BACKEND_URL}/api/auth/register`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, email, password, birthDate }),
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
        <label htmlFor="register-username" className="sr-only">아이디</label>
        <input
          id="register-username"
          type="text"
          autoComplete="username"
          className="border rounded px-3 py-2 text-sm"
          placeholder="아이디 (영문 소문자·숫자·_ 4~20자)"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          pattern="[a-z0-9_]{4,20}"
          title="영문 소문자, 숫자, 밑줄(_)로 4~20자"
          aria-describedby="register-username-help"
          required
        />
        <p id="register-username-help" className="text-xs text-gray-500">
          영문 소문자, 숫자, 밑줄(_)로 4~20자
        </p>
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
          autoComplete="new-password"
          className="border rounded px-3 py-2 text-sm"
          placeholder="비밀번호 (8자 이상, 영문·숫자·특수문자 포함)"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          minLength={8}
          aria-describedby="register-password-help"
          required
        />
        <label htmlFor="register-password-confirm" className="sr-only">비밀번호 확인</label>
        <input
          id="register-password-confirm"
          type="password"
          autoComplete="new-password"
          className="border rounded px-3 py-2 text-sm"
          placeholder="비밀번호 확인"
          value={passwordConfirm}
          onChange={(e) => setPasswordConfirm(e.target.value)}
          aria-invalid={mismatch}
          aria-describedby={mismatch ? "register-password-confirm-error" : undefined}
          required
        />
        {mismatch && (
          <p id="register-password-confirm-error" role="alert" className="text-sm text-red-600">
            비밀번호가 서로 달라요.
          </p>
        )}
        <p id="register-password-help" className="text-xs text-gray-500">
          8자 이상, 영문·숫자·특수문자를 각각 1자 이상 포함해 주세요. 대문자·소문자는 구분하지 않아요.
        </p>
        <label htmlFor="register-birthdate" className="text-xs text-gray-500">생년월일 (만 14세 이상)</label>
        <input
          id="register-birthdate"
          type="date"
          className="border rounded px-3 py-2 text-sm"
          value={birthDate}
          onChange={(e) => {
            setBirthDate(e.target.value);
            setBirthDateError("");
          }}
          autoComplete="bday"
          min={MIN_BIRTH_DATE}
          aria-invalid={birthDateError !== ""}
          aria-describedby={birthDateError ? "register-birthdate-error" : undefined}
          required
        />
        {birthDateError && (
          <p id="register-birthdate-error" role="alert" className="text-sm text-red-600">
            {birthDateError}
          </p>
        )}
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
