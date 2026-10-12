import { describe, it, expect, vi, beforeEach } from "vitest";
import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import OrderComplete from "./page";
import { FAKE_PAYMENT_ENV, clearPaymentEnv, stubPaymentEnv } from "@/test-utils/paymentEnv";

const mocks = vi.hoisted(() => ({
  query: "",
  logout: vi.fn(),
  replace: vi.fn(),
  clearCart: vi.fn(),
  items: [] as unknown[],
  requestPayment: vi.fn(),
}));
vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "o1" }),
  useSearchParams: () => new URLSearchParams(mocks.query),
  useRouter: () => ({ push: vi.fn(), replace: mocks.replace }),
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => ({ session: { token: "fake-token", role: "USER", username: "buyer" }, ready: true }),
  useAuth: () => ({ logout: mocks.logout }),
}));
vi.mock("@/cart/CartContext", () => ({ useCart: () => ({ clear: mocks.clearCart, items: mocks.items }) }));
vi.mock("@portone/browser-sdk/v2", () => ({ requestPayment: mocks.requestPayment }));

const order = {
  id: "o1",
  totalAmount: 16000,
  customerName: "홍길동",
  customerPhone: "010-0000-0000",
  customerAddress: "서울시 테스트구",
  items: [{ id: "i1", quantity: 2, price: 8000, productName: "린넨 셔츠" }],
};
const future = () => new Date(Date.now() + 12 * 60_000 + 5_000).toISOString();
const pending = (extra: object = {}) => ({ ...order, status: "PENDING_PAYMENT", expiresAt: future(), ...extra });

const reply = (status: number, body: unknown = {}) => ({ ok: status >= 200 && status < 300, status, json: async () => body });

// GET replies come from `gets` in order (the last one repeats); the complete POST replies with `complete`.
function mockApi(gets: unknown[], complete: { status: number; body?: unknown } = { status: 200, body: { status: "PAID" } }) {
  let n = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url: string, init?: RequestInit) =>
      init?.method === "POST" ? reply(complete.status, complete.body) : reply(200, gets[Math.min(n++, gets.length - 1)])
    )
  );
}
const completeCalls = () => vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith("/payment/complete"));
const windowOk = { transactionType: "PAYMENT", txId: "t", paymentId: "o1" };
const clickPay = async () => userEvent.click(await screen.findByRole("button", { name: "결제하기" }));

beforeEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  sessionStorage.clear();
  mocks.query = "";
  mocks.items = [];
  mocks.logout.mockClear();
  mocks.replace.mockClear();
  mocks.clearCart.mockClear();
  mocks.requestPayment.mockReset();
  stubPaymentEnv(FAKE_PAYMENT_ENV);
});

