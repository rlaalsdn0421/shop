"use client";

import { useEffect, useRef, useState } from "react";
import { askChat } from "@/lib/api";

type Message = { role: "user" | "bot"; text: string; greetingError?: boolean };

const ERROR_TEXT = "지금은 답변할 수 없어요. 잠시 후 다시 시도해 주세요.";

export function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const greeted = useRef(false); // 인사 요청이 진행 중이거나 끝났음
  const botAnswered = useRef(false); // 봇 답변을 한 번이라도 받았음
  const toggleRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const logRef = useRef<HTMLDivElement>(null);

  async function ask(text: string, showUser: boolean) {
    if (showUser) setMessages((m) => [...m, { role: "user", text }]);
    setLoading(true);
    try {
      const res = await askChat(text);
      botAnswered.current = true;
      setMessages((m) => [...m, { role: "bot", text: res.answer }]);
      setSuggestions(res.suggestions);
    } catch {
      setMessages((m) => [...m, { role: "bot", text: ERROR_TEXT, greetingError: !showUser }]);
      if (showUser) setInput((cur) => cur || text); // 실패한 질문은 다시 보낼 수 있게 복원
      else greeted.current = false; // 첫 인사 실패 시 다음에 열 때 다시 시도
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (!open) return;
    inputRef.current?.focus();
    if (!greeted.current && !botAnswered.current) {
      greeted.current = true;
      setMessages((m) => m.filter((x) => !x.greetingError));
      void ask("도움말", false);
    }
  }, [open]);

  useEffect(() => {
    const log = logRef.current;
    if (log) log.scrollTop = log.scrollHeight;
  }, [messages, loading, suggestions]);

  function close() {
    setOpen(false);
    toggleRef.current?.focus();
  }

  function send(raw: string) {
    const text = raw.trim();
    if (!text || loading) return;
    setInput("");
    void ask(text, true);
    inputRef.current?.focus();
  }

  const focusRing =
    "focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-black";

  return (
    <>
      {open && (
        <div
          id="chat-panel"
          role="dialog"
          aria-label="FAQ 챗봇"
          onKeyDown={(e) => {
            if (e.key === "Escape" && !e.nativeEvent.isComposing) close();
          }}
          className="fixed z-30 right-4 bottom-[8.5rem] md:bottom-24 w-[calc(100vw-2rem)] max-w-sm h-[min(32rem,calc(100dvh-12rem))] flex flex-col bg-white border border-gray-300 rounded-lg shadow-xl overflow-hidden"
        >
          <div className="flex items-center justify-between bg-black text-white px-4 py-3">
            <h2 className="text-sm font-medium">무엇이든 물어보세요</h2>
            <button
              type="button"
              onClick={close}
              aria-label="닫기"
              className={`px-2 rounded ${focusRing} focus-visible:ring-white focus-visible:ring-offset-black`}
            >
              ✕
            </button>
          </div>

          <div
            ref={logRef}
            role="log"
            aria-live="polite"
            aria-label="대화 내용"
            className="flex-1 overflow-y-auto p-3 flex flex-col gap-2 text-sm"
          >
            {messages.map((m, i) => (
              <p
                key={i}
                className={`max-w-[85%] rounded-lg px-3 py-2 whitespace-pre-wrap break-words ${
                  m.role === "user"
                    ? "self-end bg-black text-white"
                    : "self-start bg-gray-100 text-gray-900"
                }`}
              >
                {m.text}
              </p>
            ))}
            {loading && <p className="self-start text-gray-500">답변 중...</p>}
            {!loading && suggestions.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {suggestions.map((s) => (
                  <button
                    key={s}
                    type="button"
                    onClick={() => send(s)}
                    className={`border border-gray-400 rounded-full px-3 py-1 text-xs hover:bg-gray-100 ${focusRing}`}
                  >
                    {s}
                  </button>
                ))}
              </div>
            )}
          </div>

          <form
            onSubmit={(e) => {
              e.preventDefault();
              send(input);
            }}
            className="flex gap-2 border-t p-3"
          >
            <label htmlFor="chat-input" className="sr-only">질문 입력</label>
            <input
              id="chat-input"
              ref={inputRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              maxLength={200}
              placeholder="질문을 입력하세요"
              className={`flex-1 min-w-0 border rounded px-3 py-2 text-sm ${focusRing}`}
            />
            <button
              type="submit"
              disabled={!input.trim() || loading}
              className={`bg-black text-white rounded px-4 text-sm disabled:opacity-50 ${focusRing}`}
            >
              전송
            </button>
          </form>
        </div>
      )}

      <button
        type="button"
        ref={toggleRef}
        onClick={() => setOpen((o) => !o)}
        aria-label="챗봇"
        aria-expanded={open}
        aria-controls="chat-panel"
        className={`fixed z-30 right-4 bottom-[4.5rem] md:bottom-6 h-12 w-12 rounded-full bg-black text-white text-xl shadow-lg ${focusRing}`}
      >
        💬
      </button>
    </>
  );
}
