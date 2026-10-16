"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useAuth, useRequireRole } from "@/auth/AuthContext";
import { useCart } from "@/cart/CartContext";
import { PaymentMethodSelect } from "@/components/PaymentMethodSelect";
import { cancelOrder, getOrder, AuthError, OrderActionError, type Order } from "@/lib/api";
import { formatKoreanDateTime } from "@/lib/date";
import { attemptIsFor, clearOrderAttempt } from "@/lib/orderAttempt";
import {
  PAYMENT_NOT_READY,
  type PayMethodId,
  type PayResult,
  confirmPayment,
  getPaymentConfig,
  orderNameFor,
  payOrder,
  remainingLabel,
  statusLabel,
} from "@/lib/payment";
import { formatWon } from "@/lib/price";
import { useNow } from "@/lib/useNow";

export default function OrderPage() {
  const { id } = useParams<{ id: string }>();
  const { session, ready } = useRequireRole(["ADMIN", "SELLER", "USER"]);

  if (!ready || !session) return null;
  // The key remounts the loader, so a different order or login never shows the previous state.
  // useSearchParams needs a Suspense boundary so the build can prerender this route's shell.
  return (
    <Suspense fallback={<p role="status" className="text-gray-600">불러오는 중...</p>}>
      <OrderComplete key={`${id}:${session.token}`} id={id} token={session.token} />
    </Suspense>
  );
}