describe("주문 상태 문구와 결제하기 버튼", () => {
  it("성공: 상태별 문구를 보인다", async () => {
    for (const [status, text] of [
      ["PENDING_PAYMENT", "결제 대기"],
      ["PAID", "결제 완료"],
      ["EXPIRED", "주문 만료(결제하지 않아 취소됨)"],
      ["PAYMENT_REVIEW", "확인 중(결제 확인 후 취소·환불을 안내해 드려요)"],
    ]) {
      mockApi([{ ...order, status, expiresAt: future() }]);
      const { unmount } = render(<OrderComplete />);
      expect(await screen.findByText(`주문 상태: ${text}`)).toBeInTheDocument();
      unmount();
    }
  });

  it("실패: 상태가 없는 구버전 응답은 상태 문구도 결제 버튼도 보이지 않는다", async () => {
    mockApi([order]);
    render(<OrderComplete />);
    await screen.findByText("주문번호 o1");
    expect(screen.queryByText(/주문 상태/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "결제하기" })).not.toBeInTheDocument();
  });

  it("성공: 결제 대기이고 기한이 남았으면 남은 시간과 결제수단, 결제하기 버튼을 보인다", async () => {
    mockApi([pending()]);
    render(<OrderComplete />);
    expect(await screen.findByRole("button", { name: "결제하기" })).toBeEnabled();
    expect(screen.getByText("남은 시간 13분")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "카드" })).toBeChecked();
    for (const name of ["카카오페이", "네이버페이", "토스페이"]) expect(screen.getByRole("radio", { name })).toBeInTheDocument();
  });

  it("성공: 채널 키가 있는 결제수단만 고르게 한다", async () => {
    stubPaymentEnv({ NEXT_PUBLIC_PORTONE_CHANNEL_KEY_NAVERPAY: "", NEXT_PUBLIC_PORTONE_CHANNEL_KEY_TOSSPAY: "" });
    mockApi([pending()]);
    render(<OrderComplete />);
    await screen.findByRole("button", { name: "결제하기" });
    expect(screen.getAllByRole("radio").map((r) => r.closest("label")?.textContent)).toEqual(["카드", "카카오페이"]);
  });

  it("실패: 결제 대기여도 기한이 지났으면 결제하기 버튼 대신 안내를 보인다", async () => {
    mockApi([pending({ expiresAt: new Date(Date.now() - 1000).toISOString() })]);
    render(<OrderComplete />);
    expect(await screen.findByText(/결제 가능 시간이 지났어요/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "결제하기" })).not.toBeInTheDocument();
  });

  it("실패: 결제 완료·만료·확인 중 주문에는 결제하기 버튼이 없다", async () => {
    for (const status of ["PAID", "EXPIRED", "PAYMENT_REVIEW"]) {
      mockApi([{ ...order, status, expiresAt: future() }]);
      const { unmount } = render(<OrderComplete />);
      await screen.findByText("주문번호 o1");
      expect(screen.queryByRole("button", { name: "결제하기" })).not.toBeInTheDocument();
      unmount();
    }
  });

  it("실패: 결제 설정이 없으면 결제하기 대신 준비 중 안내를 보인다", async () => {
    clearPaymentEnv();
    mockApi([pending()]);
    render(<OrderComplete />);
    expect(await screen.findByText(/결제 준비 중이에요/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "결제하기" })).not.toBeInTheDocument();
  });
});

