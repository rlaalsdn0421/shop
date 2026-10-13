import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor, within } from "@testing-library/react";
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
    expect(c.getByText("결제 2026. 10. 11.")).toBeInTheDocument();
    expect(c.getByText("배송 시작 2026. 10. 12.")).toBeInTheDocument();
    expect(c.getByText("배송 완료 2026. 10. 13.")).toBeInTheDocument();
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
    mockApi([page([order(1)]), page([order(2, { status: "SHIPPED" })])], reply(409, { code: "INVALID_ORDER_STATE" }));
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
    for (const n of [4, 5, 6]) expect(names(await card(`상품${n}`))).toEqual([]);
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

  it("실패: 409 INVALID_ORDER_STATE이면 그 행에 오류를 보이고 상태는 그대로이며 다시 누를 수 있다", async () => {
    mockApi([page([order(1), order(2)])], reply(409, { error: "raw", code: "INVALID_ORDER_STATE" }));
    render(<AdminOrdersPage />);
    const c = await card("상품1");
    await userEvent.click(c.getByRole("button", { name: /배송 시작/ }));

    expect(await c.findByRole("alert")).toHaveTextContent("주문 상태가 바뀌어 처리할 수 없어요. 목록을 새로고침해 확인해 주세요.");
    expect(c.getByRole("button", { name: /배송 시작/ })).toBeEnabled();
    expect((await card("상품2")).queryByRole("alert")).not.toBeInTheDocument();
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
    await userEvent.dblClick(c.getByRole("button", { name: /배송 시작/ }));

    expect(await c.findByRole("button", { name: /배송 시작/ })).toBeDisabled();
    expect(c.getByRole("button", { name: /배송 시작/ })).toHaveTextContent("처리 중...");
    expect((await card("상품2")).getByRole("button", { name: /배송 시작/ })).toBeEnabled();
    expect(postCalls()).toHaveLength(1);
    await act(async () => finish(reply(200, { status: "SHIPPED" })));
    expect(postCalls()).toHaveLength(1);
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
    mockApi([page([order(1, { status: "SHIPPED" })])], reply(409, { code: "INVALID_ORDER_STATE" }));
    render(<AdminOrdersPage />);
    const c = await openRefund();
    await userEvent.type(c.getByLabelText("환불 사유"), "하자");
    await userEvent.click(c.getByRole("button", { name: /환불 확정/ }));

    expect(await c.findByRole("alert")).toHaveTextContent("주문 상태가 바뀌어 처리할 수 없어요");
    expect(c.getByLabelText("환불 사유")).toHaveValue("하자");
    expect(c.getByRole("button", { name: /환불 확정/ })).toBeEnabled();
  });

  it("실패: 환불 중 두 번 눌러도 요청은 한 번이고 그동안 입력과 버튼이 잠긴다", async () => {
    let finish!: (v: unknown) => void;
    mockApi([page([order(1, { status: "SHIPPED" })])], new Promise((r) => (finish = r)));
    render(<AdminOrdersPage />);
    const c = await openRefund();
    await userEvent.type(c.getByLabelText("환불 사유"), "하자");
    await userEvent.dblClick(c.getByRole("button", { name: /환불 확정/ }));

    expect(await c.findByRole("button", { name: /환불 확정/ })).toBeDisabled();
    expect(c.getByLabelText("환불 사유")).toBeDisabled();
    expect(postCalls()).toHaveLength(1);
    await act(async () => finish(reply(200, { status: "REFUNDED" })));
    expect(postCalls()).toHaveLength(1);
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
