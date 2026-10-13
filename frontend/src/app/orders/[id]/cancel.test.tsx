import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import OrderComplete from "./page";

const mocks = vi.hoisted(() => ({ logout: vi.fn() }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "o1" }),
  useSearchParams: () => new URLSearchParams(""),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => ({ session: { token: "fake-token", role: "USER", username: "buyer" }, ready: true }),
  useAuth: () => ({ logout: mocks.logout }),
}));
vi.mock("@/cart/CartContext", () => ({ useCart: () => ({ clear: vi.fn(), items: [] }) }));
vi.mock("@portone/browser-sdk/v2", () => ({ requestPayment: vi.fn() }));

const order = {
  id: "o1",
  totalAmount: 16000,
  status: "PAID",
  customerName: "홍길동",
  customerPhone: "010-0000-0000",
  customerAddress: "서울시 테스트구",
  items: [{ id: "i1", quantity: 2, price: 8000, productName: "린넨 셔츠" }],
};

const reply = (status: number, body: unknown = {}) => ({ ok: status >= 200 && status < 300, status, json: async () => body });

// GET replies come from `gets` in order (the last one repeats); the cancel POST replies with `cancel` (a response, or a promise of one).
function mockApi(gets: unknown[], cancel: unknown = reply(200, { status: "CANCELLED" })) {
  let n = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url: string, init?: RequestInit) =>
      init?.method === "POST" ? cancel : reply(200, gets[Math.min(n++, gets.length - 1)])
    )
  );
}
const cancelCalls = () => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === "POST");
const openPanel = async () => userEvent.click(await screen.findByRole("button", { name: "주문 취소" }));
const confirm = () => userEvent.click(screen.getByRole("button", { name: "주문 취소 확정" }));

beforeEach(() => {
  vi.unstubAllGlobals();
  mocks.logout.mockClear();
});

describe("주문 상태 문구와 날짜", () => {
  it("성공: 새 상태의 문구와 배송 시작·배송 완료·취소 날짜를 보인다", async () => {
    for (const [status, text] of [
      ["CANCELING", "취소 처리 중"],
      ["CANCELLED", "주문 취소됨(환불 완료)"],
      ["SHIPPED", "배송 중"],
      ["DELIVERED", "배송 완료"],
      ["REFUNDED", "환불 완료"],
    ]) {
      mockApi([{ ...order, status }]);
      const { unmount } = render(<OrderComplete />);
      expect(await screen.findByText(`주문 상태: ${text}`)).toBeInTheDocument();
      unmount();
    }

    mockApi([
      {
        ...order,
        status: "DELIVERED",
        shippedAt: "2026-10-12T03:00:00Z",
        deliveredAt: "2026-10-13T03:00:00Z",
        cancelledAt: "2026-10-14T03:00:00Z",
      },
    ]);
    render(<OrderComplete />);
    expect(await screen.findByText("배송 시작 2026. 10. 12.")).toBeInTheDocument();
    expect(screen.getByText("배송 완료 2026. 10. 13.")).toBeInTheDocument();
    expect(screen.getByText("취소 2026. 10. 14.")).toBeInTheDocument();
  });

  it("실패: 날짜가 없거나 null이면 날짜 줄이 없다", async () => {
    mockApi([{ ...order, shippedAt: null, deliveredAt: null, cancelledAt: null }]);
    render(<OrderComplete />);
    await screen.findByText("주문번호 o1");
    expect(screen.queryByText(/^배송 시작 /)).not.toBeInTheDocument();
    expect(screen.queryByText(/^취소 \d/)).not.toBeInTheDocument();
  });
});

