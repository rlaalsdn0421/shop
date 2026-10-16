import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, act, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import OrdersPage from "./page";

const mocks = vi.hoisted(() => ({
  logout: vi.fn(),
  auth: { session: { token: "fake-token", role: "USER", username: "buyer" } as unknown, ready: true },
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => mocks.auth,
  useAuth: () => ({ logout: mocks.logout }),
}));

const order = (n: number, extra: object = {}) => ({
  id: `0000000${n}-aaaa-bbbb-cccc-ddddeeeeffff`,
  createdAt: "2026-10-11T03:00:00Z",
  totalAmount: 16000 + n,
  status: "PAID",
  itemCount: 1,
  firstItemName: `상품${n}`,
  ...extra,
});

const reply = (status: number, body: unknown = {}) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const FAIL = "주문 내역을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";

beforeEach(() => {
  vi.unstubAllGlobals();
  mocks.logout.mockClear();
  mocks.auth = { session: { token: "fake-token", role: "USER", username: "buyer" }, ready: true };
});

describe("주문내역 페이지", () => {
  it("성공: 토큰을 보내 첫 페이지를 cursor 없이 요청하고 카드를 보여준다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, { orders: [order(1)], nextCursor: null })));
    render(<OrdersPage />);
    const link = await screen.findByRole("link", { name: /상품1/ });
    expect(link).toHaveAttribute("href", "/orders/00000001-aaaa-bbbb-cccc-ddddeeeeffff");
    expect(link).toHaveTextContent("16,001원");
    expect(link).toHaveTextContent("주문번호 00000001");
    expect(link).not.toHaveTextContent("aaaa");
    expect(link).toHaveTextContent("2026. 10. 11.");
    // The raw status code is never shown, only its label.
    expect(screen.queryByText(/PAID/)).not.toBeInTheDocument();
    expect(link).toHaveTextContent("결제 완료");
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toMatch(/\/api\/orders\?size=10$/);
    expect(init?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
  });

  it("성공: 취소·배송·환불 상태의 문구를 보이고 상태 코드는 보이지 않는다", async () => {
    const statuses: [string, string][] = [
      ["CANCELING", "취소 처리 중"],
      ["CANCELLED", "주문 취소됨(환불 완료)"],
      ["SHIPPED", "배송 중"],
      ["DELIVERED", "배송 완료"],
      ["REFUNDED", "환불 완료"],
    ];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        reply(200, { orders: statuses.map(([status], i) => order(i + 1, { status })), nextCursor: null })
      )
    );
    render(<OrdersPage />);
    for (const [i, [status, text]] of statuses.entries()) {
      const link = await screen.findByRole("link", { name: new RegExp(`상품${i + 1}`) });
      expect(link).toHaveTextContent(text);
      expect(link).not.toHaveTextContent(status);
    }
  });

  it("실패: 모르는 상태는 문구 없이 카드만 보인다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(reply(200, { orders: [order(1, { status: "SOMETHING_NEW" })], nextCursor: null }))
    );
    render(<OrdersPage />);
    const link = await screen.findByRole("link", { name: /상품1/ });
    expect(link).not.toHaveTextContent("SOMETHING_NEW");
    expect(link).toHaveTextContent("16,001원");
  });

  it("성공: 카드의 접근 가능한 이름은 상품명으로 시작한다 (날짜·주문번호는 뒤에 읽힌다)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, { orders: [order(1)], nextCursor: null })));
    render(<OrdersPage />);
    expect(await screen.findByRole("link", { name: /상품1/ })).toHaveAccessibleName(/^상품1 .*2026\. 10\. 11\./);
  });

  it("성공: 로딩·오류·빈 목록·목록 모든 상태에서 같은 주문내역 제목(h1)이 있다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(500))
        .mockResolvedValueOnce(reply(200, { orders: [], nextCursor: null }))
    );
    const { unmount } = render(<OrdersPage />);
    expect(screen.getByRole("heading", { level: 1, name: "주문내역" })).toBeInTheDocument(); // loading
    await screen.findByRole("alert");
    expect(screen.getByRole("heading", { level: 1, name: "주문내역" })).toBeInTheDocument(); // error
    await userEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
    await screen.findByText("아직 주문 내역이 없어요.");
    expect(screen.getByRole("heading", { level: 1, name: "주문내역" })).toBeInTheDocument(); // empty
    unmount();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, { orders: [order(1)], nextCursor: null })));
    render(<OrdersPage />);
    await screen.findByText("상품1");
    expect(screen.getAllByRole("heading", { level: 1, name: "주문내역" })).toHaveLength(1); // list
  });

  it("성공: 불러오는 동안 status 영역에 로딩 문구를 보인다", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    render(<OrdersPage />);
    expect(screen.getByRole("status")).toHaveTextContent("불러오는 중...");
  });

  it("성공: 상품이 여러 개면 대표 상품명 뒤에 '외 N건'을 붙이고, 하나면 붙이지 않는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        reply(200, { orders: [order(1, { itemCount: 3 }), order(2)], nextCursor: null })
      )
    );
    render(<OrdersPage />);
    expect(await screen.findByText("상품1 외 2건")).toBeInTheDocument();
    expect(screen.getByText("상품2")).toBeInTheDocument();
  });

  it("성공: 더 보기를 누르면 nextCursor로 이어 받아 붙이고, 마지막이면 버튼이 사라진다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c 1/2" }))
      .mockResolvedValueOnce(reply(200, { orders: [order(2)], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);
    render(<OrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    expect(await screen.findByText("상품2")).toBeInTheDocument();
    expect(screen.getByText("상품1")).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[1][0])).toMatch(/\/api\/orders\?size=10&cursor=c%201%2F2$/);
    expect(screen.queryByRole("button", { name: "더 보기" })).not.toBeInTheDocument();
  });

  it("성공: 더 보기 성공 후 N건을 status로 알리고 포커스를 새로 추가된 첫 항목으로 옮긴다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c" }))
        .mockResolvedValueOnce(reply(200, { orders: [order(2), order(3)], nextCursor: null }))
    );
    render(<OrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    expect(await screen.findByRole("status")).toHaveTextContent("2건 더 불러왔어요");
    // The button is gone, so focus must land on the first new order rather than on body.
    expect(screen.getByRole("link", { name: /상품2/ })).toHaveFocus();
  });

  it("성공: 새로 받은 게 없고 마지막 페이지면 포커스를 목록으로 옮기고 알린다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c" }))
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: null }))
    );
    render(<OrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    expect(await screen.findByRole("status")).toHaveTextContent("더 불러올 주문이 없어요");
    expect(screen.getByRole("list")).toHaveFocus();
  });

  it("성공: 이미 보여 준 주문이 다음 페이지에 또 와도 한 번만 보인다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c" }))
        .mockResolvedValueOnce(reply(200, { orders: [order(1), order(2)], nextCursor: null }))
    );
    render(<OrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    await screen.findByText("상품2");
    expect(screen.getAllByText("상품1")).toHaveLength(1);
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByRole("status")).toHaveTextContent("1건 더 불러왔어요");
  });

  it("성공: 같은 틱에 더 보기를 두 번 눌러도 요청은 한 번만 간다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c" }))
      .mockResolvedValueOnce(reply(200, { orders: [order(2)], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);
    render(<OrdersPage />);
    const button = await screen.findByRole("button", { name: "더 보기" });
    act(() => {
      button.click();
      button.click();
    });
    await screen.findByText("상품2");
    expect(fetchMock).toHaveBeenCalledTimes(2); // first page + exactly one "more"
    expect(screen.getAllByText("상품2")).toHaveLength(1);
  });

  it("성공: 더 보기 요청 중에는 버튼이 비활성화된다", async () => {
    let finish!: (v: unknown) => void;
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c" }))
        .mockReturnValueOnce(new Promise((r) => (finish = r)))
    );
    render(<OrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    expect(screen.getByRole("button")).toBeDisabled();
    await act(async () => finish(reply(200, { orders: [], nextCursor: null })));
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("성공: nextCursor가 null이면 더 보기 버튼이 없다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, { orders: [order(1)], nextCursor: null })));
    render(<OrdersPage />);
    await screen.findByText("상품1");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("성공: 주문이 없으면 안내문과 홈 링크를 보인다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, { orders: [], nextCursor: null })));
    render(<OrdersPage />);
    expect(await screen.findByText("아직 주문 내역이 없어요.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "홈으로" })).toHaveAttribute("href", "/");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 서버 오류면 alert와 다시 불러오기 버튼을 보이고, 누르면 다시 가져온다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(500))
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: null }))
    );
    render(<OrdersPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent(FAIL);
    await userEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
    expect(await screen.findByText("상품1")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("실패: 네트워크 오류와 타임아웃도 같은 오류 안내를 보인다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(Object.assign(new Error("timed out"), { name: "TimeoutError" })));
    render(<OrdersPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent(FAIL);
  });

  it("실패: 더 보기가 실패하면 받은 목록을 유지하고 그 자리에 오류와 재시도를 보인다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c" }))
        .mockResolvedValueOnce(reply(500))
        .mockResolvedValueOnce(reply(200, { orders: [order(2)], nextCursor: null }))
    );
    render(<OrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("더 불러오지 못했어요");
    expect(screen.getByText("상품1")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("상품2")).toBeInTheDocument();
    expect(screen.getByText("상품1")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 더 보기가 실패하면 포커스가 재시도 버튼으로 돌아온다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c" }))
        .mockResolvedValueOnce(reply(500))
    );
    render(<OrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("button", { name: "다시 시도" })).toHaveFocus();
  });

  it("성공: 더 보기 중 언마운트되면 늦게 온 응답을 무시한다 (401이어도 로그아웃하지 않는다)", async () => {
    let finish!: (v: unknown) => void;
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c" }))
        .mockReturnValueOnce(new Promise((r) => (finish = r)))
    );
    const { unmount } = render(<OrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    unmount();
    await act(async () => finish(reply(401)));
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("성공: 세션이 바뀌면 이전 사용자의 목록은 바로 사라지고 로딩 상태가 된다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: null }))
        .mockReturnValueOnce(new Promise(() => {}))
    );
    const { rerender } = render(<OrdersPage />);
    await screen.findByText("상품1");
    mocks.auth = { session: { token: "other-token", role: "USER", username: "other" }, ready: true };
    rerender(<OrdersPage />);
    expect(screen.queryByText("상품1")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("불러오는 중...");
  });

  it("실패: 첫 페이지가 401이면 로그아웃한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(401)));
    render(<OrdersPage />);
    await waitFor(() => expect(mocks.logout).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 더 보기가 401이면 로그아웃한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(reply(200, { orders: [order(1)], nextCursor: "c" }))
        .mockResolvedValueOnce(reply(401))
    );
    render(<OrdersPage />);
    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    await waitFor(() => expect(mocks.logout).toHaveBeenCalledTimes(1));
  });

  it("실패: 로그인하지 않았으면 요청하지 않고 아무것도 그리지 않는다", () => {
    mocks.auth = { session: null, ready: true };
    vi.stubGlobal("fetch", vi.fn());
    const { container } = render(<OrdersPage />);
    expect(container).toBeEmptyDOMElement();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("성공: 언로드된 뒤에 온 응답은 무시한다 (401이어도 로그아웃하지 않는다)", async () => {
    let finish!: (v: unknown) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise((r) => (finish = r))));
    const { unmount } = render(<OrdersPage />);
    unmount();
    await act(async () => finish(reply(401)));
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("성공: 로그인 세션이 바뀌면 이전 사용자의 목록을 지우고 늦게 온 이전 응답은 무시한다", async () => {
    const pending: Record<string, (v: unknown) => void> = {};
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, init: RequestInit) => {
        const token = (init.headers as Record<string, string>).Authorization;
        return new Promise((r) => (pending[token] = r));
      })
    );
    const { rerender } = render(<OrdersPage />);
    mocks.auth = { session: { token: "other-token", role: "USER", username: "other" }, ready: true };
    rerender(<OrdersPage />);
    await waitFor(() => expect(Object.keys(pending)).toHaveLength(2));

    await act(async () =>
      pending["Bearer other-token"](reply(200, { orders: [order(2)], nextCursor: null }))
    );
    expect(await screen.findByText("상품2")).toBeInTheDocument();
    await act(async () => pending["Bearer fake-token"](reply(200, { orders: [order(1)], nextCursor: null })));
    expect(screen.queryByText("상품1")).not.toBeInTheDocument();
  });
});

