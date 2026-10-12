"use client";

import { useEffect, useState } from "react";

// Current time that re-renders every `everyMs`, so "remaining time" labels stay fresh without reading Date.now() during render.
export function useNow(everyMs = 30_000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(t);
  }, [everyMs]);
  return now;
}