describe("주문 취소 버튼 표시", () => {
  it("성공: 결제 완료 주문에만 주문 취소 버튼이 있다", async () => {
    mockApi([order]);
    render(<OrderComplete />);
    expect(await screen.findByRole("button", { name: "주문 취소" })).toBeInTheDocument();
  });

  it("실패: 결제 완료가 아닌 주문에는 주문 취소 버튼이 없다", async () => {
    for (const status of ["PENDING_PAYMENT", "CANCELING", "CANCELLED", "SHIPPED", "DELIVERED", "REFUNDED", "EXPIRED", "PAYMENT_REVIEW"]) {
      mockApi([{ ...order, status }]);
      const { unmount } = render(<OrderComplete />);
      await screen.findByText("주문번호 o1");
      expect(screen.queryByRole("button", { name: /주문 취소/ })).not.toBeInTheDocument();
      unmount();
    }
  });

  it("성공: 배송 중·배송 완료 주문은 연락처 신청 안내와 /refund 링크를 보인다", async () => {
    for (const status of ["SHIPPED", "DELIVERED"]) {
      mockApi([{ ...order, status }]);
      const { unmount } = render(<OrderComplete />);
      expect(await screen.findByText("교환·환불은 연락처로 신청해 주세요.")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "교환·환불·배송 안내" })).toHaveAttribute("href", "/refund");
      unmount();
    }
  });

  it("실패: 결제 완료 주문에는 연락처 신청 안내가 없다", async () => {
    mockApi([order]);
    render(<OrderComplete />);
    await screen.findByRole("button", { name: "주문 취소" });
    expect(screen.queryByText("교환·환불은 연락처로 신청해 주세요.")).not.toBeInTheDocument();
  });
});