function OrderComplete({ id, token }: { id: string; token: string }) {
  const { logout } = useAuth();
  // Only the redirect right after checkout carries ?placed=1; reopening an old order is just a detail view.
  const params = useSearchParams();
  const placed = params.get("placed") === "1";
  // After a redirect payment (mobile) the payment window sends the browser back with these; read once, then the query is cleaned.
  const [returned] = useState(() => ({
    paymentId: params.get("paymentId"),
    code: params.get("code"),
    message: params.get("message"),
  }));
  const router = useRouter();
  const { clear: clearCart, items: cartItems } = useCart();
  const now = useNow();
  // undefined = still loading, null = no such order (or not the caller's).
  const [order, setOrder] = useState<Order | null | undefined>(undefined);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [methodId, setMethodId] = useState<PayMethodId | null>(null);
  const [paying, setPaying] = useState(false);
  const [payError, setPayError] = useState("");
  // The window said "paid" but the server has not confirmed yet: the button then only re-checks, it never pays twice.
  const [unconfirmed, setUnconfirmed] = useState(false);
  const [justPaid, setJustPaid] = useState(false);
  const [cartNoticeDismissed, setCartNoticeDismissed] = useState(false);
  const payLock = useRef(false);
  // Customer cancel: the confirm panel, the optional reason, and a lock against a double click (same idea as payLock).
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState("");
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState("");
  const cancelLock = useRef(false);
  // The next refetch follows a cancel request: if it fails we keep the order we already show instead of the error page.
  const quietRefetch = useRef(false);
  const [staleNote, setStaleNote] = useState(false);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelWasOpen = useRef(false);
  // Runs once per mount (also under StrictMode's double effect) so the redirect result is confirmed only once.
  const returnHandled = useRef<Promise<void> | null>(null);

  function applyResult(result: PayResult) {
    if (result.kind === "paid") {
      setPayError("");
      setUnconfirmed(false);
      setJustPaid(true);
      // Show the server-confirmed status right away so the pay block cannot reappear while the refetch is in flight.
      setOrder((o) => (o ? { ...o, status: result.status ?? "PAID" } : o));
      setAttempt((a) => a + 1);
    } else if (result.kind === "failed") setPayError(result.message);
    else if (result.kind === "unconfirmed") {
      setUnconfirmed(true);
      setPayError(result.message);
      // The webhook may have confirmed it meanwhile.
      setAttempt((a) => a + 1);
    }
  }

  function handleReturn(): Promise<void> {
    returnHandled.current ??= (async () => {
      if (!returned.paymentId) return;
      router.replace(`/orders/${encodeURIComponent(id)}`);
      // A result for some other order must never confirm this one.
      if (returned.paymentId.toLowerCase() !== id.toLowerCase()) return;
      if (returned.code) setPayError(returned.message || "결제가 취소되었거나 실패했어요.");
      else applyResult(await confirmPayment(id, token));
    })();
    return returnHandled.current;
  }

  useEffect(() => {
    // Ignore a response that arrives after unmount or a retry.
    let cancelled = false;
    const quiet = quietRefetch.current;
    quietRefetch.current = false;
    // Without a redirect result the order is requested right away, in the same tick as before.
    (returned.paymentId ? handleReturn().then(() => getOrder(id, token)) : getOrder(id, token))
      .then((o) => {
        if (cancelled) return;
        // A body for some other order must never be shown as this one.
        if (o && String(o.id).toLowerCase() !== id.toLowerCase()) setFailed(true);
        else {
          setOrder(o);
          setStaleNote(false);
          if (o && o.status !== "PENDING_PAYMENT") {
            // Nothing is left to confirm, so an earlier error would only be stale.
            setPayError("");
            setUnconfirmed(false);
          }
          // A cart checkout that created this very order is finished once it is paid; any other order leaves the cart alone.
          if (o?.status === "PAID" && attemptIsFor(id)) {
            clearOrderAttempt();
            clearCart();
          }
        }
      })
      .catch((e) => {
        if (cancelled) return;
        // An expired or invalid token: logging out makes useRequireRole send the user to /login.
        if (e instanceof AuthError) logout();
        else if (quiet) setStaleNote(true);
        else setFailed(true);
      });
    return () => {
      cancelled = true;
    };
    // `logout` is a fresh function every render; including it would refetch on each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, token, attempt]);

  // Back/forward cache restores the page as it was while the browser was leaving for the payment page.
  useEffect(() => {
    const onShow = (e: PageTransitionEvent) => {
      if (!e.persisted) return;
      payLock.current = false;
      setPaying(false);
    };
    window.addEventListener("pageshow", onShow);
    return () => window.removeEventListener("pageshow", onShow);
  }, []);

  // Focus follows the confirm panel: into the reason field when it opens, back to its button when it closes.
  useEffect(() => {
    if (cancelOpen) reasonRef.current?.focus();
    else if (panelWasOpen.current) triggerRef.current?.focus();
    panelWasOpen.current = cancelOpen;
  }, [cancelOpen]);

  function retry() {
    setFailed(false);
    setOrder(undefined);
    setAttempt((a) => a + 1);
  }

  const config = getPaymentConfig();
  const method = config.ready ? (config.methods.find((m) => m.id === methodId) ?? config.methods[0]) : null;

  async function pay() {
    // Confirming an accepted payment needs no payment setup; opening the payment window does.
    if (!order || payLock.current || (!unconfirmed && (!config.ready || !method))) return;
    payLock.current = true;
    setPaying(true);
    setPayError("");
    let redirected = false;
    try {
      const result =
        unconfirmed || !config.ready || !method
          ? await confirmPayment(order.id, token)
          : await payOrder({
              orderId: order.id,
              orderName: orderNameFor(order.items.map((i) => i.productName)),
              totalAmount: order.totalAmount,
              method,
              storeId: config.storeId,
              customer: { name: order.customerName, phone: order.customerPhone },
              token,
            });
      // The browser is leaving for the payment page: stay locked until it is gone (or restored from the bfcache).
      redirected = result.kind === "redirected";
      applyResult(result);
    } catch (e) {
      if (e instanceof AuthError) logout();
      else setPayError("결제를 진행하지 못했어요. 잠시 후 다시 시도해 주세요.");
    } finally {
      if (!redirected) {
        payLock.current = false;
        setPaying(false);
      }
    }
  }

  async function cancel() {
    if (!order || cancelLock.current) return;
    cancelLock.current = true;
    setCancelling(true);
    setCancelError("");
    try {
      const status = await cancelOrder(order.id, token, cancelReason);
      setCancelOpen(false);
      setCancelReason("");
      setOrder((o) => (o ? { ...o, status } : o));
    } catch (e) {
      if (e instanceof AuthError) {
        logout();
        return;
      }
      setCancelError(
        e instanceof OrderActionError ? e.message : "취소 결과를 확인하지 못했어요. 주문 상태를 확인해 주세요."
      );
      setCancelOpen(false);
    } finally {
      cancelLock.current = false;
      setCancelling(false);
    }
    // The server decides the final state (cancel time, or what a failed request left behind), so always read it back.
    quietRefetch.current = true;
    setAttempt((a) => a + 1);
  }

  const home = (
    <Link href="/" className="text-sm underline">
      홈으로
    </Link>
  );

  if (failed) {
    return (
      <div className="flex flex-col items-start gap-3">
        <p role="alert" className="text-sm text-red-600">
          주문은 접수되었을 수 있어요. 잠시 후 다시 확인해 주세요.
        </p>
        <button onClick={retry} className="border rounded px-3 py-2 text-sm">
          다시 불러오기
        </button>
        {home}
      </div>
    );
  }
  if (order === undefined) return <p role="status" className="text-gray-600">불러오는 중...</p>;
  if (order === null) {
    return (
      <div className="flex flex-col items-start gap-3">
        <p className="text-gray-600">주문을 찾을 수 없습니다.</p>
        {home}
      </div>
    );
  }

  // An older backend response has no shippingFee; anything but a positive number counts as free.
  const remaining = order.status === "PENDING_PAYMENT" ? remainingLabel(order.expiresAt, now) : null;
  const label = statusLabel(order.status);
  // Reached through a payment return and the cart still holds items: the stored attempt did not identify this order, so ask.
  const returnedHere = returned.paymentId?.toLowerCase() === id.toLowerCase();
  const showCartNotice = order.status === "PAID" && returnedHere && cartItems.length > 0 && !cartNoticeDismissed;
  const shippingFee = typeof order.shippingFee === "number" && order.shippingFee > 0 ? order.shippingFee : 0;

  return (
    <div className="max-w-lg mx-auto flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">{placed || justPaid ? "주문이 접수되었습니다" : "주문 상세"}</h1>
        <p className="text-gray-600 text-sm mt-1">주문번호 {order.id}</p>
        {label && <p role="status" className="text-sm mt-1">주문 상태: {label}</p>}
        {order.shippedAt && <p className="text-sm text-gray-600 mt-1">배송 시작 {formatKoreanDateTime(order.shippedAt)}</p>}
        {order.deliveredAt && <p className="text-sm text-gray-600 mt-1">배송 완료 {formatKoreanDateTime(order.deliveredAt)}</p>}
        {order.cancelledAt && <p className="text-sm text-gray-600 mt-1">취소 {formatKoreanDateTime(order.cancelledAt)}</p>}
        {staleNote && (
          <div role="status" className="flex items-center gap-2 text-sm text-gray-600 mt-1">
            <span>최신 상태를 불러오지 못했어요. 화면의 상태가 오래됐을 수 있어요.</span>
            <button onClick={() => setAttempt((a) => a + 1)} className="border rounded px-2 py-0.5">
              상태 다시 확인
            </button>
          </div>
        )}
      </div>

      {showCartNotice && (
        <div className="flex flex-col items-start gap-2 border rounded p-3 text-sm">
          <p>결제한 상품이 장바구니에 남아 있을 수 있어요.</p>
          <button
            onClick={() => {
              clearCart();
              setCartNoticeDismissed(true);
            }}
            className="border rounded px-3 py-1"
          >
            장바구니 비우기
          </button>
        </div>
      )}

      {order.status === "PENDING_PAYMENT" && (
        <div className="flex flex-col gap-3 border-t pt-4">
          {unconfirmed ? (
            // The payment went through at the PG: whatever the client clock says, only re-checking makes sense.
            <>
              <p className="text-sm text-gray-600">결제 내용을 확인하고 있어요</p>
              <button
                onClick={pay}
                disabled={paying}
                className="self-start bg-black text-white rounded px-4 py-2 text-sm disabled:opacity-50"
              >
                {paying ? "결제 처리 중..." : "결제 확인하기"}
              </button>
            </>
          ) : !config.ready ? (
            <p role="status" className="text-sm text-gray-600">{PAYMENT_NOT_READY}</p>
          ) : remaining ? (
            <>
              <p className="text-sm text-gray-600">남은 시간 {remaining}</p>
              {method && (
                <PaymentMethodSelect
                  methods={config.methods}
                  value={method.id}
                  onChange={setMethodId}
                  disabled={paying}
                  name="order-payment-method"
                />
              )}
              <button
                onClick={pay}
                disabled={paying}
                className="self-start bg-black text-white rounded px-4 py-2 text-sm disabled:opacity-50"
              >
                {paying ? "결제 처리 중..." : "결제하기"}
              </button>
            </>
          ) : (
            <p className="text-sm text-gray-600">결제 가능 시간이 지났어요. 곧 주문이 자동으로 취소돼요.</p>
          )}
        </div>
      )}
      <p role="status" className="sr-only">{paying ? "결제 처리 중..." : ""}</p>
      {payError && (
        <p role="alert" className="text-sm text-red-600">
          {payError}
        </p>
      )}

      {order.status === "PAID" && (
        <div className="flex flex-col items-start gap-3 border-t pt-4">
          {cancelOpen ? (
            <>
              <p className="text-sm">
                주문을 취소하면 배송비를 포함한 결제 금액 전액이 환불돼요. 카드사에 따라 며칠 걸릴 수 있어요.
              </p>
              <label htmlFor="cancel-reason" className="text-sm">
                취소 사유 (선택)
              </label>
              <textarea
                id="cancel-reason"
                ref={reasonRef}
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
                maxLength={200}
                rows={2}
                disabled={cancelling}
                className="w-full border rounded px-3 py-2 text-sm disabled:opacity-50"
              />
              <div className="flex gap-2">
                <button
                  onClick={cancel}
                  disabled={cancelling}
                  className="bg-black text-white rounded px-4 py-2 text-sm disabled:opacity-50"
                >
                  {cancelling ? "취소 처리 중..." : "주문 취소 확정"}
                </button>
                <button
                  onClick={() => setCancelOpen(false)}
                  disabled={cancelling}
                  className="border rounded px-4 py-2 text-sm disabled:opacity-50"
                >
                  돌아가기
                </button>
              </div>
            </>
          ) : (
            <button
              ref={triggerRef}
              onClick={() => {
                setCancelError("");
                setCancelOpen(true);
              }}
              className="border rounded px-4 py-2 text-sm"
            >
              주문 취소
            </button>
          )}
        </div>
      )}
      {cancelError && (
        <p role="alert" className="text-sm text-red-600">
          {cancelError}
        </p>
      )}
      {order.status === "CANCELING" && (
        <div className="flex flex-col items-start gap-2 border-t pt-4 text-sm">
          <p>취소 처리 중이에요. 잠시 뒤 다시 확인해 주세요.</p>
          <div className="flex gap-2">
            <button onClick={() => setAttempt((a) => a + 1)} className="border rounded px-3 py-1">
              새로고침
            </button>
            {/* The server resumes a stuck cancel; a seller refund in progress answers 409, which is shown below. */}
            <button
              onClick={() => {
                setCancelError("");
                void cancel();
              }}
              disabled={cancelling}
              className="border rounded px-3 py-1 disabled:opacity-50"
            >
              {cancelling ? "취소 처리 중..." : "취소 이어서 처리"}
            </button>
          </div>
        </div>
      )}
      {(order.status === "SHIPPED" || order.status === "DELIVERED") && (
        <div className="flex flex-col items-start gap-1 border-t pt-4 text-sm">
          <p>교환·환불은 연락처로 신청해 주세요.</p>
          <Link href="/refund" className="underline">
            교환·환불·배송 안내
          </Link>
        </div>
      )}

      <div className="flex flex-col gap-2 border-t pt-4">
        {order.items.map((item) => (
          <div key={item.id} className="flex justify-between text-sm">
            <span>
              {item.productName} x {item.quantity}
              {item.optionLabel && <span className="block text-xs text-gray-500">{item.optionLabel}</span>}
            </span>
            <span>{(item.price * item.quantity).toLocaleString()}원</span>
          </div>
        ))}
      </div>

      <dl className="flex flex-col gap-1 text-sm border-t pt-4">
        <div className="flex justify-between">
          <dt>상품 금액</dt>
          <dd>{formatWon(order.totalAmount - shippingFee)}</dd>
        </div>
        <div className="flex justify-between">
          <dt>배송비</dt>
          <dd>{shippingFee === 0 ? "무료" : formatWon(shippingFee)}</dd>
        </div>
        <div className="flex justify-between font-semibold text-base border-t pt-2 mt-1">
          <dt>총 주문금액</dt>
          <dd>{formatWon(order.totalAmount)}</dd>
        </div>
      </dl>

      <div className="border-t pt-4 text-sm text-gray-600 flex flex-col gap-1">
        <p>받는 사람: {order.customerName}</p>
        <p>연락처: {order.customerPhone}</p>
        <p>배송지: {order.customerAddress}</p>
      </div>

      <div className="flex gap-4 text-sm">
        <Link href="/orders" className="underline">
          주문내역으로
        </Link>
        {home}
      </div>
    </div>
  );
}
