"use client";

import { FormEvent, useEffect, useRef, useState } from "react";
import { useAuth, useRequireRole } from "@/auth/AuthContext";
import {
  AuthError,
  OrderActionError,
  deliverOrder,
  listAdminOrders,
  refundOrder,
  shipOrder,
  type AdminOrder,
} from "@/lib/api";
import { formatKoreanDateTime } from "@/lib/date";
import { statusLabel } from "@/lib/payment";
import { formatWon } from "@/lib/price";
import { useNow } from "@/lib/useNow";

// The backend filters by one status at a time, so the cancel/refund tab opens a second row to pick which one.
const CANCEL_FILTERS = [
  { label: "배송 전 취소", status: "CANCELLED" },
  { label: "배송 후 환불", status: "REFUNDED" },
];
const FILTERS = [
  { label: "전체", status: "" },
  { label: "결제 완료", status: "PAID" },
  { label: "배송 중", status: "SHIPPED" },
  { label: "배송 완료", status: "DELIVERED" },
  { label: "취소·환불", status: CANCEL_FILTERS[0].status },
];
const inCancelTab = (status: string) => CANCEL_FILTERS.some((f) => f.status === status);
const RESUME_HINT = "취소 처리를 이어서 진행합니다";
const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;
const LOAD_FAILED = "주문 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";

export default function AdminOrdersPage() {
  const { session, ready } = useRequireRole(["ADMIN", "SELLER"]);

  // Customer names, phones and addresses are listed here, so nothing is fetched for a customer account.
  if (!ready || !session || session.role === "USER") return null;
  // The key remounts the list, so a different login never shows the previous user's orders.
  return <AdminOrderList key={session.token} token={session.token} />;
}

