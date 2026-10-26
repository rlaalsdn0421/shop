"use client";

import { useRouter } from "next/navigation";
import { useAuth } from "@/auth/AuthContext";
import { useWishlist } from "@/wishlist/WishlistContext";

type Props = {
  productId: string;
  name: string;
  // card: round heart over the product image (placed by ProductCard). detail: bordered button with text on the product page.
  variant: "card" | "detail";
};

const LOAD_FAILED = "찜 정보를 불러오지 못했어요. 페이지를 새로고침해 주세요.";
const focusRing = "focus:outline-none focus-visible:ring-2 focus-visible:ring-black focus-visible:ring-offset-2";

// Heart toggle for one product. Not logged in: goes to /login and wishes nothing.
// Messages are announced by the one live region in WishlistProvider; here they are only a tooltip.
export function WishButton({ productId, name, variant }: Props) {
  const { session } = useAuth();
  const router = useRouter();
  const { ready, loadFailed, isWished, toggle, pending, lastError } = useWishlist();
  const wished = isWished(productId);
  const note = lastError?.productId === productId ? lastError.message : loadFailed && session ? LOAD_FAILED : undefined;
  // Logged in: wait for the ids and for this id's request. Auth not read yet (no session, not ready): do nothing, no dimming.
  const busy = !!session && (!ready || pending.has(productId));
  const authPending = !session && !ready;

  const heart = (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className={`h-5 w-5 shrink-0 stroke-current ${wished ? "fill-current text-red-600" : "fill-none"}`}
      strokeWidth={2}
    >
      <path d="M12 21s-7-4.4-9.3-9A5.3 5.3 0 0 1 12 6.6 5.3 5.3 0 0 1 21.3 12C19 16.6 12 21 12 21z" />
    </svg>
  );

  // The name never changes; aria-pressed carries the state.
  return (
    <button
      type="button"
      aria-pressed={wished}
      aria-label={`찜하기 - ${name}`}
      aria-disabled={authPending || undefined}
      title={note}
      disabled={busy}
      onClick={() => {
        if (authPending) return;
        if (!session) router.push("/login");
        else void toggle(productId);
      }}
      className={`${
        variant === "card"
          ? "absolute right-1 top-1 flex h-10 w-10 items-center justify-center rounded-full bg-white/90 text-neutral-800 hover:bg-white"
          : "flex flex-wrap items-center justify-center gap-x-1 rounded border py-3 hover:bg-gray-50"
      } ${busy ? "opacity-50" : ""} ${focusRing}`}
    >
      {heart}
      {variant === "detail" && <span className="whitespace-nowrap">찜하기</span>}
    </button>
  );
}
