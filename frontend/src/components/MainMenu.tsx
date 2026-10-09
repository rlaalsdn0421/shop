"use client";

import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { MENU_ITEMS, activeMenuKey, fitCount } from "@/lib/menu";

const GAP = 28; // px, matches gap-x-7 below
const DESKTOP_MIN_WIDTH = 768; // Tailwind md

// Category links are real page-level destinations; 베스트/전체상품/신상품 only jump to a
// section of the home page, which is a "location" rather than a "page".
function currentFor(key: string, active: string | undefined) {
  if (key !== active) return undefined;
  return key.startsWith("category:") ? "page" : "location";
}

export function MainMenu() {
  const pathname = usePathname();
  const params = useSearchParams();
  const active = activeMenuKey(pathname, params);

  const boxRef = useRef<HTMLDivElement>(null);
  const measureRef = useRef<HTMLDivElement>(null);
  const moreRef = useRef<HTMLLIElement>(null);
  const moreButtonRef = useRef<HTMLButtonElement>(null);
  const [visible, setVisible] = useState(MENU_ITEMS.length);
  // Until the first measurement the server-rendered row would show every item and then
  // jump; on desktop it stays invisible (still in the DOM and accessibility tree) until trimmed.
  const [measured, setMeasured] = useState(false);
  const [open, setOpen] = useState(false);

  // Widths come from a hidden bold copy of the whole menu, not from the visible row: items that
  // moved into "더보기" can't be measured there, and bold is the widest an item ever gets, so the
  // active item turning bold can never push the row past its width.
  useLayoutEffect(() => {
    const box = boxRef.current;
    const measure = measureRef.current;
    if (!box || !measure) return;

    function update() {
      const style = getComputedStyle(box!);
      const padding = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight);
      const available = box!.clientWidth - (padding || 0);
      const widths = Array.from(measure!.querySelectorAll<HTMLElement>("[data-measure-item]")).map(
        (el) => el.offsetWidth
      );
      const moreWidth = measure!.querySelector<HTMLElement>("[data-measure-more]")?.offsetWidth ?? 0;
      // On phones the row scrolls sideways instead. No width (not laid out) means nothing to fit.
      const count =
        window.innerWidth < DESKTOP_MIN_WIDTH || available <= 0
          ? MENU_ITEMS.length
          : fitCount(widths, available, GAP, moreWidth);
      setVisible(count);
      if (count >= MENU_ITEMS.length) setOpen(false); // nothing left to list under 더보기
      setMeasured(true);
    }

    update();
    // Also fires when the container or the text size changes (window resize, web font loaded).
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(update);
    observer.observe(box);
    observer.observe(measure);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!open) return;
    function onPointerDown(e: PointerEvent) {
      if (!moreRef.current?.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const shown = MENU_ITEMS.slice(0, visible);
  const overflow = MENU_ITEMS.slice(visible);
  const activeInOverflow = overflow.some((item) => item.key === active);

  return (
    <nav
      aria-label="주요 메뉴"
      className="sticky top-0 z-10 border-y border-neutral-200 bg-white"
    >
      <div ref={boxRef} className="relative mx-auto max-w-7xl px-4">
        <ul
          className={`flex items-center gap-x-7 overflow-x-auto whitespace-nowrap [scrollbar-width:none] [&::-webkit-scrollbar]:hidden md:overflow-visible ${
            measured ? "" : "md:invisible"
          }`}
        >
          {shown.map((item) => (
            <li key={item.key} className="shrink-0">
              <Link
                href={item.href}
                aria-current={currentFor(item.key, active)}
                className={`block border-b-2 py-3 text-sm ${
                  active === item.key
                    ? "border-neutral-900 font-bold text-neutral-900"
                    : "border-transparent text-neutral-600 hover:text-neutral-900"
                }`}
              >
                {item.label}
              </Link>
            </li>
          ))}
          {overflow.length > 0 && (
            <li
              ref={moreRef}
              className="relative shrink-0"
              onKeyDown={(e) => {
                if (e.key === "Escape" && open) {
                  setOpen(false);
                  moreButtonRef.current?.focus();
                }
              }}
              onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false);
              }}
            >
              <button
                ref={moreButtonRef}
                type="button"
                aria-expanded={open}
                aria-controls="menu-more"
                onClick={() => setOpen((o) => !o)}
                className={`flex items-center gap-1 border-b-2 py-3 text-sm ${
                  activeInOverflow
                    ? "border-neutral-900 font-bold text-neutral-900"
                    : "border-transparent text-neutral-600 hover:text-neutral-900"
                }`}
              >
                더보기
                <span aria-hidden="true" className="text-[10px]">
                  {open ? "▲" : "▼"}
                </span>
              </button>
              {open && (
                <ul
                  id="menu-more"
                  className="absolute right-0 top-full z-20 min-w-36 border border-neutral-200 bg-white py-1 shadow-sm"
                >
                  {overflow.map((item) => (
                    <li key={item.key}>
                      <Link
                        href={item.href}
                        aria-current={currentFor(item.key, active)}
                        onClick={() => setOpen(false)}
                        className={`block px-4 py-2 text-sm hover:bg-neutral-50 ${
                          active === item.key ? "font-bold text-neutral-900" : "text-neutral-600"
                        }`}
                      >
                        {item.label}
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          )}
        </ul>

        {/* Invisible bold copy used only for measuring; not focusable and hidden from assistive tech. */}
        <div
          aria-hidden="true"
          className="pointer-events-none invisible absolute inset-x-0 top-0 h-0 overflow-hidden"
        >
          <div ref={measureRef} className="flex w-max gap-x-7 text-sm font-bold whitespace-nowrap">
            {MENU_ITEMS.map((item) => (
              <span key={item.key} data-measure-item className="shrink-0">
                {item.label}
              </span>
            ))}
            <span data-measure-more className="flex shrink-0 items-center gap-1">
              더보기
              <span className="text-[10px]">▼</span>
            </span>
          </div>
        </div>
      </div>
    </nav>
  );
}