function AdminOrderList({ token }: { token: string }) {
  const { logout } = useAuth();
  const now = useNow();
  const [status, setStatus] = useState("");
  // null = first page still loading.
  const [orders, setOrders] = useState<AdminOrder[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [rowError, setRowError] = useState<Record<string, string>>({});
  const [refundFor, setRefundFor] = useState<string | null>(null);
  // One reason per order, so finishing a refund on one row never touches what is typed on another.
  const [reasons, setReasons] = useState<Record<string, string>>({});
  // List-level message (e.g. a state conflict that forced a reload) and the polite announcement of finished actions.
  const [notice, setNotice] = useState("");
  const [announce, setAnnounce] = useState("");
  const prevRefundFor = useRef<string | null>(null);
  // Which filter/reload the list currently shows, so a late "more" response for an old one is dropped.
  const listKey = useRef("");
  const moreLock = useRef(false);
  // Blocks a second click on the same row in the same tick, before the disabled button re-renders.
  const rowLocks = useRef(new Set<string>());

  useEffect(() => {
    let cancelled = false;
    listKey.current = `${status}:${attempt}`;
    listAdminOrders(token, status)
      .then((page) => {
        if (cancelled) return;
        setOrders(page.orders);
        setNextCursor(page.nextCursor);
      })
      .catch((e) => {
        if (cancelled) return;
        // An expired or invalid token: logging out makes useRequireRole send the user to /login.
        if (e instanceof AuthError) logout();
        else setFailed(true);
      });
    return () => {
      cancelled = true;
    };
    // `logout` is a fresh function every render; including it would refetch on each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, status, attempt]);

  // Focus follows the refund form: into the reason field when it opens, back to the row's button when it closes.
  useEffect(() => {
    const prev = prevRefundFor.current;
    prevRefundFor.current = refundFor;
    if (refundFor) document.getElementById(`refund-reason-${refundFor}`)?.focus();
    else if (prev) document.getElementById(`refund-open-${prev}`)?.focus();
  }, [refundFor]);

  function reload(nextStatus: string) {
    setOrders(null);
    setNextCursor(null);
    setFailed(false);
    setMoreFailed(false);
    // A "more" request still in flight belongs to the old list; its result is dropped, so it must not keep the button locked.
    moreLock.current = false;
    setLoadingMore(false);
    setRowError({});
    setNotice("");
    setRefundFor(null);
    setReasons({});
    setStatus(nextStatus);
    setAttempt((a) => a + 1);
  }

  async function loadMore() {
    if (!nextCursor || moreLock.current) return;
    moreLock.current = true;
    const g = `${status}:${attempt}`;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const page = await listAdminOrders(token, status, nextCursor);
      if (g !== listKey.current) return;
      // Appending to the latest list (not a copy from when the click happened) keeps rows an action changed meanwhile.
      setOrders((prev) => {
        const seen = new Set((prev ?? []).map((o) => o.id));
        return [...(prev ?? []), ...page.orders.filter((o) => !seen.has(o.id))];
      });
      setNextCursor(page.nextCursor);
    } catch (e) {
      if (g !== listKey.current) return;
      if (e instanceof AuthError) logout();
      else setMoreFailed(true);
    } finally {
      // After a reload the lock was already released and may belong to a newer request.
      if (g === listKey.current) {
        moreLock.current = false;
        setLoadingMore(false);
      }
    }
  }

  // Runs one row action; returns true when the server accepted it. The row takes its new status from the response;
  // dates such as shippedAt come with the next load rather than from this device's clock.
  async function act(o: AdminOrder, call: () => Promise<string>) {
    if (rowLocks.current.has(o.id)) return false;
    rowLocks.current.add(o.id);
    setBusy((b) => ({ ...b, [o.id]: true }));
    setRowError((r) => ({ ...r, [o.id]: "" }));
    try {
      const newStatus = await call();
      setOrders((list) => list?.map((x) => (x.id === o.id ? { ...x, status: newStatus } : x)) ?? list);
      setAnnounce(`주문 ${o.id.slice(0, 8)}: ${statusLabel(newStatus) || newStatus}`);
      return true;
    } catch (e) {
      if (e instanceof AuthError) logout();
      else if (e instanceof OrderActionError && e.code === "INVALID_ORDER_STATE") {
        // Someone else changed the order; the list is stale, so show the current one.
        reload(status);
        setNotice("주문 상태가 바뀌어 처리하지 못했어요. 목록을 새로 불러왔어요.");
      } else {
        const message =
          e instanceof OrderActionError ? e.message : "요청 결과를 확인하지 못했어요. 목록을 새로고침해 확인해 주세요.";
        setRowError((r) => ({ ...r, [o.id]: message }));
      }
      return false;
    } finally {
      rowLocks.current.delete(o.id);
      setBusy((b) => ({ ...b, [o.id]: false }));
    }
  }

  function openRefund(o: AdminOrder) {
    setRefundFor(o.id);
    // A stuck cancel is resumed through the same call; the hint is only a starting text, the reason stays required and editable.
    setReasons((r) => ({ ...r, [o.id]: o.status === "CANCELING" ? RESUME_HINT : "" }));
    setRowError((r) => ({ ...r, [o.id]: "" }));
  }

  async function submitRefund(e: FormEvent, o: AdminOrder) {
    e.preventDefault();
    const trimmed = (reasons[o.id] ?? "").trim();
    if (!trimmed) {
      setRowError((r) => ({ ...r, [o.id]: "환불 사유를 입력해 주세요." }));
      return;
    }
    if (await act(o, () => refundOrder(o.id, token, trimmed))) {
      // Only close the form if it is still this order's; the admin may have opened another row meanwhile.
      setRefundFor((cur) => (cur === o.id ? null : cur));
      setReasons((r) => {
        const next = { ...r };
        delete next[o.id];
        return next;
      });
    }
  }

  let body;
  if (failed) {
    body = (
      <div className="flex flex-col items-start gap-3">
        <p role="alert" className="text-sm text-red-600">
          {LOAD_FAILED}
        </p>
        <button onClick={() => reload(status)} className="border rounded px-3 py-2 text-sm">
          다시 불러오기
        </button>
      </div>
    );
  } else if (orders === null) {
    body = <p role="status" className="text-gray-600">불러오는 중...</p>;
  } else if (orders.length === 0) {
    body = <p className="text-gray-600">해당하는 주문이 없어요.</p>;
  } else {
    body = (
      <>
        <ul className="flex flex-col gap-3">
          {orders.map((o) => {
            const short = o.id.slice(0, 8);
            const isBusy = !!busy[o.id];
            const deliveredAt = o.deliveredAt ? new Date(o.deliveredAt).getTime() : NaN;
            const late = now - deliveredAt > SEVEN_DAYS_MS;
            // A cancel stuck at the provider (CANCELING) is finished through the same refund call.
            const resuming = o.status === "CANCELING";
            const canRefund = o.status === "SHIPPED" || o.status === "DELIVERED" || resuming;
            return (
              <li key={o.id} className="border rounded p-4 flex flex-col gap-2 text-sm">
                <div className="flex justify-between text-xs text-gray-500">
                  <span>{formatKoreanDateTime(o.createdAt)}</span>
                  <span>주문번호 {short}</span>
                </div>
                <div className="font-medium">
                  {o.firstItemName}
                  {o.itemCount > 1 ? ` 외 ${o.itemCount - 1}건` : ""}
                </div>
                <div>
                  {formatWon(o.totalAmount)}
                  <span className="text-gray-500"> (배송비 {o.shippingFee > 0 ? formatWon(o.shippingFee) : "무료"})</span>
                </div>
                <div className="font-medium">{statusLabel(o.status) || o.status}</div>
                <div className="text-gray-600">
                  <p>
                    {o.customerName} / {o.customerPhone}
                  </p>
                  <p>{o.customerAddress}</p>
                </div>
                <div className="text-xs text-gray-500">
                  {o.paidAt && <p>결제 {formatKoreanDateTime(o.paidAt)}</p>}
                  {o.shippedAt && <p>배송 시작 {formatKoreanDateTime(o.shippedAt)}</p>}
                  {o.deliveredAt && <p>배송 완료 {formatKoreanDateTime(o.deliveredAt)}</p>}
                </div>

                <div className="flex gap-2">
                  {o.status === "PAID" && (
                    <button
                      onClick={() => act(o, () => shipOrder(o.id, token))}
                      disabled={isBusy}
                      aria-label={`${isBusy ? "처리 중 " : ""}배송 시작 - 주문 ${short}`}
                      className="bg-black text-white rounded px-3 py-1 disabled:opacity-50"
                    >
                      {isBusy ? "처리 중..." : "배송 시작"}
                    </button>
                  )}
                  {o.status === "SHIPPED" && (
                    <button
                      onClick={() => act(o, () => deliverOrder(o.id, token))}
                      disabled={isBusy}
                      aria-label={`${isBusy ? "처리 중 " : ""}배송 완료 처리 - 주문 ${short}`}
                      className="bg-black text-white rounded px-3 py-1 disabled:opacity-50"
                    >
                      {isBusy ? "처리 중..." : "배송 완료"}
                    </button>
                  )}
                  {canRefund && refundFor !== o.id && (
                    <button
                      id={`refund-open-${o.id}`}
                      onClick={() => openRefund(o)}
                      disabled={isBusy}
                      aria-label={`${isBusy ? "처리 중 " : ""}${resuming ? "취소 이어서 처리" : "환불"} - 주문 ${short}`}
                      className="border rounded px-3 py-1 disabled:opacity-50"
                    >
                      {resuming ? "취소 이어서 처리" : "환불"}
                    </button>
                  )}
                </div>
                {isBusy && <p className="text-gray-500">처리 중...</p>}

                {canRefund && refundFor === o.id && (
                  <form noValidate onSubmit={(e) => submitRefund(e, o)} className="flex flex-col gap-2 border-t pt-2">
                    {o.status === "DELIVERED" && late && (
                      <p className="text-amber-700">수령 후 7일이 지났어요. 판매자 재량으로 환불할 수 있어요</p>
                    )}
                    <p>
                      {resuming
                        ? "멈춰 있는 취소를 이어서 처리해요. 결제 금액 전액이 환불돼요."
                        : "결제 금액 전액을 환불해요. 되돌릴 수 없어요."}
                    </p>
                    <label htmlFor={`refund-reason-${o.id}`}>환불 사유</label>
                    <input
                      id={`refund-reason-${o.id}`}
                      value={reasons[o.id] ?? ""}
                      onChange={(e) => setReasons((r) => ({ ...r, [o.id]: e.target.value }))}
                      maxLength={200}
                      required
                      aria-required="true"
                      disabled={isBusy}
                      className="border rounded px-3 py-2 disabled:opacity-50"
                    />
                    <div className="flex gap-2">
                      <button
                        type="submit"
                        disabled={isBusy}
                        aria-label={`${isBusy ? "환불 처리 중 " : ""}${resuming ? "이어서 처리 확정" : "환불 확정"} - 주문 ${short}`}
                        className="bg-black text-white rounded px-3 py-1 disabled:opacity-50"
                      >
                        {isBusy ? "환불 처리 중..." : resuming ? "이어서 처리 확정" : "환불 확정"}
                      </button>
                      <button
                        type="button"
                        onClick={() => setRefundFor(null)}
                        disabled={isBusy}
                        className="border rounded px-3 py-1 disabled:opacity-50"
                      >
                        닫기
                      </button>
                    </div>
                  </form>
                )}

                {rowError[o.id] && (
                  <p role="alert" className="text-red-600">
                    {rowError[o.id]}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
        {moreFailed && (
          <p role="alert" className="text-sm text-red-600">
            주문 목록을 더 불러오지 못했어요.
          </p>
        )}
        {nextCursor && (
          <button
            onClick={loadMore}
            disabled={loadingMore}
            className="border rounded px-3 py-2 text-sm disabled:opacity-50"
          >
            {loadingMore ? "불러오는 중..." : moreFailed ? "다시 시도" : "더 보기"}
          </button>
        )}
      </>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold">주문 관리</h1>
        <button onClick={() => reload(status)} className="border rounded px-3 py-1 text-sm">
          새로고침
        </button>
      </div>
      <p role="status" className="sr-only">{announce}</p>
      {notice && (
        <p role="alert" className="text-sm text-red-600">
          {notice}
        </p>
      )}
      <div role="group" aria-label="주문 상태" className="flex flex-wrap gap-2">
        {FILTERS.map((f) => {
          const pressed = f.label === "취소·환불" ? inCancelTab(status) : f.status === status;
          return (
            <button
              key={f.label}
              onClick={() => !pressed && reload(f.status)}
              aria-pressed={pressed}
              className={`rounded border px-3 py-1 text-sm ${pressed ? "bg-black text-white" : ""}`}
            >
              {f.label}
            </button>
          );
        })}
      </div>
      {inCancelTab(status) && (
        <div role="group" aria-label="취소·환불 구분" className="flex flex-wrap gap-2">
          {CANCEL_FILTERS.map((f) => (
            <button
              key={f.label}
              onClick={() => f.status !== status && reload(f.status)}
              aria-pressed={f.status === status}
              className={`rounded border px-3 py-1 text-sm ${f.status === status ? "bg-black text-white" : ""}`}
            >
              {f.label}
            </button>
          ))}
        </div>
      )}
      {body}
    </div>
  );
}