describe("주문내역 결제 상태", () => {
  const soon = () => new Date(Date.now() + 7 * 60_000 + 5_000).toISOString();
  const show = async (orders: object[]) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, { orders, nextCursor: null })));
    render(<OrdersPage />);
    await screen.findByRole("link", { name: /^상품1/ });
  };

  it("성공: 상태별 문구를 카드 안에 보인다", async () => {
    await show([
      order(1, { status: "PENDING_PAYMENT", expiresAt: soon() }),
      order(2, { status: "EXPIRED" }),
      order(3, { status: "PAYMENT_REVIEW" }),
    ]);
    expect(screen.getByRole("link", { name: /^상품1/ })).toHaveTextContent("결제 대기");
    expect(screen.getByRole("link", { name: /상품2/ })).toHaveTextContent("주문 만료(결제하지 않아 취소됨)");
    expect(screen.getByRole("link", { name: /상품3/ })).toHaveTextContent("확인 중(결제 확인 후 취소·환불을 안내해 드려요)");
  });

  it("성공: 결제 대기이고 기한이 남았으면 남은 시간이 붙은 결제하기 링크가 주문 상세로 간다", async () => {
    await show([order(1, { status: "PENDING_PAYMENT", expiresAt: soon() })]);
    expect(screen.getByRole("link", { name: "결제하기 (남은 시간 8분) - 상품1" })).toHaveAttribute(
      "href",
      "/orders/00000001-aaaa-bbbb-cccc-ddddeeeeffff"
    );
  });

  it("실패: 기한이 지난 대기 주문, 결제 완료 주문에는 결제하기 링크가 없다", async () => {
    await show([
      order(1, { status: "PENDING_PAYMENT", expiresAt: new Date(Date.now() - 1000).toISOString() }),
      order(2, { status: "PAID", expiresAt: soon() }),
      order(3, { status: "PENDING_PAYMENT", expiresAt: null }),
    ]);
    expect(screen.queryByRole("link", { name: /결제하기/ })).not.toBeInTheDocument();
  });

  it("실패: 모르는 상태 코드는 문구를 만들어 내지 않고 그대로 숨긴다", async () => {
    await show([order(1, { status: "SOMETHING_NEW" })]);
    expect(screen.queryByText(/SOMETHING_NEW/)).not.toBeInTheDocument();
  });
});

describe("주문내역 옵션 이름", () => {
  it("성공: 첫 상품 이름 옆에 옵션 이름을 보인다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, { orders: [order(1, { firstItemOptionLabel: "화이트 / M", itemCount: 3 })], nextCursor: null })));
    render(<OrdersPage />);
    const link = await screen.findByRole("link", { name: /상품1/ });
    expect(link).toHaveTextContent("상품1(화이트 / M) 외 2건");
    expect(screen.getByText("(화이트 / M)")).toBeInTheDocument();
  });

  it("실패: 옵션 이름이 null이거나 없으면(구버전 포함) 아무것도 더하지 않는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(reply(200, { orders: [order(1, { firstItemOptionLabel: null }), order(2)], nextCursor: null }))
    );
    render(<OrdersPage />);
    const link = await screen.findByRole("link", { name: /상품1/ });
    expect(link).not.toHaveTextContent("(");
    expect(screen.getByRole("link", { name: /상품2/ })).not.toHaveTextContent("(");
  });
});
