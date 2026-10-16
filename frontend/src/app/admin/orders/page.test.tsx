import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AdminOrdersPage from "./page";

const mocks = vi.hoisted(() => ({
  logout: vi.fn(),
  auth: { session: { token: "fake-token", role: "ADMIN", username: "boss" } as unknown, ready: true },
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => mocks.auth,
  useAuth: () => ({ logout: mocks.logout }),
}));

const DAY = 24 * 60 * 60 * 1000;
const daysAgo = (n: number) => new Date(Date.now() - n * DAY).toISOString();

const order = (n: number, extra: object = {}) => ({
  id: `0000000${n}-aaaa-bbbb-cccc-ddddeeeeffff`,
  status: "PAID",
  totalAmount: 19500 + n,
  shippingFee: 3500,
  createdAt: "2026-10-11T03:00:00Z",
  paidAt: "2026-10-11T03:05:00Z",
  shippedAt: null,
  deliveredAt: null,
  customerName: `고객${n}`,
  customerPhone: "010-0000-0000",
  customerAddress: "서울시 테스트구",
  itemCount: 1,
  firstItemName: `상품${n}`,
  ...extra,
});

const reply = (status: number, body: unknown = {}) => ({ ok: status >= 200 && status < 300, status, json: async () => body });

// GETs answer from `pages` in order (the last one repeats); POSTs answer with `post` (a response, or a function of the url).
function mockApi(pages: unknown[], post: unknown | ((url: string) => unknown) = reply(200, { status: "SHIPPED" })) {
  let n = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST") return typeof post === "function" ? post(String(url)) : post;
      return pages[Math.min(n++, pages.length - 1)];
    })
  );
}
const getCalls = () => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method !== "POST");
const postCalls = () => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === "POST");
const page = (orders: unknown[], nextCursor: string | null = null) => reply(200, { orders, nextCursor });
// Two clicks inside one act(): React cannot re-render (and disable the button) in between, so only a real lock stops the second.
const clickTwice = (el: HTMLElement) =>
  act(async () => {
    fireEvent.click(el);
    fireEvent.click(el);
  });
const card = async (name: RegExp | string) => within((await screen.findByText(name)).closest("li")!);

beforeEach(() => {
  vi.unstubAllGlobals();
  mocks.logout.mockClear();
  mocks.auth = { session: { token: "fake-token", role: "ADMIN", username: "boss" }, ready: true };
});

