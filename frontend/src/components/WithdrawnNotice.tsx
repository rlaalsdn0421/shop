"use client";

import { useEffect } from "react";

// Shown once: the server renders it for ?withdrawn=1, and this drops the param so a reload does not repeat it.
export function WithdrawnNotice() {
  useEffect(() => {
    const url = new URL(window.location.href);
    if (!url.searchParams.has("withdrawn")) return;
    url.searchParams.delete("withdrawn");
    window.history.replaceState(window.history.state, "", url.pathname + url.search + url.hash);
  }, []);

  return (
    <p role="status" className="mb-6 rounded bg-neutral-100 px-4 py-3 text-center text-sm">
      탈퇴가 완료되었어요
    </p>
  );
}
