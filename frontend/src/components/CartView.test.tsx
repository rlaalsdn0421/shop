import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { CartProvider, useCart } from "@/cart/CartContext";
import { FAKE_PAYMENT_ENV, clearPaymentEnv, stubPaymentEnv } from "@/test-utils/paymentEnv";
import { CartView } from "./CartView";

const requestPayment = vi.hoisted(() => vi.fn());
vi.mock("@portone/browser-sdk/v2", () => ({ requestPayment }));

const push = vi.fn();
const logout = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => ({ session: { token: "fake-token", role: "USER", username: "buyer" }, ready: true }),
  useAuth: () => ({ logout }),
}));

type Reply = { status: number; body?: unknown; json?: () => Promise<unknown> } | Error;

// Product price lookups (GET /api/products/{id}) are answered by `productReply`, never by the scripted order replies.
// By default a product still has the price of the cart line stored in localStorage (nothing changed); a test overrides
// `productReply` to simulate an edited price, a 404, a failure or a slow answer.
const productFromCart = (id: string): Reply => {
  const lines = (JSON.parse(localStorage.getItem("shop-cart") ?? "[]") as StoredItem[]).filter((i) => i.productId === id);
  if (lines.length === 0) return { status: 404 };
  const options = lines.filter((i) => i.optionId).map((i) => ({ id: i.optionId, price: i.price }));
  return { status: 200, body: { id, price: lines[0].price, ...(options.length > 0 ? { options } : {}) } };
};
let productReply: (id: string) => Reply | Promise<Reply> = productFromCart;
const productIdOf = (url: unknown) => String(url).match(/\/api\/products\/([^/?]+)/)?.[1];

// Every other request (orders, payment/complete) takes the next scripted reply; the last one repeats.
function routeFetch(next: (n: number) => Reply | Promise<Reply>) {
  let n = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: unknown) => {
      const id = productIdOf(url);
      const r = await (id ? productReply(decodeURIComponent(id)) : next(n++));
      if (r instanceof Error) throw r;
      return {
        ok: r.status >= 200 && r.status < 300,
        status: r.status,
        json: r.json ?? (async () => r.body),
      };
    })
  );
}
function mockReplies(...replies: Reply[]) {
  routeFetch((n) => replies[Math.min(n, replies.length - 1)]);
}

const productCalls = () => vi.mocked(fetch).mock.calls.filter(([url]) => productIdOf(url) !== undefined);
const keys = () => orderCalls().map(([, init]) => (init?.headers as Record<string, string>)["Idempotency-Key"]);
const submit = () => userEvent.click(screen.getByRole("button", { name: "주문하기" }));

async function renderCart() {
  localStorage.setItem(
    "shop-cart",
    JSON.stringify([{ productId: "p1", name: "린넨 셔츠", price: 8000, imageUrl: "https://example.com/a.jpg", quantity: 1 }])
  );
  const view = render(
    <CartProvider>
      <CartView />
    </CartProvider>
  );
  await userEvent.type(await screen.findByLabelText("이름"), "홍길동");
  await userEvent.type(screen.getByLabelText("연락처"), "010-0000-0000");
  await userEvent.type(screen.getByLabelText("배송 주소"), "서울시 테스트구");
  return view;
}

// The order reply once payment is confirmed (or replayed after it was); the other statuses are built from it.
const PAID_ORDER = { id: "o1", status: "PAID", totalAmount: 11500, orderName: "린넨 셔츠", expiresAt: null };

beforeEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  stubPaymentEnv(FAKE_PAYMENT_ENV);
  productReply = productFromCart;
  refreshPrice = 9000;
  // Every test gets a routed fetch (product lookups never reach the network); tests that order call mockReplies again.
  routeFetch(() => ({ status: 500, body: {} }));
  requestPayment.mockReset();
  localStorage.clear();
  sessionStorage.clear();
  push.mockClear();
  logout.mockClear();
});

function renderCartWith(price: number, quantity: number) {
  localStorage.setItem(
    "shop-cart",
    JSON.stringify([{ productId: "p1", name: "린넨 셔츠", price, imageUrl: "https://example.com/a.jpg", quantity }])
  );
  return render(
    <CartProvider>
      <CartView />
    </CartProvider>
  );
}

const row = (label: string) => screen.getByText(label).closest("div")!;