describe("결제하기 동작", () => {
  it("성공: 주문 id·서버 금액·고른 결제수단으로 결제창을 열고, 성공하면 확정 요청 뒤 다시 불러온다", async () => {
    mockApi([pending(), { ...order, status: "PAID" }]);
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await userEvent.click(await screen.findByRole("radio", { name: "카카오페이" }));
    await userEvent.click(screen.getByRole("button", { name: "결제하기" }));

    expect(mocks.requestPayment).toHaveBeenCalledWith({
      storeId: "store-test-0000",
      channelKey: "channel-kakaopay-test",
      paymentId: "o1",
      orderName: "린넨 셔츠",
      totalAmount: 16000,
      currency: "KRW",
      payMethod: "EASY_PAY",
      easyPay: { easyPayProvider: "KAKAOPAY" },
      redirectUrl: `${window.location.origin}/orders/o1`,
      customer: { fullName: "홍길동", phoneNumber: "010-0000-0000" },
    });
    expect(await screen.findByText("주문 상태: 결제 완료")).toBeInTheDocument();
    expect(completeCalls()).toHaveLength(1);
    expect(completeCalls()[0][1]?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("성공: 카드를 고르면 payMethod는 CARD이고 easyPay는 보내지 않는다", async () => {
    mockApi([pending()]);
    mocks.requestPayment.mockResolvedValue({ code: "FAILURE_TYPE_PG", message: "카드 한도 초과" });
    render(<OrderComplete />);
    await clickPay();
    const req = mocks.requestPayment.mock.calls[0][0];
    expect(req).toMatchObject({ payMethod: "CARD", channelKey: "channel-card-test" });
    expect(req).not.toHaveProperty("easyPay");
  });

  it("실패: 결제창이 code를 돌려주면 메시지를 보이고 확정 요청을 하지 않으며 다시 결제할 수 있다", async () => {
    mockApi([pending()]);
    mocks.requestPayment.mockResolvedValue({ code: "FAILURE_TYPE_PG", message: "카드 한도 초과" });
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByRole("alert")).toHaveTextContent("카드 한도 초과");
    expect(completeCalls()).toHaveLength(0);
    expect(screen.getByRole("button", { name: "결제하기" })).toBeEnabled();
  });

  it("실패: 결제창이 메시지 없이 취소되면 기본 문구를 보인다", async () => {
    mockApi([pending()]);
    mocks.requestPayment.mockResolvedValue({ code: "PAY_PROCESS_CANCELED" });
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByRole("alert")).toHaveTextContent("결제가 취소되었거나 실패했어요.");
  });

  it("실패: 결제창을 열다 예외가 나면 안내를 보이고 확정 요청을 하지 않는다", async () => {
    mockApi([pending()]);
    mocks.requestPayment.mockRejectedValue(new Error("script load failed"));
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByRole("alert")).toHaveTextContent("결제창을 열지 못했어요");
    expect(completeCalls()).toHaveLength(0);
  });

  it("성공: 결제창 결과가 없으면(리디렉션 이동) 결제창을 열었고 버튼은 계속 잠겨 있으며 아무 확정 요청도 없다", async () => {
    mockApi([pending()]);
    mocks.requestPayment.mockResolvedValue(undefined);
    render(<OrderComplete />);
    await clickPay();
    await waitFor(() => expect(mocks.requestPayment).toHaveBeenCalledTimes(1));
    await act(async () => {});
    expect(screen.getByRole("button", { name: "결제 처리 중..." })).toBeDisabled();
    expect(screen.getByRole("radio", { name: "카드" })).toBeDisabled();
    expect(completeCalls()).toHaveLength(0);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("성공: 리디렉션으로 떠났다가 bfcache로 돌아오면(pageshow persisted) 버튼이 다시 풀린다", async () => {
    mockApi([pending()]);
    mocks.requestPayment.mockResolvedValue(undefined);
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByRole("button", { name: "결제 처리 중..." })).toBeDisabled();
    act(() => {
      window.dispatchEvent(Object.assign(new Event("pageshow"), { persisted: false }));
    });
    expect(screen.getByRole("button", { name: "결제 처리 중..." })).toBeDisabled();
    act(() => {
      window.dispatchEvent(Object.assign(new Event("pageshow"), { persisted: true }));
    });
    expect(await screen.findByRole("button", { name: "결제하기" })).toBeEnabled();
  });

  it("성공: 결제하기를 같은 틱에 두 번 눌러도 결제창은 한 번만 열린다", async () => {
    mockApi([pending()]);
    mocks.requestPayment.mockReturnValue(new Promise(() => {}));
    render(<OrderComplete />);
    const button = await screen.findByRole("button", { name: "결제하기" });
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(mocks.requestPayment).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("button", { name: "결제 처리 중..." })).toBeDisabled();
    // The progress text is also announced through a polite live region.
    expect(screen.getAllByRole("status").some((el) => el.textContent === "결제 처리 중...")).toBe(true);
  });

  it("성공: 결제가 끝난 직후 다시 불러오는 동안에도 결제하기 버튼과 결제수단은 다시 나타나지 않는다", async () => {
    // The first GET shows the pending order; the refetch after payment never answers.
    let n = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, init?: RequestInit) => {
        if (init?.method === "POST") return Promise.resolve(reply(200, { status: "PAID" }));
        return n++ === 0 ? Promise.resolve(reply(200, pending())) : new Promise(() => {});
      })
    );
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByText("주문 상태: 결제 완료")).toBeInTheDocument();
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
    expect(screen.queryByRole("button", { name: "결제하기" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 확정이 실패한 뒤 다시 불러온 주문이 이미 결제 완료면 오래된 오류 문구와 확인 버튼을 지운다", async () => {
    let gets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === "POST") return reply(409, { code: "PAYMENT_NOT_CONFIRMED" });
        return reply(200, gets++ === 0 ? pending() : { ...order, status: "PAID" });
      })
    );
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByText("주문 상태: 결제 완료")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "결제 확인하기" })).not.toBeInTheDocument();
  });

  it("성공: 확인 중에는 기한이 지났다고 나와도 '자동으로 취소' 문구 대신 확인 중 안내와 결제 확인하기 버튼을 보인다", async () => {
    const lapsed = () => pending({ expiresAt: new Date(Date.now() - 1000).toISOString() });
    let gets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === "POST") return reply(409, { code: "PAYMENT_NOT_CONFIRMED" });
        return reply(200, gets++ === 0 ? pending() : lapsed());
      })
    );
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByText("결제 내용을 확인하고 있어요")).toBeInTheDocument();
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
    expect(screen.getByRole("button", { name: "결제 확인하기" })).toBeEnabled();
    expect(screen.queryByText(/자동으로 취소/)).not.toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
  });

  it("실패: 확인 중이 아니고 기한이 지났을 때만 자동 취소 안내를 보인다", async () => {
    mockApi([pending({ expiresAt: new Date(Date.now() - 1000).toISOString() })]);
    render(<OrderComplete />);
    expect(await screen.findByText(/곧 주문이 자동으로 취소돼요/)).toBeInTheDocument();
    expect(screen.queryByText("결제 내용을 확인하고 있어요")).not.toBeInTheDocument();
  });

  it("실패: 서버가 아직 결제를 확인하지 못하면(409) 코드별 문구를 보이고, 버튼은 결제 확인하기로 바뀌어 결제창을 다시 열지 않는다", async () => {
    mockApi([pending()], { status: 409, body: { code: "PAYMENT_NOT_CONFIRMED", error: "raw" } });
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByRole("alert")).toHaveTextContent("아직 결제가 확인되지 않았어요");
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole("button", { name: "결제 확인하기" }));
    await waitFor(() => expect(completeCalls()).toHaveLength(2));
    expect(mocks.requestPayment).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["PAYMENT_AMOUNT_MISMATCH", 409, "결제 금액이 주문 금액과 달라"],
    ["PAYMENT_NEEDS_REVIEW", 409, "재고 문제로 주문을 확정하지 못했어요"],
    ["PAYMENT_PROVIDER_UNAVAILABLE", 503, "결제 확인이 지연되고 있어요"],
    ["PAYMENT_NOT_CONFIGURED", 503, "결제 준비 중이에요"],
  ])("실패: 확정 응답 %s 는 짧은 한국어 문구로 보인다", async (code, status, text) => {
    mockApi([pending()], { status, body: { code, error: "raw english" } });
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(text);
    expect(alert).not.toHaveTextContent("raw english");
  });

  it("실패: 코드 없는 확정 오류는 서버 메시지를 그대로 보인다", async () => {
    mockApi([pending()], { status: 404, body: { error: "주문을 찾을 수 없습니다." } });
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByRole("alert")).toHaveTextContent("주문을 찾을 수 없습니다.");
  });

  it("실패: 확정이 200이어도 PAID가 아니면 확인 못 한 것으로 다룬다", async () => {
    mockApi([pending()], { status: 200, body: { status: "PENDING_PAYMENT" } });
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByRole("alert")).toHaveTextContent("결제 상태를 확인하지 못했어요");
    expect(mocks.clearCart).not.toHaveBeenCalled();
  });

  it("실패: 확정 요청이 네트워크 오류여도 결제 확인 문구를 보인다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === "POST") throw new TypeError("Failed to fetch");
        return reply(200, pending());
      })
    );
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    expect(await screen.findByRole("alert")).toHaveTextContent("결제 확인이 지연되고 있어요");
    expect(await screen.findByRole("button", { name: "결제 확인하기" })).toBeInTheDocument();
  });

  it("실패: 확정 요청이 401이면 로그아웃한다", async () => {
    mockApi([pending()], { status: 401 });
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    await waitFor(() => expect(mocks.logout).toHaveBeenCalledTimes(1));
  });

  it("성공: 이 주문을 만든 장바구니 시도(orderId 일치)가 확정되면 장바구니와 저장된 키를 지운다", async () => {
    sessionStorage.setItem("shop-order-attempt", JSON.stringify({ key: "k", fingerprintHash: "h", orderId: "O1" }));
    mockApi([pending(), { ...order, status: "PAID" }]);
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    await waitFor(() => expect(mocks.clearCart).toHaveBeenCalledTimes(1));
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
  });

  it("실패: 저장된 주문 시도가 다른 주문의 것이거나 orderId를 모르는 예전 형식이면 장바구니와 키를 건드리지 않는다", async () => {
    for (const stored of [
      { key: "k", fingerprintHash: "h", orderId: "other-order" },
      { key: "k", fingerprintHash: "h" },
    ]) {
      sessionStorage.setItem("shop-order-attempt", JSON.stringify(stored));
      mockApi([pending(), { ...order, status: "PAID" }]);
      mocks.requestPayment.mockResolvedValue(windowOk);
      const { unmount } = render(<OrderComplete />);
      await clickPay();
      expect(await screen.findByText("주문 상태: 결제 완료")).toBeInTheDocument();
      await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
      expect(mocks.clearCart).not.toHaveBeenCalled();
      expect(sessionStorage.getItem("shop-order-attempt")).not.toBeNull();
      unmount();
    }
  });

  it("실패: 저장된 주문 시도가 없으면 결제가 확정돼도 장바구니는 건드리지 않는다", async () => {
    mockApi([pending()]);
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    await waitFor(() => expect(completeCalls()).toHaveLength(1));
    expect(mocks.clearCart).not.toHaveBeenCalled();
  });

  it("실패: 결제 확정이 실패하면 장바구니와 저장된 키를 그대로 둔다", async () => {
    sessionStorage.setItem("shop-order-attempt", JSON.stringify({ key: "k", fingerprintHash: "h" }));
    mockApi([pending()], { status: 409, body: { code: "PAYMENT_NOT_CONFIRMED" } });
    mocks.requestPayment.mockResolvedValue(windowOk);
    render(<OrderComplete />);
    await clickPay();
    await screen.findByRole("alert");
    expect(mocks.clearCart).not.toHaveBeenCalled();
    expect(sessionStorage.getItem("shop-order-attempt")).not.toBeNull();
  });
});

