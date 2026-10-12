import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CartProvider } from "@/cart/CartContext";
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

// Replies are consumed in order; the last one repeats.
function mockReplies(...replies: Reply[]) {
  let n = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => {
      const r = replies[Math.min(n++, replies.length - 1)];
      if (r instanceof Error) throw r;
      return {
        ok: r.status >= 200 && r.status < 300,
        status: r.status,
        json: r.json ?? (async () => r.body),
      };
    })
  );
}

const keys = () => vi.mocked(fetch).mock.calls.map(([, init]) => (init?.headers as Record<string, string>)["Idempotency-Key"]);
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
    expect(vi.mocked(fetch).mock.calls[0][1]?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
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
    let finish!: (v: unknown) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise((r) => (finish = r))));
    await renderCart();
    await submit();
    for (const label of ["이름", "연락처", "배송 주소"]) expect(screen.getByLabelText(label)).toHaveAttribute("readonly");
    for (const name of ["수량 감소", "수량 증가", "삭제"]) expect(screen.getByRole("button", { name })).toBeDisabled();
    finish({ ok: false, status: 400, json: async () => ({ error: "재고가 부족합니다." }) });
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
    expect(vi.mocked(fetch).mock.calls[0][1]?.signal).toHaveProperty("aborted", false);
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
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
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
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("실패: 입력이 비어 있으면 요청하지 않는다", async () => {
    mockReplies({ status: 201, body: PAID_ORDER });
    await renderCart();
    await userEvent.clear(screen.getByLabelText("이름"));
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("이름, 연락처, 주소를 모두 입력해주세요.");
    expect(fetch).not.toHaveBeenCalled();
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
      expect(fetch).not.toHaveBeenCalled();
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
      expect(JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body)).customerPhone).toBe(phone);
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
    expect(fetch).not.toHaveBeenCalled();
    expect(requestPayment).not.toHaveBeenCalled();
  });

  it("실패: 채널 키가 하나도 없으면 주문을 만들지 않는다", async () => {
    clearPaymentEnv();
    stubPaymentEnv({ NEXT_PUBLIC_PORTONE_STORE_ID: "store-test-0000" });
    mockReplies({ status: 201, body: PENDING_ORDER });
    await renderCart();
    expect(screen.getByRole("status")).toHaveTextContent("결제 준비 중이에요");
    expect(screen.getByRole("button", { name: "주문하기" })).toBeDisabled();
    expect(fetch).not.toHaveBeenCalled();
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