describe("CartView 요약 금액", () => {
  it("성공: 50,000원 미만이면 배송비 3,500원을 더하고 무료까지 남은 금액을 안내한다", async () => {
    renderCartWith(49999, 1);
    expect(await screen.findByText("상품 금액")).toBeInTheDocument();
    expect(row("상품 금액")).toHaveTextContent("49,999원");
    expect(row("배송비")).toHaveTextContent("3,500원");
    expect(row("총 주문금액")).toHaveTextContent("53,499원");
    expect(screen.getByText("1원 더 담으면 배송비 무료")).toBeInTheDocument();
  });

  it("성공: 정확히 50,000원이면 배송비는 무료이고 안내 문구가 없다", async () => {
    renderCartWith(25000, 2);
    expect(await screen.findByText("상품 금액")).toBeInTheDocument();
    expect(row("상품 금액")).toHaveTextContent("50,000원");
    expect(row("배송비")).toHaveTextContent("무료");
    expect(row("총 주문금액")).toHaveTextContent("50,000원");
    expect(screen.queryByText(/더 담으면 배송비 무료/)).not.toBeInTheDocument();
  });

  it("성공: 50,000원을 넘으면 배송비는 무료다", async () => {
    renderCartWith(50001, 1);
    expect(await screen.findByText("상품 금액")).toBeInTheDocument();
    expect(row("배송비")).toHaveTextContent("무료");
    expect(row("총 주문금액")).toHaveTextContent("50,001원");
  });

  it("실패: 결제 설정이 없으면 준비 중 안내를 보이고 주문하기가 막힌다", async () => {
    clearPaymentEnv();
    renderCartWith(8000, 1);
    expect(await screen.findByText("총 주문금액")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("결제 준비 중이에요");
    expect(screen.getByRole("button", { name: "주문하기" })).toBeDisabled();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(row("총 주문금액")).toHaveTextContent("11,500원");
    expect(screen.getByText("42,000원 더 담으면 배송비 무료")).toBeInTheDocument();
  });
});

describe("CartView 주문 멱등 키", () => {
  it("성공: Idempotency-Key와 토큰을 보내고, 성공하면 장바구니를 비우고 이동한다", async () => {
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[0]).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    expect(orderCalls()[0][1]?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    expect(await screen.findByText("장바구니가 비어있습니다.")).toBeInTheDocument();
  });

  it("성공: 5xx 후 다시 눌러도 같은 키로 보낸다", async () => {
    mockReplies({ status: 500, body: { error: "서버 오류" } }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("서버 오류");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: 네트워크 오류 후 다시 눌러도 같은 키로 보낸다", async () => {
    mockReplies(new TypeError("Failed to fetch"), { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("네트워크 오류");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: JSON이 아닌 502도 서버 오류 문구를 보이고 같은 키를 유지한다", async () => {
    mockReplies(
      { status: 502, json: async () => { throw new SyntaxError("Unexpected token <"); } },
      { status: 201, body: PAID_ORDER }
    );
    await renderCart();
    await submit();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("서버 오류로 주문에 실패했습니다");
    expect(alert).not.toHaveTextContent("네트워크");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("실패: 503이면 잠시 후 다시 시도하라고 안내하고 같은 키를 유지한다", async () => {
    mockReplies({ status: 503, body: { error: "lock timeout" } }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("잠시 후 다시 시도해주세요.");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("실패: 4xx(재고 부족 등) 후에는 새 키로 보내고 서버 메시지를 보여준다", async () => {
    mockReplies({ status: 400, body: { error: "재고가 부족합니다." } }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("재고가 부족합니다.");
    expect(push).not.toHaveBeenCalled();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it("실패: 409 후에도 새 키로 보낸다", async () => {
    mockReplies({ status: 409, body: { error: "같은 키에 다른 내용" } }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("같은 키에 다른 내용");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it("성공: 실패 뒤 입력을 바꾸면 새 키로 보낸다", async () => {
    mockReplies({ status: 500, body: {} }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    await userEvent.type(screen.getByLabelText("배송 주소"), " 101호");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it("성공: 실패 뒤 수량을 바꾸면 새 키로 보낸다", async () => {
    mockReplies({ status: 500, body: {} }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    await userEvent.click(screen.getByRole("button", { name: "수량 증가" }));
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it("성공: 제출 중에는 입력칸이 읽기 전용이고 수량·삭제 버튼이 잠기며, 끝나면 풀린다", async () => {
    let finish: ((r: Reply) => void) | undefined;
    routeFetch(() => new Promise<Reply>((r) => (finish = r)));
    await renderCart();
    await submit();
    for (const label of ["이름", "연락처", "배송 주소"]) expect(screen.getByLabelText(label)).toHaveAttribute("readonly");
    for (const name of ["수량 감소", "수량 증가", "삭제"]) expect(screen.getByRole("button", { name })).toBeDisabled();
    await waitFor(() => expect(finish).toBeDefined());
    finish!({ status: 400, body: { error: "재고가 부족합니다." } });
    expect(await screen.findByRole("alert")).toHaveTextContent("재고가 부족합니다.");
    expect(screen.getByLabelText("이름")).not.toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "수량 증가" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "삭제" })).toBeEnabled();
  });

  it("성공: 요청에 timeout signal을 싣는다", async () => {
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(orderCalls()[0][1]?.signal).toHaveProperty("aborted", false);
  });

  it("성공: 타임아웃 후에는 구분 문구를 보이고 같은 키를 유지한다", async () => {
    mockReplies(Object.assign(new Error("timed out"), { name: "TimeoutError" }), { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "응답이 늦어 주문 결과를 확인하지 못했습니다. 같은 내용으로 다시 눌러주세요."
    );
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: 2xx인데 orderId가 없으면 오류를 보이고 같은 키를 유지한다", async () => {
    mockReplies({ status: 200, body: {} }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("서버 오류로 주문에 실패했습니다");
    expect(push).not.toHaveBeenCalled();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: 새로고침(재마운트)해도 같은 내용이면 같은 키, 내용이 다르면 새 키를 쓴다", async () => {
    mockReplies(new TypeError("Failed to fetch"), new TypeError("Failed to fetch"), { status: 201, body: PAID_ORDER });
    const first = await renderCart();
    await submit();
    await screen.findByRole("alert");
    first.unmount();

    const second = await renderCart();
    await submit();
    await waitFor(() => expect(orderCalls()).toHaveLength(2));
    expect(keys()[1]).toBe(keys()[0]);
    await screen.findByRole("alert");
    second.unmount();

    await renderCart();
    await userEvent.type(screen.getByLabelText("배송 주소"), " 101호");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[2]).not.toBe(keys()[0]);
  });

  it("성공: 4xx로 거절되면 저장된 키를 지워 새로고침 뒤에도 새 키를 쓴다", async () => {
    mockReplies({ status: 400, body: { error: "재고가 부족합니다." } }, { status: 201, body: PAID_ORDER });
    const first = await renderCart();
    await submit();
    await screen.findByRole("alert");
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
    first.unmount();
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it("성공: 저장되는 값은 {key, fingerprintHash(SHA-256 hex)}뿐이고 이름·연락처·주소가 들어 있지 않다", async () => {
    mockReplies({ status: 500, body: {} });
    await renderCart();
    await submit();
    await screen.findByRole("alert");

    const raw = sessionStorage.getItem("shop-order-attempt")!;
    const stored = JSON.parse(raw);
    expect(Object.keys(stored).sort()).toEqual(["fingerprintHash", "key"]);
    expect(stored.fingerprintHash).toMatch(/^[0-9a-f]{64}$/);
    for (const personal of ["홍길동", "010-0000-0000", "서울시 테스트구", "p1"]) expect(raw).not.toContain(personal);
  });

  it("성공: 예전 형식(평문 fingerprint)으로 저장된 값은 읽지 않고 지운다", async () => {
    sessionStorage.setItem("shop-order-attempt", JSON.stringify({ key: "old", fingerprint: '["홍길동","010-0000-0000"]' }));
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());

    expect(keys()[0]).not.toBe("old");
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
  });

  it("성공: SubtleCrypto가 없으면 sessionStorage에는 아무것도 쓰지 않고 메모리로만 같은 키를 유지한다", async () => {
    const real = crypto;
    vi.stubGlobal("crypto", { randomUUID: () => real.randomUUID() });
    mockReplies({ status: 500, body: {} }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: 성공하면 저장된 키를 지운다", async () => {
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
  });

  it("성공: sessionStorage를 쓸 수 없어도 주문되고 재시도 키는 유지된다", async () => {
    const broken = { getItem: () => { throw new Error("blocked"); }, setItem: () => { throw new Error("blocked"); }, removeItem: () => { throw new Error("blocked"); } };
    vi.stubGlobal("sessionStorage", broken);
    mockReplies({ status: 500, body: {} }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: 같은 틱에 두 번 제출해도 요청은 한 번이다", async () => {
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    const form = screen.getByRole("button", { name: "주문하기" }).closest("form")!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    await waitFor(() => expect(push).toHaveBeenCalledTimes(1));
    expect(orderCalls()).toHaveLength(1);
  });

  it("실패: 입력이 비어 있으면 요청하지 않는다", async () => {
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await userEvent.clear(screen.getByLabelText("이름"));
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("이름, 연락처, 주소를 모두 입력해주세요.");
    expect(orderCalls()).toHaveLength(0);
  });

  it.each(["abc", "12345678", "1".repeat(16), "010.0000.0000", "010-0000-0000x", "01+00000000"])(
    "실패: 연락처 %s 는 요청하지 않고 안내 문구를 보인다",
    async (phone) => {
      mockReplies({ status: 201, body: PAID_ORDER });
      await renderCart();
      await userEvent.clear(screen.getByLabelText("연락처"));
      await userEvent.type(screen.getByLabelText("연락처"), phone);
      await submit();
      expect(await screen.findByRole("alert")).toHaveTextContent("연락처는 숫자 9~15자리로 입력해 주세요.");
      expect(orderCalls()).toHaveLength(0);
    }
  );

  it.each(["010-0000-0000", "01000000000", "+82 10 0000 0000", "(02) 000-0000", "1".repeat(15), "123456789"])(
    "성공: 연락처 %s 는 주문 요청까지 간다",
    async (phone) => {
      mockReplies({ status: 201, body: PAID_ORDER });
      await renderCart();
      await userEvent.clear(screen.getByLabelText("연락처"));
      await userEvent.type(screen.getByLabelText("연락처"), phone);
      await submit();
      await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
      expect(JSON.parse(String(orderCalls()[0][1]?.body)).customerPhone).toBe(phone);
    }
  );

  it.each([401, 403])("실패: 주문 요청이 %i 이면 만료 안내를 보이고 로그아웃하며 저장된 시도는 지우지 않는다", async (status) => {
    mockReplies({ status, body: { error: "인증이 필요합니다." } });
    await renderCart();
    await submit();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("로그인이 만료되었어요. 다시 로그인해 주세요.");
    expect(alert).not.toHaveTextContent("인증이 필요합니다.");
    expect(logout).toHaveBeenCalledTimes(1);
    expect(logout).toHaveBeenCalledWith({ keepOrderAttempt: true });
    expect(sessionStorage.getItem("shop-order-attempt")).not.toBeNull();
    expect(push).not.toHaveBeenCalled();
  });

  it("성공: 401 뒤 다시 눌러도 같은 키로 보낸다", async () => {
    mockReplies({ status: 401, body: { error: "인증이 필요합니다." } }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("실패: 400은 로그아웃하지 않고 서버 메시지를 보인다", async () => {
    mockReplies({ status: 400, body: { error: "재고가 부족합니다." } });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("재고가 부족합니다.");
    expect(logout).not.toHaveBeenCalled();
  });
});

const PENDING_ORDER = { ...PAID_ORDER, status: "PENDING_PAYMENT", expiresAt: "2099-01-01T00:00:00Z" };
const windowOk = { transactionType: "PAYMENT", txId: "t", paymentId: "o1" };
const completeCalls = () => vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith("/payment/complete"));
const orderCalls = () => vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith("/api/orders"));

describe("CartView 결제", () => {
  it("성공: 채널 키가 있는 결제수단만 보여주고 카드가 기본 선택이다", async () => {
    stubPaymentEnv({ NEXT_PUBLIC_PORTONE_CHANNEL_KEY_NAVERPAY: "", NEXT_PUBLIC_PORTONE_CHANNEL_KEY_TOSSPAY: "" });
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    expect(screen.getAllByRole("radio").map((r) => r.closest("label")?.textContent)).toEqual(["카드", "카카오페이"]);
    expect(screen.getByRole("radio", { name: "카드" })).toBeChecked();
    expect(screen.queryByText(/결제 준비 중/)).not.toBeInTheDocument();
  });

  it("실패: 상점 ID가 없으면 결제수단이 있어도 안내만 보이고 주문을 만들지 않는다", async () => {
    stubPaymentEnv({ NEXT_PUBLIC_PORTONE_STORE_ID: "" });
    mockReplies({ status: 201, body: PENDING_ORDER });
    await renderCart();
    expect(screen.getByRole("status")).toHaveTextContent("결제 준비 중이에요");
    fireEvent.submit(screen.getByRole("button", { name: "주문하기" }).closest("form")!);
    expect(await screen.findByRole("alert")).toHaveTextContent("결제 준비 중이에요");
    expect(orderCalls()).toHaveLength(0);
    expect(requestPayment).not.toHaveBeenCalled();
  });

  it("실패: 채널 키가 하나도 없으면 주문을 만들지 않는다", async () => {
    clearPaymentEnv();
    stubPaymentEnv({ NEXT_PUBLIC_PORTONE_STORE_ID: "store-test-0000" });
    mockReplies({ status: 201, body: PENDING_ORDER });
    await renderCart();
    expect(screen.getByRole("status")).toHaveTextContent("결제 준비 중이에요");
    expect(screen.getByRole("button", { name: "주문하기" })).toBeDisabled();
    expect(orderCalls()).toHaveLength(0);
  });

  it("성공: 대기 주문이면 서버가 준 id·금액·주문명으로 결제창을 열고, 확정되면 장바구니를 비우고 이동한다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER }, { status: 200, body: { status: "PAID" } });
    requestPayment.mockResolvedValue(windowOk);
    await renderCart();
    await userEvent.click(screen.getByRole("radio", { name: "토스페이" }));
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(requestPayment).toHaveBeenCalledWith({
      storeId: "store-test-0000",
      channelKey: "channel-tosspay-test",
      paymentId: "o1",
      orderName: "린넨 셔츠",
      totalAmount: 11500,
      currency: "KRW",
      payMethod: "EASY_PAY",
      easyPay: { easyPayProvider: "TOSSPAY", availablePayMethods: ["CARD"] },
      redirectUrl: `${window.location.origin}/orders/o1`,
      customer: { fullName: "홍길동", phoneNumber: "01000000000" },
    });
    expect(completeCalls()).toHaveLength(1);
    expect(completeCalls()[0][1]?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    expect(await screen.findByText("장바구니가 비어있습니다.")).toBeInTheDocument();
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
  });

  it("성공: 카드는 payMethod CARD로 요청하고 easyPay를 보내지 않는다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER }, { status: 200, body: { status: "PAID" } });
    requestPayment.mockResolvedValue(windowOk);
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    const req = requestPayment.mock.calls[0][0];
    expect(req).toMatchObject({ payMethod: "CARD", channelKey: "channel-card-test" });
    expect(req).not.toHaveProperty("easyPay");
  });

  it("실패: 결제창이 code를 돌려주면 메시지를 보이고 장바구니와 주문 키를 그대로 둔다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER });
    requestPayment.mockResolvedValue({ code: "FAILURE_TYPE_PG", message: "카드 한도 초과" });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("카드 한도 초과");
    expect(push).not.toHaveBeenCalled();
    expect(completeCalls()).toHaveLength(0);
    expect(screen.queryByText("장바구니가 비어있습니다.")).not.toBeInTheDocument();
    expect(sessionStorage.getItem("shop-order-attempt")).not.toBeNull();
  });

  it("성공: 결제가 실패한 뒤 다시 눌러도 같은 키로 보내 같은 대기 주문을 받는다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER }, { status: 200, body: PENDING_ORDER }, { status: 200, body: { status: "PAID" } });
    requestPayment.mockResolvedValueOnce({ code: "PAY_PROCESS_CANCELED" }).mockResolvedValue(windowOk);
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("결제가 취소되었거나 실패했어요.");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(orderCalls()).toHaveLength(2);
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("실패: 결제창을 열다 예외가 나면 안내를 보이고 확정 요청을 하지 않는다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER });
    requestPayment.mockRejectedValue(new Error("script load failed"));
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("결제창을 열지 못했어요");
    expect(completeCalls()).toHaveLength(0);
    expect(push).not.toHaveBeenCalled();
  });

  it("성공: 결제창 결과가 없으면(리디렉션 이동) 결제창을 열었고 폼은 계속 잠겨 있으며 장바구니는 그대로고 오류도 없다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER });
    requestPayment.mockResolvedValue(undefined);
    await renderCart();
    await submit();
    await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1));
    await act(async () => {});
    expect(screen.getByRole("button", { name: "주문 처리 중..." })).toBeDisabled();
    for (const label of ["이름", "연락처", "배송 주소"]) expect(screen.getByLabelText(label)).toHaveAttribute("readonly");
    expect(completeCalls()).toHaveLength(0);
    expect(push).not.toHaveBeenCalled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText("장바구니가 비어있습니다.")).not.toBeInTheDocument();
  });

  it("성공: 리디렉션으로 떠났다가 bfcache로 돌아오면(pageshow persisted) 폼이 다시 풀린다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER });
    requestPayment.mockResolvedValue(undefined);
    await renderCart();
    await submit();
    await screen.findByRole("button", { name: "주문 처리 중..." });
    act(() => {
      window.dispatchEvent(Object.assign(new Event("pageshow"), { persisted: false }));
    });
    expect(screen.getByRole("button", { name: "주문 처리 중..." })).toBeDisabled();
    act(() => {
      window.dispatchEvent(Object.assign(new Event("pageshow"), { persisted: true }));
    });
    expect(await screen.findByRole("button", { name: "주문하기" })).toBeEnabled();
    expect(screen.getByLabelText("이름")).not.toHaveAttribute("readonly");
  });

  it("성공: 주문이 만들어지면 저장된 시도에 orderId가 담긴다(개인정보는 없음)", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER });
    requestPayment.mockResolvedValue({ code: "PAY_PROCESS_CANCELED" });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    const stored = JSON.parse(sessionStorage.getItem("shop-order-attempt")!);
    expect(Object.keys(stored).sort()).toEqual(["fingerprintHash", "key", "orderId"]);
    expect(stored.orderId).toBe("o1");
    expect(JSON.stringify(stored)).not.toContain("홍길동");
  });

  it("실패: 서버가 결제를 확인하지 못하면 폼이 잠기고 주문하기 대신 결제 확인하기만 남으며, 그 버튼은 확정만 다시 요청한다", async () => {
    mockReplies(
      { status: 201, body: PENDING_ORDER },
      { status: 409, body: { code: "PAYMENT_NOT_CONFIRMED", error: "raw" } },
      { status: 200, body: { status: "PAID" } }
    );
    requestPayment.mockResolvedValue(windowOk);
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("아직 결제가 확인되지 않았어요");
    expect(push).not.toHaveBeenCalled();
    expect(screen.queryByText("장바구니가 비어있습니다.")).not.toBeInTheDocument();

    expect(screen.getByRole("status")).toHaveTextContent("이전 결제를 확인하고 있어요");
    expect(screen.getByRole("link", { name: "주문 상세에서 확인하기" })).toHaveAttribute("href", "/orders/o1");
    expect(screen.queryByRole("button", { name: "주문하기" })).not.toBeInTheDocument();
    for (const label of ["이름", "연락처", "배송 주소"]) expect(screen.getByLabelText(label)).toHaveAttribute("readonly");
    for (const name of ["수량 감소", "수량 증가", "삭제"]) expect(screen.getByRole("button", { name })).toBeDisabled();
    for (const name of ["카드", "카카오페이", "네이버페이", "토스페이"]) expect(screen.getByRole("radio", { name })).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "결제 확인하기" }));
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(requestPayment).toHaveBeenCalledTimes(1);
    expect(completeCalls()).toHaveLength(2);
    expect(orderCalls()).toHaveLength(1);
    expect(await screen.findByText("장바구니가 비어있습니다.")).toBeInTheDocument();
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
  });

  it("실패: 결제 확인 중에는 폼을 제출해도 새 주문도 새 결제창도 없고, 확인이 또 실패하면 그대로 남는다", async () => {
    mockReplies(
      { status: 201, body: PENDING_ORDER },
      { status: 409, body: { code: "PAYMENT_NOT_CONFIRMED" } },
      { status: 503, body: { code: "PAYMENT_PROVIDER_UNAVAILABLE" } }
    );
    requestPayment.mockResolvedValue(windowOk);
    await renderCart();
    await submit();
    await screen.findByText("이전 결제를 확인하고 있어요");

    fireEvent.submit(screen.getByLabelText("이름").closest("form")!);
    await act(async () => {});
    expect(orderCalls()).toHaveLength(1);
    expect(requestPayment).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole("button", { name: "결제 확인하기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("결제 확인이 지연되고 있어요");
    expect(screen.getByRole("button", { name: "결제 확인하기" })).toBeEnabled();
    expect(push).not.toHaveBeenCalled();
    expect(orderCalls()).toHaveLength(1);
    expect(requestPayment).toHaveBeenCalledTimes(1);
  });

  it("실패: 결제 확인하기가 401이면 로그인 만료 안내를 보이고 장바구니는 그대로다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER }, { status: 409, body: { code: "PAYMENT_NOT_CONFIRMED" } }, { status: 401, body: {} });
    requestPayment.mockResolvedValue(windowOk);
    await renderCart();
    await submit();
    await screen.findByText("이전 결제를 확인하고 있어요");
    await userEvent.click(screen.getByRole("button", { name: "결제 확인하기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 만료되었어요");
    expect(push).not.toHaveBeenCalled();
  });

  it.each([
    ["PAYMENT_AMOUNT_MISMATCH", 409, "결제 금액이 주문 금액과 달라"],
    ["PAYMENT_NEEDS_REVIEW", 409, "재고 문제로 주문을 확정하지 못했어요"],
    ["PAYMENT_PROVIDER_UNAVAILABLE", 503, "결제 확인이 지연되고 있어요"],
    ["PAYMENT_NOT_CONFIGURED", 503, "결제 준비 중이에요"],
  ])("실패: 확정 응답 %s 는 짧은 한국어 문구로 보인다", async (code, status, text) => {
    mockReplies({ status: 201, body: PENDING_ORDER }, { status, body: { code, error: "raw english" } });
    requestPayment.mockResolvedValue(windowOk);
    await renderCart();
    await submit();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(text);
    expect(alert).not.toHaveTextContent("raw english");
    expect(push).not.toHaveBeenCalled();
  });

  it("실패: 확정 요청이 401이면 로그인 만료 안내를 보이고 장바구니는 그대로다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER }, { status: 401, body: {} });
    requestPayment.mockResolvedValue(windowOk);
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 만료되었어요");
    expect(push).not.toHaveBeenCalled();
  });

  it("성공: 응답이 이미 PAID(재생)면 결제창 없이 장바구니를 비우고 주문으로 이동한다", async () => {
    mockReplies({ status: 200, body: PAID_ORDER });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(requestPayment).not.toHaveBeenCalled();
    expect(await screen.findByText("장바구니가 비어있습니다.")).toBeInTheDocument();
  });

  it("성공: 예전 응답 필드 orderId도 받아들인다", async () => {
    mockReplies({ status: 201, body: { orderId: "o1", status: "PAID" } });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
  });

  it("성공: 응답이 EXPIRED면 저장된 키를 지우고 새 키로 한 번 다시 만든다", async () => {
    mockReplies({ status: 200, body: { ...PAID_ORDER, id: "old", status: "EXPIRED" } }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(orderCalls()).toHaveLength(2);
    expect(keys()[1]).not.toBe(keys()[0]);
    expect(push).not.toHaveBeenCalledWith(expect.stringContaining("old"));
  });

  it("실패: 새 키로 만든 주문도 EXPIRED면 더 시도하지 않고 오류를 보인다", async () => {
    mockReplies({ status: 200, body: { ...PAID_ORDER, status: "EXPIRED" } });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("주문이 만료되었습니다");
    expect(orderCalls()).toHaveLength(2);
    expect(push).not.toHaveBeenCalled();
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
  });

  it("실패: 결제할 수 없는 상태(PAYMENT_REVIEW)나 상태가 없는 응답이면 결제창을 열지 않고 주문 링크를 보이며 저장된 키를 지운다", async () => {
    for (const body of [{ ...PAID_ORDER, status: "PAYMENT_REVIEW" }, { id: "o1" }]) {
      mockReplies({ status: 201, body });
      const view = await renderCart();
      await submit();
      expect(await screen.findByRole("alert")).toHaveTextContent("결제를 진행할 수 없는 상태");
      expect(screen.getByRole("link", { name: "주문 상세에서 확인하기" })).toHaveAttribute("href", "/orders/o1");
      expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
      expect(requestPayment).not.toHaveBeenCalled();
      expect(push).not.toHaveBeenCalled();
      expect(screen.getByRole("button", { name: "주문하기" })).toBeEnabled();
      view.unmount();
    }
  });

  it("성공: 결제할 수 없는 주문 뒤에 다시 눌러도 같은 키로 같은 죽은 주문을 되풀이하지 않고 새 키로 보낸다", async () => {
    mockReplies({ status: 201, body: { ...PAID_ORDER, status: "PAYMENT_REVIEW" } }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[1]).not.toBe(keys()[0]);
    expect(screen.queryByRole("link", { name: "주문 상세에서 확인하기" })).not.toBeInTheDocument();
  });

  it("실패: 대기 주문 응답에 금액이나 주문명이 없으면 결제창을 열지 않는다", async () => {
    mockReplies({ status: 201, body: { id: "o1", status: "PENDING_PAYMENT" } });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("서버 오류로 주문에 실패했습니다");
    expect(requestPayment).not.toHaveBeenCalled();
  });
});

type StoredItem = { productId: string; name: string; price: number; imageUrl: string; quantity: number; optionId?: string; optionLabel?: string };
const stored = (over: Partial<StoredItem> = {}): StoredItem => ({
  productId: "p1",
  name: "린넨 셔츠",
  price: 8000,
  imageUrl: "https://example.com/a.jpg",
  quantity: 1,
  ...over,
});

async function renderCartItems(items: StoredItem[]) {
  localStorage.setItem("shop-cart", JSON.stringify(items));
  const view = render(
    <CartProvider>
      <CartView />
    </CartProvider>
  );
  await userEvent.type(await screen.findByLabelText("이름"), "홍길동");
  await userEvent.type(screen.getByLabelText("연락처"), "010-0000-0000");
  await userEvent.type(screen.getByLabelText("배송 주소"), "서울시 테스트구");
  return view;
}

const sentBody = (n = 0) => JSON.parse(orderCalls()[n][1]?.body as string);
const storedHash = () => JSON.parse(sessionStorage.getItem("shop-order-attempt")!).fingerprintHash;

describe("CartView 옵션 상품", () => {
  it("성공: 옵션 이름을 상품명 아래에 보여주고, 옵션 없는 줄에는 아무것도 더하지 않는다", async () => {
    await renderCartItems([stored({ optionId: "o1", optionLabel: "화이트 / M" }), stored({ productId: "p2", name: "양말" })]);
    expect(screen.getByText("화이트 / M")).toBeInTheDocument();
    expect(screen.getAllByText("린넨 셔츠")).toHaveLength(1);
    expect(screen.getByText("양말").parentElement).toHaveTextContent(/^양말8,000원$/);
  });

  it("성공: 주문 줄에는 옵션이 있을 때만 optionId를 넣는다", async () => {
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCartItems([stored({ optionId: "o1", optionLabel: "화이트 / M", quantity: 2 }), stored({ productId: "p2" })]);
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().items).toEqual([
      { productId: "p1", optionId: "o1", quantity: 2 },
      { productId: "p2", quantity: 1 },
    ]);
    expect(Object.keys(sentBody().items[1])).toEqual(["productId", "quantity"]);
  });

  it("성공: 옵션 없는 장바구니의 멱등 지문은 옵션이 생기기 전과 같다(저장된 해시 고정값)", async () => {
    mockReplies({ status: 500, body: {} });
    await renderCartItems([stored()]);
    await submit();
    await screen.findByRole("alert");
    // sha256 of ["홍길동","010-0000-0000","서울시 테스트구",["p1:1"]], the string built before options existed
    expect(storedHash()).toBe("ac275af49a0af7a0f7a05251bb1767ff7eae1a9c7cd1fe1d7ce437b4373b5ca8");
  });

  it("성공: 옵션이 있으면 지문이 달라지고, 옵션만 바꿔도 새 키로 보낸다", async () => {
    mockReplies({ status: 500, body: {} });
    const first = await renderCartItems([stored({ optionId: "o1", optionLabel: "화이트 / M" })]);
    await submit();
    await screen.findByRole("alert");
    expect(storedHash()).toBe("f28d92c93034000c741880668f58e60e86f122cc939b493ba1bed3cfb5717082");
    first.unmount();
    localStorage.clear();
    await renderCartItems([stored({ optionId: "o2", optionLabel: "화이트 / L" })]);
    await submit();
    await waitFor(() => expect(keys()).toHaveLength(2));
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it("성공: 같은 옵션 장바구니를 다시 보내면 같은 키를 쓴다", async () => {
    mockReplies({ status: 500, body: {} });
    await renderCartItems([stored({ optionId: "o1", optionLabel: "화이트 / M" })]);
    await submit();
    await screen.findByRole("alert");
    await submit();
    await waitFor(() => expect(keys()).toHaveLength(2));
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: 한 상품의 옵션 두 줄은 따로 보이고 따로 지워진다", async () => {
    await renderCartItems([
      stored({ optionId: "o1", optionLabel: "화이트 / M" }),
      stored({ optionId: "o2", optionLabel: "블랙 / M" }),
    ]);
    expect(screen.getAllByText("린넨 셔츠")).toHaveLength(2);
    await userEvent.click(screen.getAllByRole("button", { name: "삭제" })[0]);
    expect(screen.getAllByText("린넨 셔츠")).toHaveLength(1);
    expect(screen.queryByText("화이트 / M")).not.toBeInTheDocument();
    expect(screen.getByText("블랙 / M")).toBeInTheDocument();
  });

  it("성공: 수량 버튼은 같은 상품의 다른 옵션 줄을 건드리지 않는다", async () => {
    await renderCartItems([
      stored({ optionId: "o1", optionLabel: "화이트 / M" }),
      stored({ optionId: "o2", optionLabel: "블랙 / M" }),
    ]);
    await userEvent.click(screen.getAllByRole("button", { name: "수량 증가" })[1]);
    const counts = screen.getAllByRole("button", { name: "수량 증가" }).map((b) => b.previousElementSibling?.textContent);
    expect(counts).toEqual(["1", "2"]);
  });

  it("실패: '옵션을 선택해 주세요.' 400이면 안내와 옵션 없는 상품 링크를 보이고 새 키를 쓴다", async () => {
    mockReplies({ status: 400, body: { error: "옵션을 선택해 주세요." } });
    await renderCartItems([stored({ optionId: "o9", optionLabel: "블랙 / S", productId: "p9", name: "니트" }), stored()]);
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("옵션을 고르지 않은 상품이 있어요");
    expect(screen.getByRole("link", { name: "린넨 셔츠 옵션 선택하러 가기" })).toHaveAttribute("href", "/products/p1");
    expect(screen.queryByRole("link", { name: /니트/ })).not.toBeInTheDocument();
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
    expect(screen.getByRole("button", { name: "주문하기" })).toBeEnabled();
  });

  it("실패: '선택한 옵션을 찾을 수 없어요.' 400이면 안내와 옵션이 담긴 상품 링크를 보인다", async () => {
    mockReplies({ status: 400, body: { error: "선택한 옵션을 찾을 수 없어요." } });
    await renderCartItems([stored({ optionId: "gone", optionLabel: "블랙 / S", productId: "p9", name: "니트" }), stored()]);
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("선택한 옵션이 더 이상 없어요");
    expect(screen.getByRole("link", { name: "니트 옵션 선택하러 가기" })).toHaveAttribute("href", "/products/p9");
    expect(screen.queryByRole("link", { name: /린넨 셔츠/ })).not.toBeInTheDocument();
  });

  it("실패: 품절 400은 서버 문구를 그대로 보이고 옵션 링크는 없다", async () => {
    mockReplies({ status: 409, body: { error: "재고가 부족합니다: 린넨 셔츠 (화이트 / M)" } });
    await renderCartItems([stored({ optionId: "o1", optionLabel: "화이트 / M" })]);
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("재고가 부족합니다: 린넨 셔츠 (화이트 / M)");
    expect(screen.queryByRole("link", { name: /옵션 선택하러 가기/ })).not.toBeInTheDocument();
  });

  it("실패: 다른 오류 문구에는 옵션 링크를 보이지 않는다", async () => {
    mockReplies({ status: 400, body: { error: "주문에 실패했습니다." } });
    await renderCartItems([stored()]);
    await submit();
    await screen.findByRole("alert");
    expect(screen.queryByRole("link", { name: /옵션 선택하러 가기/ })).not.toBeInTheDocument();
  });
});

// ---- price change notice (docs/cart-price-change-design.md) ----

const NOTICE_TITLE = "가격이 바뀐 상품이 있어요. 바뀐 가격을 확인해 주세요.";
const STOP_ERROR = "가격이 바뀌었어요. 위의 안내를 확인한 뒤 주문하기를 다시 눌러 주세요.";
const noticeBox = () => screen.getByText(NOTICE_TITLE).closest('[role="alert"]')!;
const editedTo = (price: number) => (id: string): Reply => ({ status: 200, body: { id, price } });
// The first `n` product lookups see the cart's own price (nothing changed); every later one sees `price`.
function editPriceAfterCalls(n: number, price: number) {
  let calls = 0;
  productReply = (id) => (++calls <= n ? productFromCart(id) : editedTo(price)(id));
}
const settle = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 30));
  });
const storedCart = () => JSON.parse(localStorage.getItem("shop-cart") ?? "[]") as StoredItem[];

// CartView can be hidden while the provider (and so the cart) lives on, like leaving the page does.
function Harness() {
  const [show, setShow] = useState(true);
  const { items, add } = useCart();
  return (
    <>
      <button onClick={() => setShow(false)}>화면 닫기</button>
      <button onClick={() => add({ productId: "p2", name: "양말", price: 1000, imageUrl: "https://example.com/b.jpg" })}>
        양말 담기
      </button>
      <output data-testid="cart-prices">{items.map((i) => `${i.productId}:${i.price}`).join(",")}</output>
      {show && <CartView />}
    </>
  );
}
async function renderHarness(items: StoredItem[], fill = true) {
  localStorage.setItem("shop-cart", JSON.stringify(items));
  const view = render(
    <CartProvider>
      <Harness />
    </CartProvider>
  );
  if (fill) {
    await userEvent.type(await screen.findByLabelText("이름"), "홍길동");
    await userEvent.type(screen.getByLabelText("연락처"), "010-0000-0000");
    await userEvent.type(screen.getByLabelText("배송 주소"), "서울시 테스트구");
  }
  return view;
}
const probe = () => screen.getByTestId("cart-prices").textContent;

describe("CartView 가격 변경 안내 - 장바구니를 열 때", () => {
  it("성공: 담은 뒤 가격이 바뀌었으면 안내(제목, 줄별 옛 가격 → 새 가격)를 보이고 줄 가격·합계·저장값을 새 가격으로 고친다", async () => {
    productReply = editedTo(9000);
    await renderCartItems([stored({ quantity: 2 })]);
    const box = (await screen.findByText(NOTICE_TITLE)).closest('[role="alert"]')!;
    expect(box).toHaveTextContent("린넨 셔츠: 8,000원 → 9,000원");
    expect(screen.getByText("린넨 셔츠").parentElement).toHaveTextContent("9,000원");
    expect(row("상품 금액")).toHaveTextContent("18,000원");
    expect(row("총 주문금액")).toHaveTextContent("21,500원");
    expect(screen.getByRole("button", { name: "수량 증가" }).previousElementSibling).toHaveTextContent("2");
    expect(storedCart()[0]).toMatchObject({ price: 9000, quantity: 2 });
    expect(orderCalls()).toHaveLength(0);
  });

  it("성공: 옵션 줄은 안내에 옵션 이름이 괄호로 붙고, 같은 옵션 id의 가격만 고친다(다른 옵션 줄은 그대로)", async () => {
    productReply = () => ({
      status: 200,
      body: { id: "p1", price: 1, options: [{ id: "o1", price: 9900 }, { id: "o2", price: 9500 }] },
    });
    await renderCartItems([
      stored({ price: 9000, optionId: "o1", optionLabel: "화이트 / M" }),
      stored({ price: 9500, optionId: "o2", optionLabel: "블랙 / M" }),
    ]);
    const box = (await screen.findByText(NOTICE_TITLE)).closest('[role="alert"]')!;
    expect(box).toHaveTextContent("린넨 셔츠 (화이트 / M): 9,000원 → 9,900원");
    expect(box.querySelectorAll("li")).toHaveLength(1);
    expect(storedCart().map((i) => [i.optionId, i.price])).toEqual([["o1", 9900], ["o2", 9500]]);
  });

  it("성공: 상품이 여러 개면 가격이 바뀐 상품의 줄만 안내하고 고친다", async () => {
    productReply = (id) => ({ status: 200, body: { id, price: id === "p1" ? 7000 : 1000 } });
    await renderCartItems([stored(), stored({ productId: "p2", name: "양말", price: 1000 })]);
    const box = (await screen.findByText(NOTICE_TITLE)).closest('[role="alert"]')!;
    expect(box.querySelectorAll("li")).toHaveLength(1);
    expect(box).toHaveTextContent("린넨 셔츠: 8,000원 → 7,000원");
    expect(storedCart().map((i) => [i.productId, i.price])).toEqual([["p1", 7000], ["p2", 1000]]);
  });

  it("성공: 가격이 그대로면 아무 안내도 없고 한 번만 조회한다", async () => {
    await renderCartItems([stored()]);
    await settle();
    expect(screen.queryByText(NOTICE_TITLE)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(productCalls()).toHaveLength(1);
    expect(String(productCalls()[0][0])).toMatch(/\/api\/products\/p1$/);
  });

  it.each([
    ["네트워크 오류", () => new TypeError("Failed to fetch") as Reply],
    ["404(상품이 없어짐)", () => ({ status: 404 }) as Reply],
    ["500", () => ({ status: 500, body: {} }) as Reply],
  ])("실패: 조회가 %s 이면 안내도 오류도 없이 장바구니를 그대로 둔다", async (_name, reply) => {
    productReply = reply;
    await renderCartItems([stored()]);
    await settle();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(storedCart()[0].price).toBe(8000);
    expect(screen.getByRole("button", { name: "주문하기" })).toBeEnabled();
  });

  it("실패: 옵션이 없어졌거나(응답에 그 옵션 없음) 옵션 상품인데 옵션 없이 담긴 줄은 안내하지 않는다", async () => {
    productReply = () => ({ status: 200, body: { id: "p1", price: 1, options: [{ id: "other", price: 1 }] } });
    await renderCartItems([stored({ optionId: "gone", optionLabel: "블랙 / S" }), stored()]);
    await settle();
    expect(screen.queryByText(NOTICE_TITLE)).not.toBeInTheDocument();
    expect(storedCart().map((i) => i.price)).toEqual([8000, 8000]);
  });

  it("성공: 안내가 뜨면 안내 영역을 화면으로 스크롤한다(scrollIntoView가 없는 환경에서는 오류 없이 넘어간다)", async () => {
    const scroll = vi.fn();
    Object.defineProperty(Element.prototype, "scrollIntoView", { value: scroll, configurable: true, writable: true });
    try {
      productReply = editedTo(9000);
      await renderCartItems([stored()]);
      await screen.findByText(NOTICE_TITLE);
      expect(scroll).toHaveBeenCalledWith({ block: "nearest" });
      expect(scroll.mock.contexts[0]).toBe(noticeBox());
    } finally {
      delete (Element.prototype as unknown as Record<string, unknown>).scrollIntoView;
    }
  });

  it("성공: 상품마다 한 번씩만 조회한다(같은 상품의 옵션 두 줄은 한 번)", async () => {
    await renderHarness(
      [
        stored({ optionId: "o1", optionLabel: "A" }),
        stored({ optionId: "o2", optionLabel: "B" }),
        stored({ productId: "p2", name: "양말", price: 1000 }),
      ],
      false
    );
    await waitFor(() => expect(productCalls()).toHaveLength(2));
    await settle();
    expect(productCalls().map(([url]) => productIdOf(url)).sort()).toEqual(["p1", "p2"]);
  });

  it("성공: 장바구니를 연 뒤 줄을 담거나 지워도 다시 조회하지 않는다", async () => {
    await renderHarness([stored()], false);
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    await userEvent.click(screen.getByRole("button", { name: "양말 담기" }));
    expect(probe()).toBe("p1:8000,p2:1000");
    await userEvent.click(screen.getAllByRole("button", { name: "삭제" })[0]);
    expect(probe()).toBe("p2:1000");
    await settle();
    expect(productCalls()).toHaveLength(1);
  });

  it("실패: 빈 장바구니로는 조회하지 않고, 처음 담긴 때 한 번만 조회한다", async () => {
    await renderHarness([], false);
    expect(await screen.findByText("장바구니가 비어있습니다.")).toBeInTheDocument();
    await settle();
    expect(productCalls()).toHaveLength(0);
    await userEvent.click(screen.getByRole("button", { name: "양말 담기" }));
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    await userEvent.click(screen.getByRole("button", { name: "양말 담기" }));
    await settle();
    expect(productCalls()).toHaveLength(1);
  });
});

function holdProductReply(...heldCalls: number[]) {
  const held: ((r: Reply) => void)[] = [];
  let n = 0;
  productReply = (id) => (heldCalls.includes(++n) ? new Promise<Reply>((r) => held.push(r)) : productFromCart(id));
  return held;
}
const lateChange = (price: number): Reply => ({ status: 200, body: { id: "p1", price } });

describe("CartView 가격 확인이 늦게 끝날 때", () => {
  it("성공: 새 확인이 시작된 뒤 늦게 온 옛 확인 결과는 장바구니도 안내도 바꾸지 않고, 옛 요청은 취소된다", async () => {
    const held = holdProductReply(1);
    mockReplies({ status: 500, body: {} });
    await renderCartItems([stored()]);
    await waitFor(() => expect(held).toHaveLength(1));
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("서버 오류로 주문에 실패했습니다");
    expect(productCalls()[0][1]?.signal).toHaveProperty("aborted", true);
    const spies = [vi.spyOn(console, "error").mockImplementation(() => {}), vi.spyOn(console, "warn").mockImplementation(() => {})];
    try {
      await act(async () => held[0](lateChange(12345)));
      await settle();
      expect(screen.queryByText(NOTICE_TITLE)).not.toBeInTheDocument();
      expect(storedCart()[0].price).toBe(8000);
      expect(screen.getByText("린넨 셔츠").parentElement).toHaveTextContent("8,000원");
      for (const spy of spies) expect(spy).not.toHaveBeenCalled();
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });

  it("성공: 화면이 닫힌 뒤 늦게 온 확인 결과는 장바구니를 바꾸지 않고 경고도 없으며, 요청은 취소된다", async () => {
    const held = holdProductReply(1);
    await renderHarness([stored()], false);
    await waitFor(() => expect(held).toHaveLength(1));
    await userEvent.click(screen.getByRole("button", { name: "화면 닫기" }));
    expect(productCalls()[0][1]?.signal).toHaveProperty("aborted", true);
    const spies = [vi.spyOn(console, "error").mockImplementation(() => {}), vi.spyOn(console, "warn").mockImplementation(() => {})];
    try {
      await act(async () => held[0](lateChange(12345)));
      await settle();
      expect(probe()).toBe("p1:8000");
      for (const spy of spies) expect(spy).not.toHaveBeenCalled();
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });

  it("실패: 주문 전 가격 확인을 기다리는 동안 화면이 닫히면 주문도 결제창도 없다", async () => {
    const held = holdProductReply(2);
    mockReplies({ status: 201, body: PENDING_ORDER });
    await renderHarness([stored()]);
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    await submit();
    await waitFor(() => expect(held).toHaveLength(1));
    await userEvent.click(screen.getByRole("button", { name: "화면 닫기" }));
    await act(async () => held[0](productFromCart("p1")));
    await settle();
    expect(orderCalls()).toHaveLength(0);
    expect(requestPayment).not.toHaveBeenCalled();
  });

  it("실패: 주문 전 가격 확인을 기다리는 동안 bfcache로 복원되면(pageshow persisted) 그 주문은 이어지지 않고 폼이 풀린다", async () => {
    const held = holdProductReply(2);
    mockReplies({ status: 201, body: PENDING_ORDER });
    await renderCart();
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    await submit();
    await waitFor(() => expect(held).toHaveLength(1));
    act(() => {
      window.dispatchEvent(Object.assign(new Event("pageshow"), { persisted: true }));
    });
    expect(await screen.findByRole("button", { name: "주문하기" })).toBeEnabled();
    await act(async () => held[0](productFromCart("p1")));
    await settle();
    expect(orderCalls()).toHaveLength(0);
    expect(requestPayment).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "주문하기" })).toBeEnabled();
  });

  it("성공: bfcache 복원 뒤 새로 누른 주문이 확인 중이면, 옛 주문의 마무리가 그 주문의 잠금을 풀지 않는다", async () => {
    const held = holdProductReply(2, 3);
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    await submit();
    await waitFor(() => expect(held).toHaveLength(1));
    act(() => {
      window.dispatchEvent(Object.assign(new Event("pageshow"), { persisted: true }));
    });
    await userEvent.click(await screen.findByRole("button", { name: "주문하기" }));
    await waitFor(() => expect(held).toHaveLength(2));
    await settle();
    // The first submit's check was cancelled by the second one and has ended by now; the second still owns the lock.
    expect(screen.getByRole("button", { name: "주문 처리 중..." })).toBeDisabled();
    expect(screen.getByLabelText("이름")).toHaveAttribute("readonly");
    expect(orderCalls()).toHaveLength(0);
    await act(async () => held[1](productFromCart("p1")));
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(orderCalls()).toHaveLength(1);
  });

  it("성공: 새 확인이 시작되면 이전 확인의 요청 signal을 중단하고 새 확인의 signal은 그대로 둔다", async () => {
    holdProductReply(1);
    mockReplies({ status: 500, body: {} });
    await renderCart();
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    expect(productCalls()[0][1]?.signal).toHaveProperty("aborted", false);
    await submit();
    await screen.findByRole("alert");
    expect(productCalls()[0][1]?.signal).toHaveProperty("aborted", true);
    expect(productCalls()[1][1]?.signal).toHaveProperty("aborted", false);
  });
});

describe("CartView 가격 변경 안내 - 주문하기를 누를 때", () => {
  it("성공: 그 사이 가격이 바뀌었으면 주문을 만들지 않고 안내와 오류 문구를 보이며, 장바구니 가격을 고치고 버튼을 다시 연다. 다시 누르면 주문한다", async () => {
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    productReply = editedTo(9000);
    await submit();
    expect(await screen.findByText(NOTICE_TITLE)).toBeInTheDocument();
    expect(noticeBox()).toHaveTextContent("린넨 셔츠: 8,000원 → 9,000원");
    expect(screen.getAllByRole("alert").map((a) => a.textContent)).toContain(STOP_ERROR);
    expect(screen.getAllByRole("alert")).toHaveLength(2);
    expect(orderCalls()).toHaveLength(0);
    expect(requestPayment).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
    expect(row("총 주문금액")).toHaveTextContent("12,500원");
    expect(screen.getByRole("button", { name: "주문하기" })).toBeEnabled();
    expect(screen.getByLabelText("이름")).not.toHaveAttribute("readonly");

    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(orderCalls()).toHaveLength(1);
    expect(sentBody().items).toEqual([{ productId: "p1", quantity: 1 }]);
  });

  it("성공: 가격만 바뀐 경우 멱등 키는 그대로다(가격은 키 지문에 들어가지 않는다)", async () => {
    mockReplies({ status: 500, body: {} }, { status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    expect(keys()).toHaveLength(1);
    productReply = editedTo(9000);
    await submit();
    await screen.findByText(NOTICE_TITLE);
    expect(orderCalls()).toHaveLength(1);
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()).toHaveLength(2);
    expect(keys()[1]).toBe(keys()[0]);
  });

  it.each([
    ["네트워크 오류", () => new TypeError("Failed to fetch") as Reply],
    ["404", () => ({ status: 404 }) as Reply],
    ["500", () => ({ status: 500, body: {} }) as Reply],
  ])("성공: 가격 조회가 %s 로 실패해도 주문은 같은 내용·같은 형식의 키로 만들어진다", async (_name, reply) => {
    productReply = reply;
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(orderCalls()).toHaveLength(1);
    expect(sentBody()).toEqual({
      customerName: "홍길동",
      customerPhone: "010-0000-0000",
      customerAddress: "서울시 테스트구",
      items: [{ productId: "p1", quantity: 1 }],
    });
    expect(keys()[0]).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    expect(screen.queryByText(NOTICE_TITLE)).not.toBeInTheDocument();
  });

  it("성공: 가격 조회가 5초가 지나도 끝나지 않으면 기다리다 포기하고 주문한다", async () => {
    productReply = () => new Promise<Reply>(() => {});
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await submit();
    expect(orderCalls()).toHaveLength(0);
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"), { timeout: 8000 });
    expect(orderCalls()).toHaveLength(1);
    expect(productCalls().every(([, init]) => (init?.signal as AbortSignal).aborted)).toBe(true);
  }, 15_000);

  it("실패: 입력 검사에 걸리면 가격 조회도 주문도 하지 않는다", async () => {
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    await userEvent.clear(screen.getByLabelText("이름"));
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("이름, 연락처, 주소를 모두 입력해주세요.");
    await userEvent.type(screen.getByLabelText("이름"), "홍길동");
    await userEvent.clear(screen.getByLabelText("연락처"));
    await userEvent.type(screen.getByLabelText("연락처"), "abc");
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("연락처는 숫자 9~15자리로 입력해 주세요.");
    await settle();
    expect(productCalls()).toHaveLength(1);
    expect(orderCalls()).toHaveLength(0);
  });

  it("실패: 결제 설정이 없으면 가격 조회 없이 안내만 한다", async () => {
    clearPaymentEnv();
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    fireEvent.submit(screen.getByRole("button", { name: "주문하기" }).closest("form")!);
    expect(await screen.findByRole("alert")).toHaveTextContent("결제 준비 중이에요");
    await settle();
    expect(productCalls()).toHaveLength(1);
    expect(orderCalls()).toHaveLength(0);
  });

  it("성공: 가격 변경 안내는 주문하기를 다시 누르는 순간 사라진다", async () => {
    productReply = editedTo(9000);
    mockReplies({ status: 500, body: { error: "서버 오류" } });
    await renderCart();
    await screen.findByText(NOTICE_TITLE);
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("서버 오류");
    expect(screen.queryByText(NOTICE_TITLE)).not.toBeInTheDocument();
    expect(orderCalls()).toHaveLength(1);
  });

  it("실패: 안내가 떠 있는 채로 가격이 또 바뀌면 다시 멈추고 새 안내로 바꾼다", async () => {
    productReply = editedTo(9000);
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await screen.findByText(NOTICE_TITLE);
    productReply = editedTo(9500);
    await submit();
    await waitFor(() => expect(noticeBox()).toHaveTextContent("린넨 셔츠: 9,000원 → 9,500원"));
    expect(orderCalls()).toHaveLength(0);
  });

  it.each([
    ["50,000원 미만에서 이상으로(배송비 무료가 됨)", 49000, 50000, "무료", "50,000원"],
    ["50,000원 이상에서 미만으로(배송비가 붙음)", 50000, 49000, "3,500원", "52,500원"],
  ])("성공: 가격이 바뀌어 배송비 기준을 넘으면 %s, 다음 주문의 기준 금액도 새 총액이다", async (_name, from, to, shipping, total) => {
    const serverTotal = Number(total.replace(/\D/g, ""));
    mockReplies({ status: 201, body: { ...PENDING_ORDER, totalAmount: serverTotal } }, { status: 200, body: { status: "PAID" } });
    requestPayment.mockResolvedValue(windowOk);
    await renderCartItems([stored({ price: from })]);
    await waitFor(() => expect(productCalls()).toHaveLength(1));
    productReply = editedTo(to);
    await submit();
    await screen.findByText(NOTICE_TITLE);
    expect(row("배송비")).toHaveTextContent(shipping);
    expect(row("총 주문금액")).toHaveTextContent(total);
    expect(orderCalls()).toHaveLength(0);

    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(screen.queryByText(/주문 금액이 장바구니와 달라요/)).not.toBeInTheDocument();
    expect(requestPayment).toHaveBeenCalledWith(expect.objectContaining({ totalAmount: serverTotal }));
  });
});

const MISMATCH_ORDER = { ...PENDING_ORDER, totalAmount: 12500 };
const mismatchText = (won: string) => `주문 금액이 장바구니와 달라요. 결제할 금액은 ${won}이에요.`;
const payButton = (won: string) => screen.queryByRole("button", { name: `${won} 결제하기` });

// Presses 주문하기 with an 8,000원 cart (11,500원 on screen) and a server order of `order`; the third product lookup
// (the refresh after the mismatch) sees the price edited to 9,000원, which is what the server charged for.
// `refreshPrice` null: the refresh after the mismatch finds nothing changed, so the cart still shows 8,000원 (11,500원).
let refreshPrice: number | null = 9000;
async function intoMismatch(order: object = MISMATCH_ORDER, won = "12,500원", ...rest: Reply[]) {
  if (refreshPrice === null) productReply = productFromCart;
  else editPriceAfterCalls(2, refreshPrice);
  mockReplies({ status: 201, body: order }, ...rest);
  await renderCartItems([stored()]);
  await submit();
  await screen.findByText(mismatchText(won));
}

describe("CartView 주문 금액 확인(서버 총액이 화면과 다를 때)", () => {
  it("성공: 서버 총액이 다르면 결제창을 열지 않고 안내와 금액 버튼을 보이며, 주문은 취소하지 않고 시도에 orderId를 남기고, 가격 조회를 한 번 더 한다", async () => {
    await intoMismatch();
    expect(payButton("12,500원")).toBeEnabled();
    expect(requestPayment).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
    // Only the one order request; nothing cancels or reorders.
    const others = vi.mocked(fetch).mock.calls.filter(([url]) => productIdOf(url) === undefined);
    expect(others.map(([url, init]) => [String(url), init?.method])).toEqual([["http://localhost:8080/api/orders", "POST"]]);
    expect(JSON.parse(sessionStorage.getItem("shop-order-attempt")!).orderId).toBe("o1");
    // The refresh: a third lookup that corrects the cart and says so; the mismatch message stays (a price edit does not hide it).
    await waitFor(() => expect(productCalls()).toHaveLength(3));
    expect(await screen.findByText(NOTICE_TITLE)).toBeInTheDocument();
    expect(storedCart()[0].price).toBe(9000);
    expect(screen.getByText(mismatchText("12,500원"))).toBeInTheDocument();
    expect(payButton("12,500원")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "주문하기" })).toBeEnabled();
  });

  it.each([
    ["더 큼", 11501, "11,501원"],
    ["더 작음", 11499, "11,499원"],
    ["배송비 없는 상품 금액만", 8000, "8,000원"],
    ["훨씬 큼", 99999, "99,999원"],
  ])("실패: 서버 총액이 화면 총 주문금액(11,500원)과 %s 이면 결제창을 열지 않는다", async (_name, serverTotal, won) => {
    await intoMismatch({ ...PENDING_ORDER, totalAmount: serverTotal }, won);
    expect(payButton(won)).toBeInTheDocument();
    expect(requestPayment).not.toHaveBeenCalled();
  });

  it("성공: 서버 총액이 화면 총 주문금액(상품 금액 + 배송비)과 같으면 안내 없이 바로 결제창을 연다", async () => {
    mockReplies({ status: 201, body: PENDING_ORDER }, { status: 200, body: { status: "PAID" } });
    requestPayment.mockResolvedValue(windowOk);
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(requestPayment).toHaveBeenCalledTimes(1);
    expect(requestPayment).toHaveBeenCalledWith(expect.objectContaining({ totalAmount: 11500 }));
    expect(screen.queryByText(/주문 금액이 장바구니와 달라요/)).not.toBeInTheDocument();
  });

  it("성공: 무료 배송 금액(50,000원 이상)에서는 상품 금액 그대로가 기준이다", async () => {
    mockReplies({ status: 201, body: { ...PENDING_ORDER, totalAmount: 60000 } }, { status: 200, body: { status: "PAID" } });
    requestPayment.mockResolvedValue(windowOk);
    await renderCartItems([stored({ price: 30000, quantity: 2 })]);
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(requestPayment).toHaveBeenCalledWith(expect.objectContaining({ totalAmount: 60000 }));
  });

  it("성공: 이미 결제된 주문(PAID)이 다른 총액으로 돌아와도 금액 확인 없이 바로 결제 완료 흐름으로 간다", async () => {
    mockReplies({ status: 200, body: { ...PAID_ORDER, totalAmount: 99999 } });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(requestPayment).not.toHaveBeenCalled();
    expect(screen.queryByText(/주문 금액이 장바구니와 달라요/)).not.toBeInTheDocument();
    expect(await screen.findByText("장바구니가 비어있습니다.")).toBeInTheDocument();
  });

  it("실패: 금액이 숫자가 아닌 대기 주문은 금액 확인이 아니라 기존 서버 오류 안내로 간다", async () => {
    mockReplies({ status: 201, body: { id: "o1", status: "PENDING_PAYMENT", orderName: "린넨 셔츠", totalAmount: "12500" } });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("서버 오류로 주문에 실패했습니다");
    expect(screen.queryByText(/주문 금액이 장바구니와 달라요/)).not.toBeInTheDocument();
    expect(requestPayment).not.toHaveBeenCalled();
  });

  it("성공: 금액 버튼을 누르면 서버의 id·금액·주문명으로 결제창을 열고(새 주문 없이), 확정되면 장바구니를 비우고 이동한다", async () => {
    refreshPrice = null;
    requestPayment.mockResolvedValue(windowOk);
    await intoMismatch(MISMATCH_ORDER, "12,500원", { status: 200, body: { status: "PAID" } });
    await userEvent.click(payButton("12,500원")!);
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(requestPayment).toHaveBeenCalledTimes(1);
    expect(requestPayment).toHaveBeenCalledWith(
      expect.objectContaining({ paymentId: "o1", orderName: "린넨 셔츠", totalAmount: 12500, currency: "KRW" })
    );
    expect(orderCalls()).toHaveLength(1);
    expect(completeCalls()).toHaveLength(1);
    expect(await screen.findByText("장바구니가 비어있습니다.")).toBeInTheDocument();
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
  });

  it("실패: 금액 버튼으로 연 결제창이 실패하면 기존 실패 문구를 보이고 버튼과 장바구니를 그대로 두며, 다시 누를 수 있다", async () => {
    refreshPrice = null;
    requestPayment.mockResolvedValueOnce({ code: "PAY_PROCESS_CANCELED" }).mockResolvedValue(windowOk);
    await intoMismatch(MISMATCH_ORDER, "12,500원", { status: 200, body: { status: "PAID" } });
    await userEvent.click(payButton("12,500원")!);
    expect(await screen.findByText(/결제가 취소되었거나 실패했어요\. 다시 시도하거나 주문내역에서 결제할 수 있어요\./)).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
    expect(screen.queryByText("장바구니가 비어있습니다.")).not.toBeInTheDocument();
    expect(payButton("12,500원")).toBeEnabled();
    expect(orderCalls()).toHaveLength(1);

    await userEvent.click(payButton("12,500원")!);
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(requestPayment).toHaveBeenCalledTimes(2);
    expect(orderCalls()).toHaveLength(1);
  });

  it("실패: 금액 버튼을 두 번 눌러도 결제창은 한 번만 열린다", async () => {
    refreshPrice = null;
    requestPayment.mockReturnValue(new Promise(() => {}));
    await intoMismatch();
    const button = payButton("12,500원")!;
    await userEvent.dblClick(button);
    await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1));
    await settle();
    expect(requestPayment).toHaveBeenCalledTimes(1);
    expect(button).toBeDisabled();
    expect(orderCalls()).toHaveLength(1);
  });

  it("실패: 금액 버튼 뒤 결제 확인이 401이면 로그인 만료 안내를 보이고 장바구니는 그대로다", async () => {
    refreshPrice = null;
    requestPayment.mockResolvedValue(windowOk);
    await intoMismatch(MISMATCH_ORDER, "12,500원", { status: 401, body: {} });
    await userEvent.click(payButton("12,500원")!);
    expect(await screen.findByText("로그인이 만료되었어요. 다시 로그인해 주세요.")).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
    expect(screen.queryByText("장바구니가 비어있습니다.")).not.toBeInTheDocument();
  });

  it("성공: 수량을 바꾸면 금액 안내와 버튼이 사라진다", async () => {
    await intoMismatch();
    await userEvent.click(screen.getByRole("button", { name: "수량 증가" }));
    expect(screen.queryByText(/주문 금액이 장바구니와 달라요/)).not.toBeInTheDocument();
    expect(payButton("12,500원")).not.toBeInTheDocument();
  });

  it("성공: 줄을 지우면 금액 안내와 버튼이 사라진다", async () => {
    mockReplies({ status: 201, body: { ...PENDING_ORDER, totalAmount: 20000 } });
    await renderCartItems([stored(), stored({ productId: "p2", name: "양말", price: 5000 })]);
    await submit();
    await screen.findByText(mismatchText("20,000원"));
    await userEvent.click(screen.getAllByRole("button", { name: "삭제" })[0]);
    expect(screen.queryByText(/주문 금액이 장바구니와 달라요/)).not.toBeInTheDocument();
    expect(payButton("20,000원")).not.toBeInTheDocument();
  });

  it("성공: 주문하기를 다시 누르면 금액 안내와 버튼이 바로 사라진다", async () => {
    await intoMismatch(MISMATCH_ORDER, "12,500원", { status: 500, body: { error: "서버 오류" } });
    await submit();
    expect(await screen.findByText("서버 오류")).toBeInTheDocument();
    expect(screen.queryByText(/주문 금액이 장바구니와 달라요/)).not.toBeInTheDocument();
    expect(payButton("12,500원")).not.toBeInTheDocument();
  });

  it.each([
    ["이름", "김"],
    ["연락처", "1"],
    ["배송 주소", "호"],
  ])("성공: %s 를 고치면 금액 안내와 버튼이 사라진다(다른 받는 사람·주소로 결제하지 않도록)", async (label, typed) => {
    await intoMismatch();
    await userEvent.type(screen.getByLabelText(label), typed);
    expect(screen.queryByText(/주문 금액이 장바구니와 달라요/)).not.toBeInTheDocument();
    expect(payButton("12,500원")).not.toBeInTheDocument();
  });

  it("실패: 공백만 덧붙인 입력은 고친 것으로 보지 않아 금액 안내가 남는다", async () => {
    await intoMismatch();
    await userEvent.type(screen.getByLabelText("배송 주소"), "   ");
    expect(screen.getByText(mismatchText("12,500원"))).toBeInTheDocument();
    expect(payButton("12,500원")).toBeInTheDocument();
  });

  it("성공: 가격만 새로 고쳐져도(수량·입력 그대로) 금액 안내와 버튼은 남는다", async () => {
    await intoMismatch();
    await screen.findByText(NOTICE_TITLE);
    expect(storedCart()[0].price).toBe(9000);
    expect(screen.getByText(mismatchText("12,500원"))).toBeInTheDocument();
    expect(payButton("12,500원")).toBeInTheDocument();
  });
});

describe("CartView 금액 확인 후 결제창 직전의 만료 확인", () => {
  const expiresIn = (ms: number) => ({ ...MISMATCH_ORDER, expiresAt: new Date(Date.now() + ms).toISOString() });
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2030-01-01T00:00:00Z"));
  });
  afterEach(() => vi.useRealTimers());

  it.each([
    ["이미 지남", -60_000],
    ["지금", 0],
    ["29.999초 남음(30초 미만)", 29_999],
  ])("실패: 주문 만료가 %s 이면 결제창을 열지 않고 안내를 지우며 저장된 시도를 지우고 다시 주문하라고 한다", async (_name, ms) => {
    requestPayment.mockResolvedValue(windowOk);
    await intoMismatch(expiresIn(ms), "12,500원", { status: 201, body: PAID_ORDER });
    await userEvent.click(payButton("12,500원")!);
    expect(await screen.findByText("주문 시간이 지났어요. 다시 주문해 주세요.")).toBeInTheDocument();
    expect(requestPayment).not.toHaveBeenCalled();
    expect(screen.queryByText(/주문 금액이 장바구니와 달라요/)).not.toBeInTheDocument();
    expect(payButton("12,500원")).not.toBeInTheDocument();
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
    expect(orderCalls()).toHaveLength(1);
    expect(screen.getByRole("button", { name: "주문하기" })).toBeEnabled();

    // The next press starts over with a fresh key.
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it.each([
    ["정확히 30초 남음", () => expiresIn(30_000)],
    ["10분 남음", () => expiresIn(600_000)],
    ["만료 시각이 null", () => ({ ...MISMATCH_ORDER, expiresAt: null })],
    ["만료 시각이 없음", () => ({ ...MISMATCH_ORDER, expiresAt: undefined })],
    ["만료 시각을 읽을 수 없음", () => ({ ...MISMATCH_ORDER, expiresAt: "not-a-date" })],
  ])("성공: 주문 만료가 %s 이면 결제창을 연다", async (_name, makeOrder) => {
    requestPayment.mockResolvedValue(windowOk);
    await intoMismatch(makeOrder(), "12,500원", { status: 200, body: { status: "PAID" } });
    await userEvent.click(payButton("12,500원")!);
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(requestPayment).toHaveBeenCalledTimes(1);
    expect(screen.queryByText("주문 시간이 지났어요. 다시 주문해 주세요.")).not.toBeInTheDocument();
  });
});