describe("모바일 결제 리디렉션 복귀", () => {
  it("성공: paymentId가 이 주문 id와 같으면 확정 요청을 한 번 보내고 쿼리를 정리한 뒤 접수 화면을 보인다", async () => {
    mocks.query = "paymentId=o1";
    mockApi([{ ...order, status: "PAID" }]);
    render(<OrderComplete />);
    expect(await screen.findByText("주문 상태: 결제 완료")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "주문이 접수되었습니다" })).toBeInTheDocument();
    expect(completeCalls()).toHaveLength(1);
    expect(String(completeCalls()[0][0])).toMatch(/\/api\/orders\/o1\/payment\/complete$/);
    expect(mocks.replace).toHaveBeenCalledWith("/orders/o1");
  });

  it("성공: 장바구니에서 시작된 결제(orderId가 일치하는 시도)가 돌아와 확정되면 장바구니를 비운다", async () => {
    sessionStorage.setItem("shop-order-attempt", JSON.stringify({ key: "k", fingerprintHash: "h", orderId: "o1" }));
    mocks.query = "paymentId=o1";
    mockApi([{ ...order, status: "PAID" }]);
    render(<OrderComplete />);
    await waitFor(() => expect(mocks.clearCart).toHaveBeenCalledTimes(1));
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
  });

  it("실패: paymentId가 다른 주문 id면 확정 요청 없이 쿼리만 정리한다", async () => {
    mocks.query = "paymentId=other-order";
    mockApi([pending()]);
    render(<OrderComplete />);
    await screen.findByText("주문번호 o1");
    expect(completeCalls()).toHaveLength(0);
    expect(mocks.replace).toHaveBeenCalledWith("/orders/o1");
    expect(mocks.clearCart).not.toHaveBeenCalled();
  });

  it("실패: code가 붙어 돌아오면 메시지를 보이고 확정 요청은 하지 않는다", async () => {
    mocks.query = `paymentId=o1&code=PAY_PROCESS_CANCELED&message=${encodeURIComponent("사용자가 취소했어요")}`;
    mockApi([pending()]);
    render(<OrderComplete />);
    expect(await screen.findByRole("alert")).toHaveTextContent("사용자가 취소했어요");
    expect(completeCalls()).toHaveLength(0);
    expect(screen.getByRole("button", { name: "결제하기" })).toBeInTheDocument();
    expect(mocks.replace).toHaveBeenCalledWith("/orders/o1");
  });

  it("실패: 돌아왔는데 서버가 결제를 확인하지 못하면 오류 문구와 주문 상태를 함께 보인다", async () => {
    mocks.query = "paymentId=o1";
    mockApi([pending()], { status: 503, body: { code: "PAYMENT_PROVIDER_UNAVAILABLE" } });
    render(<OrderComplete />);
    expect(await screen.findByRole("alert")).toHaveTextContent("결제 확인이 지연되고 있어요");
    expect(await screen.findByText("주문 상태: 결제 대기")).toBeInTheDocument();
    expect(mocks.clearCart).not.toHaveBeenCalled();
  });

  it("실패: paymentId가 없으면 쿼리를 건드리지 않고 확정 요청도 하지 않는다", async () => {
    mocks.query = "placed=1";
    mockApi([{ ...order, status: "PAID" }]);
    render(<OrderComplete />);
    await screen.findByText("주문번호 o1");
    expect(mocks.replace).not.toHaveBeenCalled();
    expect(completeCalls()).toHaveLength(0);
  });
});