describe("관리자 주문 화면 접근", () => {
  it("성공: 관리자와 판매자는 토큰을 보내 목록을 본다", async () => {
    for (const role of ["ADMIN", "SELLER"]) {
      mocks.auth = { session: { token: "fake-token", role, username: "boss" }, ready: true };
      mockApi([page([order(1)])]);
      const { unmount } = render(<AdminOrdersPage />);
      expect(await screen.findByText("상품1")).toBeInTheDocument();
      const [url, init] = getCalls()[0];
      expect(new URL(String(url)).pathname).toBe("/api/admin/orders");
      expect(init?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
      unmount();
    }
  });

  it("실패: 일반 회원이나 비로그인, 준비 전이면 아무것도 그리지 않고 요청하지 않는다", () => {
    for (const auth of [
      { session: { token: "t", role: "USER", username: "u" }, ready: true },
      { session: null, ready: true },
      { session: { token: "t", role: "ADMIN", username: "u" }, ready: false },
    ]) {
      mocks.auth = auth;
      mockApi([page([])]);
      const { container, unmount } = render(<AdminOrdersPage />);
      expect(container).toBeEmptyDOMElement();
      expect(fetch).not.toHaveBeenCalled();
      unmount();
    }
  });
});

describe("관리자 주문 목록", () => {
  it("성공: 주문 정보(상품, 금액, 상태, 주문자, 날짜)를 카드로 보인다", async () => {
    mockApi([
      page([
        order(2, {
          status: "DELIVERED",
          itemCount: 3,
          shippedAt: "2026-10-12T03:00:00Z",
          deliveredAt: "2026-10-13T03:00:00Z",
        }),
      ]),
    ]);
    render(<AdminOrdersPage />);
    const c = await card(/상품2 외 2건/);
    expect(c.getByText("19,502원", { exact: false })).toBeInTheDocument();
    expect(c.getByText(/배송비 3,500원/)).toBeInTheDocument();
    expect(c.getByText("배송 완료", { selector: "div" })).toBeInTheDocument();
    expect(c.getByText("고객2 / 010-0000-0000")).toBeInTheDocument();
    expect(c.getByText("서울시 테스트구")).toBeInTheDocument();
    expect(c.getByText("주문번호 00000002")).toBeInTheDocument();
    expect(c.getByText("결제 2026. 10. 11. 12:05")).toBeInTheDocument();
    expect(c.getByText("배송 시작 2026. 10. 12. 12:00")).toBeInTheDocument();
    expect(c.getByText("배송 완료 2026. 10. 13. 12:00")).toBeInTheDocument();
    expect(screen.queryByText(/DELIVERED/)).not.toBeInTheDocument();
  });

  it("성공: 배송비가 0이면 무료로 보인다", async () => {
    mockApi([page([order(1, { shippingFee: 0 })])]);
    render(<AdminOrdersPage />);
    expect(await screen.findByText(/배송비 무료/)).toBeInTheDocument();
  });

  it("실패: 주문이 없으면 안내 문구를 보인다", async () => {
    mockApi([page([])]);
    render(<AdminOrdersPage />);
    expect(await screen.findByText("해당하는 주문이 없어요.")).toBeInTheDocument();
  });

  it("실패: 불러오기에 실패하면 alert와 다시 불러오기 버튼을 보이고, 누르면 다시 가져온다", async () => {
    mockApi([reply(500), page([order(1)])]);
    render(<AdminOrdersPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("주문 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
    await userEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
    expect(await screen.findByText("상품1")).toBeInTheDocument();
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("실패: 401/403이면 로그아웃한다", async () => {
    mockApi([reply(403)]);
    render(<AdminOrdersPage />);
    await waitFor(() => expect(mocks.logout).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("성공: 새로고침 버튼은 목록을 다시 가져온다", async () => {
    mockApi([page([order(1)]), page([order(1), order(2)])]);
    render(<AdminOrdersPage />);
    await screen.findByText("상품1");
    await userEvent.click(screen.getByRole("button", { name: "새로고침" }));
    expect(await screen.findByText("상품2")).toBeInTheDocument();
  });
});

describe("상태 탭", () => {
  const statusOf = (i: number) => new URL(String(getCalls()[i][0])).searchParams.get("status");

  it("성공: 탭마다 해당 status로 다시 요청하고 눌린 탭을 표시한다", async () => {
    mockApi([page([order(1)])]);
    render(<AdminOrdersPage />);
    await screen.findByText("상품1");
    expect(statusOf(0)).toBeNull();
    expect(screen.getByRole("button", { name: "전체" })).toHaveAttribute("aria-pressed", "true");

    const expected = [
      ["결제 완료", "PAID"],
      ["배송 중", "SHIPPED"],
      ["배송 완료", "DELIVERED"],
      ["취소·환불", "CANCELLED"],
    ];
    for (const [i, [name, status]] of expected.entries()) {
      await userEvent.click(screen.getByRole("button", { name }));
      await waitFor(() => expect(getCalls()).toHaveLength(i + 2));
      expect(statusOf(i + 1)).toBe(status);
      expect(screen.getByRole("button", { name })).toHaveAttribute("aria-pressed", "true");
      expect(screen.getByRole("button", { name: "전체" })).toHaveAttribute("aria-pressed", "false");
      await screen.findByText("상품1");
    }
    await userEvent.click(screen.getByRole("button", { name: "전체" }));
    await waitFor(() => expect(getCalls()).toHaveLength(6));
    expect(statusOf(5)).toBeNull();
  });

  it("성공: 취소·환불 탭에서만 배송 전 취소(CANCELLED)와 배송 후 환불(REFUNDED)을 고를 수 있다", async () => {
    mockApi([page([order(1)])]);
    render(<AdminOrdersPage />);
    await screen.findByText("상품1");
    expect(screen.queryByRole("button", { name: "배송 후 환불" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "취소·환불" }));
    await waitFor(() => expect(getCalls()).toHaveLength(2));
    expect(statusOf(1)).toBe("CANCELLED");
    expect(screen.getByRole("button", { name: "배송 전 취소" })).toHaveAttribute("aria-pressed", "true");

    await userEvent.click(screen.getByRole("button", { name: "배송 후 환불" }));
    await waitFor(() => expect(getCalls()).toHaveLength(3));
    expect(statusOf(2)).toBe("REFUNDED");
    expect(screen.getByRole("button", { name: "취소·환불" })).toHaveAttribute("aria-pressed", "true");

    await userEvent.click(screen.getByRole("button", { name: "전체" }));
    await waitFor(() => expect(getCalls()).toHaveLength(4));
    expect(screen.queryByRole("button", { name: "배송 후 환불" })).not.toBeInTheDocument();
  });

  it("실패: 이미 눌린 탭을 다시 눌러도 요청하지 않는다", async () => {
    mockApi([page([order(1)])]);
    render(<AdminOrdersPage />);
    await screen.findByText("상품1");
    await userEvent.click(screen.getByRole("button", { name: "전체" }));
    expect(getCalls()).toHaveLength(1);
  });

  it("성공: 탭을 바꾸면 이전 탭의 목록과 오류를 지우고 새 목록을 보인다", async () => {
    mockApi([page([order(1)]), page([order(2, { status: "SHIPPED" })])], reply(404, {}));
    render(<AdminOrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: /배송 시작/ }));
    await screen.findByRole("alert");
    await userEvent.click(screen.getByRole("button", { name: "배송 중" }));
    expect(await screen.findByText("상품2")).toBeInTheDocument();
    expect(screen.queryByText("상품1")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("성공: 탭을 바꾸기 전에 시작한 더 보기 응답은 새 목록에 섞이지 않는다", async () => {
    const pending: { resolve?: (v: unknown) => void } = {};
    let n = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        const u = new URL(String(url));
        if (u.searchParams.get("cursor")) return new Promise((r) => (pending.resolve = r));
        n++;
        return n === 1 ? page([order(1)], "c1") : page([order(2)]);
      })
    );
    render(<AdminOrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    await userEvent.click(screen.getByRole("button", { name: "결제 완료" }));
    expect(await screen.findByText("상품2")).toBeInTheDocument();
    await act(async () => pending.resolve?.(page([order(3)])));
    expect(screen.queryByText("상품3")).not.toBeInTheDocument();
    expect(screen.getByText("상품2")).toBeInTheDocument();
  });
});

describe("더 보기", () => {
  it("성공: cursor로 다음 페이지를 요청해 이어 붙이고, 이미 있는 주문은 중복하지 않으며, 끝이면 버튼이 사라진다", async () => {
    mockApi([page([order(1), order(2)], "c1"), page([order(2), order(3)], null)]);
    render(<AdminOrdersPage />);
    await screen.findByText("상품1");
    await userEvent.click(screen.getByRole("button", { name: "더 보기" }));
    expect(await screen.findByText("상품3")).toBeInTheDocument();
    expect(screen.getAllByText("상품2")).toHaveLength(1);
    expect(new URL(String(getCalls()[1][0])).searchParams.get("cursor")).toBe("c1");
    expect(screen.queryByRole("button", { name: "더 보기" })).not.toBeInTheDocument();
  });

  it("실패: 다음 페이지가 실패하면 기존 목록을 두고 alert와 다시 시도 버튼을 보인다", async () => {
    mockApi([page([order(1)], "c1"), reply(500), page([order(2)])]);
    render(<AdminOrdersPage />);
    await screen.findByText("상품1");
    await userEvent.click(screen.getByRole("button", { name: "더 보기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("주문 목록을 더 불러오지 못했어요.");
    expect(screen.getByText("상품1")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("상품2")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 더 보기 중 401이면 로그아웃한다", async () => {
    mockApi([page([order(1)], "c1"), reply(401)]);
    render(<AdminOrdersPage />);
    await screen.findByText("상품1");
    await userEvent.click(screen.getByRole("button", { name: "더 보기" }));
    await waitFor(() => expect(mocks.logout).toHaveBeenCalledTimes(1));
  });
});

describe("행별 버튼", () => {
  it("성공: 상태에 맞는 버튼만 보인다", async () => {
    mockApi([
      page([
        order(1, { status: "PAID" }),
        order(2, { status: "SHIPPED" }),
        order(3, { status: "DELIVERED" }),
        order(4, { status: "CANCELLED" }),
        order(5, { status: "REFUNDED" }),
        order(6, { status: "CANCELING" }),
      ]),
    ]);
    render(<AdminOrdersPage />);
    const names = (c: Awaited<ReturnType<typeof card>>) => c.queryAllByRole("button").map((b) => b.textContent);
    expect(names(await card("상품1"))).toEqual(["배송 시작"]);
    expect(names(await card("상품2"))).toEqual(["배송 완료", "환불"]);
    expect(names(await card("상품3"))).toEqual(["환불"]);
    for (const n of [4, 5]) expect(names(await card(`상품${n}`))).toEqual([]);
    expect(names(await card("상품6"))).toEqual(["취소 이어서 처리"]);
  });

  it("성공: 배송 시작은 ship을 POST하고 응답 상태로 행을 바꾸며 배송 완료 버튼이 나타난다", async () => {
    mockApi([page([order(1)])], reply(200, { status: "SHIPPED" }));
    render(<AdminOrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: /배송 시작/ }));

    const c = await card("상품1");
    await waitFor(() => expect(c.getByRole("button", { name: /배송 완료 처리/ })).toBeInTheDocument());
    expect(c.getByText("배송 중", { selector: "div" })).toBeInTheDocument();
    expect(c.queryByRole("button", { name: /배송 시작/ })).not.toBeInTheDocument();
    const [url, init] = postCalls()[0];
    expect(new URL(String(url)).pathname).toBe("/api/admin/orders/00000001-aaaa-bbbb-cccc-ddddeeeeffff/ship");
    expect(init?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
  });

  it("성공: 배송 완료 처리는 deliver를 POST하고 행이 배송 완료가 된다", async () => {
    mockApi([page([order(1, { status: "SHIPPED", shippedAt: daysAgo(1) })])], reply(200, { status: "DELIVERED" }));
    render(<AdminOrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: /배송 완료 처리/ }));

    const c = await card("상품1");
    await waitFor(() => expect(c.queryByRole("button", { name: /배송 완료 처리/ })).not.toBeInTheDocument());
    expect(c.getByText("배송 완료", { selector: "div" })).toBeInTheDocument();
    expect(new URL(String(postCalls()[0][0])).pathname).toMatch(/\/deliver$/);
    expect(c.getByRole("button", { name: /환불/ })).toBeInTheDocument();
  });

  it("실패: 409 INVALID_ORDER_STATE이면 목록을 자동으로 다시 불러오고 안내를 보인다", async () => {
    mockApi(
      [page([order(1), order(2)]), page([order(1, { status: "SHIPPED" }), order(2)])],
      reply(409, { error: "raw", code: "INVALID_ORDER_STATE" })
    );
    render(<AdminOrdersPage />);
    const c = await card("상품1");
    await userEvent.click(c.getByRole("button", { name: /배송 시작/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("주문 상태가 바뀌어 처리하지 못했어요. 목록을 새로 불러왔어요.");
    await waitFor(() => expect(getCalls()).toHaveLength(2));
    // The reloaded list shows the server's current state.
    expect((await card("상품1")).getByText("배송 중", { selector: "div" })).toBeInTheDocument();
    expect(screen.getAllByRole("alert")).toHaveLength(1);
  });

  it("실패: 그 밖의 서버 오류는 그 행에만 오류를 보이고 상태는 그대로이며 다시 누를 수 있다", async () => {
    mockApi([page([order(1), order(2)])], reply(503, { error: "raw", code: "CANCEL_PROVIDER_UNAVAILABLE" }));
    render(<AdminOrdersPage />);
    const c = await card("상품1");
    await userEvent.click(c.getByRole("button", { name: /배송 시작/ }));

    expect(await c.findByRole("alert")).toHaveTextContent("취소 요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    expect(c.getByRole("button", { name: /배송 시작/ })).toBeEnabled();
    expect((await card("상품2")).queryByRole("alert")).not.toBeInTheDocument();
    expect(getCalls()).toHaveLength(1);
  });

  it("실패: 404와 네트워크 오류도 행에 오류를 보인다", async () => {
    mockApi([page([order(1)])], reply(404, { error: "x" }));
    render(<AdminOrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: /배송 시작/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("주문을 찾을 수 없어요.");

    vi.stubGlobal(
      "fetch",
      vi.fn(async (_u: string, init?: RequestInit) => {
        if (init?.method === "POST") throw new TypeError("network");
        return page([order(1)]);
      })
    );
    await userEvent.click(screen.getByRole("button", { name: /배송 시작/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("요청 결과를 확인하지 못했어요. 목록을 새로고침해 확인해 주세요.");
  });

  it("실패: 액션 중 401이면 로그아웃하고 오류는 보이지 않는다", async () => {
    mockApi([page([order(1)])], reply(401));
    render(<AdminOrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: /배송 시작/ }));
    await waitFor(() => expect(mocks.logout).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("성공: 처리 중에는 그 행의 버튼이 비활성화되고, 두 번 눌러도 요청은 한 번이다 (다른 행은 그대로)", async () => {
    let finish!: (v: unknown) => void;
    mockApi([page([order(1), order(2)])], new Promise((r) => (finish = r)));
    render(<AdminOrdersPage />);
    const c = await card("상품1");
    await clickTwice(c.getByRole("button", { name: /배송 시작/ }));

    // Both clicks landed before React re-rendered, so only the ref lock can stop the second request.
    expect(postCalls()).toHaveLength(1);
    const busyButton = await c.findByRole("button", { name: /처리 중 배송 시작/ });
    expect(busyButton).toBeDisabled();
    expect(busyButton).toHaveTextContent("처리 중...");
    expect(c.getAllByText("처리 중...").length).toBeGreaterThan(0);
    expect((await card("상품2")).getByRole("button", { name: /^배송 시작/ })).toBeEnabled();
    await act(async () => finish(reply(200, { status: "SHIPPED" })));
    expect(postCalls()).toHaveLength(1);
  });

  it("성공: 처리가 끝나면 상태 변화를 role=status로 알리고, 이 기기의 시계로 배송 날짜를 채우지 않는다", async () => {
    mockApi([page([order(1)])], reply(200, { status: "SHIPPED" }));
    render(<AdminOrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: /배송 시작/ }));

    expect(await screen.findByText("주문 00000001: 배송 중")).toHaveAttribute("role", "status");
    expect(screen.queryByText(/^배송 시작 \d/)).not.toBeInTheDocument();
  });

  it("성공: 더 보기 요청이 진행되는 동안 배송 처리한 행은 더 보기 응답으로 되돌아가지 않는다", async () => {
    const pending: { resolve?: (v: unknown) => void } = {};
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init?: RequestInit) => {
        if (init?.method === "POST") return reply(200, { status: "SHIPPED" });
        if (new URL(String(url)).searchParams.get("cursor")) return new Promise((r) => (pending.resolve = r));
        return page([order(1)], "c1");
      })
    );
    render(<AdminOrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    await userEvent.click(screen.getByRole("button", { name: /배송 시작/ }));
    await waitFor(() => expect(screen.queryByRole("button", { name: /^배송 시작/ })).not.toBeInTheDocument());

    // The slow page repeats order 1 with its old status plus a new order.
    await act(async () => pending.resolve?.(page([order(1), order(2)])));
    expect(await screen.findByText("상품2")).toBeInTheDocument();
    const c = await card("상품1");
    expect(c.getByText("배송 중", { selector: "div" })).toBeInTheDocument();
    expect(c.queryByRole("button", { name: /^배송 시작/ })).not.toBeInTheDocument();
  });

  it("성공: 새로고침하면 진행 중이던 더 보기의 잠금이 풀려 새 목록에서 바로 더 보기를 쓸 수 있다", async () => {
    let calls = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (new URL(String(url)).searchParams.get("cursor") === "old") return new Promise(() => {});
        calls++;
        return calls === 1 ? page([order(1)], "old") : calls === 2 ? page([order(2)], "new") : page([order(3)]);
      })
    );
    render(<AdminOrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    expect(screen.getByRole("button", { name: "불러오는 중..." })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "새로고침" }));
    expect(await screen.findByText("상품2")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "더 보기" }));
    expect(await screen.findByText("상품3")).toBeInTheDocument();
  });
});

describe("환불", () => {
  const openRefund = async (n = 1) => {
    const c = await card(`상품${n}`);
    await userEvent.click(c.getByRole("button", { name: /^환불 - / }));
    return c;
  };

  it("성공: 환불을 누르면 사유 입력과 확정 버튼이 열리고, 사유를 보내 환불하면 행이 환불 완료가 된다", async () => {
    mockApi([page([order(1, { status: "SHIPPED" })])], reply(200, { status: "REFUNDED" }));
    render(<AdminOrdersPage />);
    const c = await openRefund();
    expect(postCalls()).toHaveLength(0);
    await userEvent.type(c.getByLabelText("환불 사유"), "  상품 하자  ");
    await userEvent.click(c.getByRole("button", { name: /환불 확정/ }));

    await waitFor(() => expect(c.getByText("환불 완료", { selector: "div" })).toBeInTheDocument());
    const [url, init] = postCalls()[0];
    expect(new URL(String(url)).pathname).toMatch(/\/refund$/);
    expect(JSON.parse(String(init?.body))).toEqual({ reason: "상품 하자" });
    expect(init?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    expect(c.queryByLabelText("환불 사유")).not.toBeInTheDocument();
    expect(c.queryByRole("button", { name: /환불/ })).not.toBeInTheDocument();
  });

  it("실패: 사유가 비어 있거나 공백이면 요청하지 않고 alert를 보인다", async () => {
    mockApi([page([order(1, { status: "DELIVERED", deliveredAt: daysAgo(1) })])]);
    render(<AdminOrdersPage />);
    const c = await openRefund();
    expect(c.getByLabelText("환불 사유")).toBeRequired();
    await userEvent.click(c.getByRole("button", { name: /환불 확정/ }));
    expect(await c.findByRole("alert")).toHaveTextContent("환불 사유를 입력해 주세요.");
    await userEvent.type(c.getByLabelText("환불 사유"), "   ");
    await userEvent.click(c.getByRole("button", { name: /환불 확정/ }));
    expect(postCalls()).toHaveLength(0);
    expect(c.getByRole("alert")).toHaveTextContent("환불 사유를 입력해 주세요.");
  });

  it("성공: 닫기를 누르면 폼이 닫히고 요청하지 않는다", async () => {
    mockApi([page([order(1, { status: "SHIPPED" })])]);
    render(<AdminOrdersPage />);
    const c = await openRefund();
    await userEvent.click(c.getByRole("button", { name: "닫기" }));
    expect(c.queryByLabelText("환불 사유")).not.toBeInTheDocument();
    expect(c.getByRole("button", { name: /^환불 - / })).toBeInTheDocument();
    expect(postCalls()).toHaveLength(0);
  });

  it("실패: 서버가 거부하면 오류를 보이고 폼과 입력한 사유는 남는다", async () => {
    mockApi([page([order(1, { status: "SHIPPED" })])], reply(503, { code: "CANCEL_PROVIDER_UNAVAILABLE" }));
    render(<AdminOrdersPage />);
    const c = await openRefund();
    await userEvent.type(c.getByLabelText("환불 사유"), "하자");
    await userEvent.click(c.getByRole("button", { name: /환불 확정/ }));

    expect(await c.findByRole("alert")).toHaveTextContent("취소 요청을 처리하지 못했어요");
    expect(c.getByLabelText("환불 사유")).toHaveValue("하자");
    expect(c.getByRole("button", { name: /환불 확정/ })).toBeEnabled();
  });

  it("실패: 환불 중 두 번 눌러도 요청은 한 번이고 그동안 입력과 버튼이 잠긴다", async () => {
    let finish!: (v: unknown) => void;
    mockApi([page([order(1, { status: "SHIPPED" })])], new Promise((r) => (finish = r)));
    render(<AdminOrdersPage />);
    const c = await openRefund();
    await userEvent.type(c.getByLabelText("환불 사유"), "하자");
    await clickTwice(c.getByRole("button", { name: /환불 확정/ }));

    expect(postCalls()).toHaveLength(1);
    expect(await c.findByRole("button", { name: /환불 처리 중 환불 확정/ })).toBeDisabled();
    expect(c.getByLabelText("환불 사유")).toBeDisabled();
    expect(c.getAllByText(/처리 중\.\.\./).length).toBeGreaterThan(0);
    await act(async () => finish(reply(200, { status: "REFUNDED" })));
    expect(postCalls()).toHaveLength(1);
  });

  it("성공: A 환불이 진행 중일 때 B의 폼을 열어 사유를 쓰면, A가 끝나도 B의 폼과 사유는 남는다", async () => {
    let finish!: (v: unknown) => void;
    mockApi(
      [page([order(1, { status: "SHIPPED" }), order(2, { status: "SHIPPED" })])],
      new Promise((r) => (finish = r))
    );
    render(<AdminOrdersPage />);
    const a = await openRefund(1);
    await userEvent.type(a.getByLabelText("환불 사유"), "A 사유");
    await userEvent.click(a.getByRole("button", { name: /환불 확정/ }));
    expect(postCalls()).toHaveLength(1);

    const b = await openRefund(2);
    await userEvent.type(b.getByLabelText("환불 사유"), "B 사유");
    await act(async () => finish(reply(200, { status: "REFUNDED" })));

    await waitFor(() => expect(a.getByText("환불 완료", { selector: "div" })).toBeInTheDocument());
    expect(b.getByLabelText("환불 사유")).toHaveValue("B 사유");
    expect(b.getByRole("button", { name: /환불 확정/ })).toBeEnabled();
  });

  it("성공: 폼을 열면 사유 입력에 포커스가 가고, 닫으면 환불 버튼으로 돌아온다", async () => {
    mockApi([page([order(1, { status: "SHIPPED" })])]);
    render(<AdminOrdersPage />);
    const c = await openRefund();
    expect(c.getByLabelText("환불 사유")).toHaveFocus();
    await userEvent.click(c.getByRole("button", { name: "닫기" }));
    expect(c.getByRole("button", { name: /^환불 - / })).toHaveFocus();
  });

  it("성공: 수령 후 7일이 지난 배송 완료 주문은 환불 폼에 경고를 보인다", async () => {
    mockApi([page([order(1, { status: "DELIVERED", deliveredAt: daysAgo(8) })])]);
    render(<AdminOrdersPage />);
    const c = await openRefund();
    expect(c.getByText("수령 후 7일이 지났어요. 판매자 재량으로 환불할 수 있어요")).toBeInTheDocument();
  });

  it("실패: 7일 안이거나 배송 중이거나 배송 완료 시각이 없으면 경고가 없다", async () => {
    mockApi([
      page([
        order(1, { status: "DELIVERED", deliveredAt: daysAgo(3) }),
        order(2, { status: "SHIPPED", deliveredAt: daysAgo(30) }),
        order(3, { status: "DELIVERED", deliveredAt: null }),
      ]),
    ]);
    render(<AdminOrdersPage />);
    for (const n of [1, 2, 3]) {
      const c = await openRefund(n);
      expect(c.getByLabelText("환불 사유")).toBeInTheDocument();
      expect(c.queryByText(/7일이 지났어요/)).not.toBeInTheDocument();
    }
  });
});

describe("멈춘 취소 이어서 처리", () => {
  const stuck = (extra: object = {}) => order(1, { status: "CANCELING", ...extra });
  const openResume = async () => {
    const c = await card("상품1");
    await userEvent.click(c.getByRole("button", { name: /^취소 이어서 처리 - / }));
    return c;
  };

  it("성공: 사유 칸에 안내 문구가 미리 들어 있고, 그대로 확정하면 refund를 POST하고 행이 갱신된다", async () => {
    mockApi([page([stuck()])], reply(200, { status: "CANCELLED" }));
    render(<AdminOrdersPage />);
    const c = await openResume();
    expect(c.getByLabelText("환불 사유")).toHaveValue("취소 처리를 이어서 진행합니다");
    expect(c.getByLabelText("환불 사유")).toBeRequired();
    await userEvent.click(c.getByRole("button", { name: /이어서 처리 확정/ }));

    await waitFor(() => expect(c.getByText("주문 취소됨(환불 완료)", { selector: "div" })).toBeInTheDocument());
    const [url, init] = postCalls()[0];
    expect(new URL(String(url)).pathname).toMatch(/\/refund$/);
    expect(JSON.parse(String(init?.body))).toEqual({ reason: "취소 처리를 이어서 진행합니다" });
    expect(c.queryByRole("button")).not.toBeInTheDocument();
  });

  it("성공: 안내 문구는 고칠 수 있고 고친 사유가 전송된다", async () => {
    mockApi([page([stuck()])], reply(200, { status: "CANCELLED" }));
    render(<AdminOrdersPage />);
    const c = await openResume();
    await userEvent.clear(c.getByLabelText("환불 사유"));
    await userEvent.type(c.getByLabelText("환불 사유"), "포트원 확인 후 재시도");
    await userEvent.click(c.getByRole("button", { name: /이어서 처리 확정/ }));
    await waitFor(() => expect(postCalls()).toHaveLength(1));
    expect(JSON.parse(String(postCalls()[0][1]?.body))).toEqual({ reason: "포트원 확인 후 재시도" });
  });

  it("실패: 사유를 지우면 요청하지 않고 alert를 보인다", async () => {
    mockApi([page([stuck()])]);
    render(<AdminOrdersPage />);
    const c = await openResume();
    await userEvent.clear(c.getByLabelText("환불 사유"));
    await userEvent.click(c.getByRole("button", { name: /이어서 처리 확정/ }));
    expect(await c.findByRole("alert")).toHaveTextContent("환불 사유를 입력해 주세요.");
    expect(postCalls()).toHaveLength(0);
  });

  it("실패: 서버가 거부하면 오류를 보이고 폼은 남는다", async () => {
    mockApi([page([stuck()])], reply(503, { code: "CANCEL_PROVIDER_UNAVAILABLE" }));
    render(<AdminOrdersPage />);
    const c = await openResume();
    await userEvent.click(c.getByRole("button", { name: /이어서 처리 확정/ }));
    expect(await c.findByRole("alert")).toHaveTextContent("취소 요청을 처리하지 못했어요");
    expect(c.getByRole("button", { name: /이어서 처리 확정/ })).toBeEnabled();
  });

  it("실패: 두 번 눌러도 요청은 한 번이다", async () => {
    let finish!: (v: unknown) => void;
    mockApi([page([stuck()])], new Promise((r) => (finish = r)));
    render(<AdminOrdersPage />);
    const c = await openResume();
    await clickTwice(c.getByRole("button", { name: /이어서 처리 확정/ }));
    expect(postCalls()).toHaveLength(1);
    await act(async () => finish(reply(200, { status: "CANCELLED" })));
    expect(postCalls()).toHaveLength(1);
  });

  it("실패: 결제 완료 주문에는 이어서 처리 버튼이 없다", async () => {
    mockApi([page([order(1)])]);
    render(<AdminOrdersPage />);
    await screen.findByText("상품1");
    expect(screen.queryByRole("button", { name: /이어서 처리/ })).not.toBeInTheDocument();
  });
});

describe("관리자 주문 옵션 이름", () => {
  it("성공: 첫 상품 이름 옆에 옵션 이름을 보인다", async () => {
    mockApi([page([order(1, { firstItemOptionLabel: "블랙 / L", itemCount: 2 })])]);
    render(<AdminOrdersPage />);
    const c = await card(/상품1/);
    expect(c.getByText("(블랙 / L)")).toBeInTheDocument();
    expect(c.getByText(/상품1/)).toHaveTextContent("상품1(블랙 / L) 외 1건");
  });

  it("실패: 옵션 이름이 null이거나 없으면 괄호를 그리지 않는다", async () => {
    mockApi([page([order(1, { firstItemOptionLabel: null }), order(2)])]);
    render(<AdminOrdersPage />);
    const first = await card(/^상품1/);
    expect(first.getByText(/^상품1/).textContent).toBe("상품1");
    expect((await card(/^상품2/)).getByText(/^상품2/).textContent).toBe("상품2");
  });
});