describe("주문 취소 흐름", () => {
  it("성공: 누르면 확인 단계가 열리고 아직 요청은 보내지 않는다 (돌아가기로 닫을 수 있다)", async () => {
    mockApi([order]);
    render(<OrderComplete />);
    await openPanel();
    expect(screen.getByLabelText("취소 사유 (선택)")).toBeInTheDocument();
    expect(screen.getByText(/카드사에 따라 며칠 걸릴 수 있어요/)).toBeInTheDocument();
    expect(cancelCalls()).toHaveLength(0);
    await userEvent.click(screen.getByRole("button", { name: "돌아가기" }));
    expect(cancelCalls()).toHaveLength(0);
    expect(screen.getByRole("button", { name: "주문 취소" })).toBeInTheDocument();
  });

  it("성공: 사유와 함께 확정하면 토큰과 사유를 POST하고 취소 완료로 다시 불러온다", async () => {
    mockApi(
      [order, { ...order, status: "CANCELLED", cancelledAt: "2026-10-13T03:00:00Z" }],
      reply(200, { status: "CANCELLED" })
    );
    render(<OrderComplete />);
    await openPanel();
    await userEvent.type(screen.getByLabelText("취소 사유 (선택)"), "단순 변심");
    await confirm();

    expect(await screen.findByText("주문 상태: 주문 취소됨(환불 완료)")).toBeInTheDocument();
    expect(await screen.findByText("취소 2026. 10. 13.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /주문 취소/ })).not.toBeInTheDocument();
    const [url, init] = cancelCalls()[0];
    expect(String(url)).toMatch(/\/api\/orders\/o1\/cancel$/);
    expect(init?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    expect(JSON.parse(String(init?.body))).toEqual({ reason: "단순 변심" });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("성공: 사유를 비워도 취소할 수 있다", async () => {
    mockApi([order, { ...order, status: "CANCELLED" }]);
    render(<OrderComplete />);
    await openPanel();
    await confirm();
    await screen.findByText("주문 상태: 주문 취소됨(환불 완료)");
    expect(JSON.parse(String(cancelCalls()[0][1]?.body))).toEqual({});
  });

  it("성공: 취소 처리 중이면 안내와 새로고침 버튼을 보이고, 새로고침하면 다시 불러온다", async () => {
    mockApi([order, { ...order, status: "CANCELING" }, { ...order, status: "CANCELLED" }], reply(200, { status: "CANCELING" }));
    render(<OrderComplete />);
    await openPanel();
    await confirm();

    expect(await screen.findByText("주문 상태: 취소 처리 중")).toBeInTheDocument();
    expect(screen.getByText("취소 처리 중이에요. 잠시 뒤 다시 확인해 주세요.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /주문 취소/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "새로고침" }));
    expect(await screen.findByText("주문 상태: 주문 취소됨(환불 완료)")).toBeInTheDocument();
    expect(screen.queryByText("취소 처리 중이에요. 잠시 뒤 다시 확인해 주세요.")).not.toBeInTheDocument();
  });

  it("실패: 서버가 이미 취소 처리 중으로 돌려준 주문도 같은 안내를 보인다", async () => {
    mockApi([{ ...order, status: "CANCELING" }]);
    render(<OrderComplete />);
    expect(await screen.findByText("취소 처리 중이에요. 잠시 뒤 다시 확인해 주세요.")).toBeInTheDocument();
  });

  it("실패: 409 ORDER_NOT_CANCELLABLE 이면 안내 문구를 role=alert로 보이고 주문을 다시 불러온다", async () => {
    mockApi(
      [order, { ...order, status: "SHIPPED" }],
      reply(409, { error: "raw", code: "ORDER_NOT_CANCELLABLE" })
    );
    render(<OrderComplete />);
    await openPanel();
    await confirm();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "배송이 시작되어 바로 취소할 수 없어요. 교환·환불은 연락처로 신청해 주세요."
    );
    expect(await screen.findByText("주문 상태: 배송 중")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /주문 취소/ })).not.toBeInTheDocument();
  });

  it.each([
    ["CANCEL_PROVIDER_UNAVAILABLE", 503],
    ["PAYMENT_NOT_CONFIGURED", 503],
  ])("실패: 503 %s 이면 잠시 뒤 다시 시도하라고 하고, 버튼은 다시 쓸 수 있다", async (code, status) => {
    mockApi([order], reply(status, { error: "raw", code }));
    render(<OrderComplete />);
    await openPanel();
    await confirm();

    expect(await screen.findByRole("alert")).toHaveTextContent("취소 요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    expect(await screen.findByRole("button", { name: "주문 취소" })).toBeEnabled();
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("실패: 404도 한국어 문구로 보인다", async () => {
    mockApi([order], reply(404, { error: "x" }));
    render(<OrderComplete />);
    await openPanel();
    await confirm();
    expect(await screen.findByRole("alert")).toHaveTextContent("주문을 찾을 수 없어요.");
  });

  it("실패: 네트워크 오류면 결과를 확인하라고 안내한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === "POST") throw new TypeError("network");
        return reply(200, order);
      })
    );
    render(<OrderComplete />);
    await openPanel();
    await confirm();
    expect(await screen.findByRole("alert")).toHaveTextContent("취소 결과를 확인하지 못했어요. 주문 상태를 확인해 주세요.");
  });

  it("성공: 요청 중에는 버튼이 비활성화되고, 두 번 눌러도 요청은 한 번만 나간다", async () => {
    let finish!: (v: unknown) => void;
    const pendingPost = new Promise((r) => (finish = r));
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_url: string, init?: RequestInit) =>
        init?.method === "POST" ? pendingPost : reply(200, order)
      )
    );
    render(<OrderComplete />);
    await openPanel();
    const button = screen.getByRole("button", { name: "주문 취소 확정" });
    await userEvent.dblClick(button);

    expect(await screen.findByRole("button", { name: "취소 처리 중..." })).toBeDisabled();
    expect(screen.getByRole("button", { name: "돌아가기" })).toBeDisabled();
    expect(cancelCalls()).toHaveLength(1);
    finish(reply(200, { status: "CANCELLED" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "취소 처리 중..." })).not.toBeInTheDocument());
    expect(cancelCalls()).toHaveLength(1);
  });

  it("실패: 취소 중 401이면 로그아웃하고 오류 문구는 보이지 않는다", async () => {
    mockApi([order], reply(401));
    render(<OrderComplete />);
    await openPanel();
    await confirm();
    await waitFor(() => expect(mocks.logout).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    // No refetch with the dead token.
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method !== "POST")).toHaveLength(1);
  });
});