describe("모바일 복귀 보강", () => {
  it("성공: StrictMode(효과 두 번 실행)에서도 확정 요청은 한 번만 나간다", async () => {
    mocks.query = "paymentId=o1";
    mockApi([{ ...order, status: "PAID" }]);
    render(
      <StrictMode>
        <OrderComplete />
      </StrictMode>
    );
    expect(await screen.findByText("주문 상태: 결제 완료")).toBeInTheDocument();
    expect(completeCalls()).toHaveLength(1);
  });

  it("성공: 복귀한 결제 완료 주문 때 장바구니에 상품이 남아 있고 저장된 시도로 식별되지 않으면 묻는 안내를 보이고, 누르면 비운다", async () => {
    mocks.query = "paymentId=o1";
    mocks.items = [{ productId: "p1" }];
    mockApi([{ ...order, status: "PAID" }]);
    render(<OrderComplete />);
    expect(await screen.findByText("결제한 상품이 장바구니에 남아 있을 수 있어요.")).toBeInTheDocument();
    expect(mocks.clearCart).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "장바구니 비우기" }));
    expect(mocks.clearCart).toHaveBeenCalledTimes(1);
    expect(screen.queryByText("결제한 상품이 장바구니에 남아 있을 수 있어요.")).not.toBeInTheDocument();
  });

  it("실패: 결제 복귀가 아니거나 장바구니가 비었거나 결제 완료가 아니면 그 안내를 보이지 않는다", async () => {
    mocks.items = [{ productId: "p1" }];
    mocks.query = "";
    mockApi([{ ...order, status: "PAID" }]);
    const first = render(<OrderComplete />);
    await screen.findByText("주문 상태: 결제 완료");
    expect(screen.queryByText(/장바구니에 남아 있을 수 있어요/)).not.toBeInTheDocument();
    first.unmount();

    mocks.query = "paymentId=o1";
    mocks.items = [];
    mockApi([{ ...order, status: "PAID" }]);
    const second = render(<OrderComplete />);
    await screen.findByText("주문 상태: 결제 완료");
    expect(screen.queryByText(/장바구니에 남아 있을 수 있어요/)).not.toBeInTheDocument();
    second.unmount();

    mockApi([pending()], { status: 409, body: { code: "PAYMENT_NOT_CONFIRMED" } });
    mocks.items = [{ productId: "p1" }];
    render(<OrderComplete />);
    await screen.findByText("주문 상태: 결제 대기");
    expect(screen.queryByText(/장바구니에 남아 있을 수 있어요/)).not.toBeInTheDocument();
  });

  it("실패: 다른 주문의 paymentId로 돌아오면 안내도 확정도 없다", async () => {
    mocks.query = "paymentId=other-order";
    mocks.items = [{ productId: "p1" }];
    mockApi([{ ...order, status: "PAID" }]);
    render(<OrderComplete />);
    await screen.findByText("주문 상태: 결제 완료");
    expect(screen.queryByText(/장바구니에 남아 있을 수 있어요/)).not.toBeInTheDocument();
    expect(completeCalls()).toHaveLength(0);
  });
});
