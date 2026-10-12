import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, act, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import OrderComplete from "./page";

const mocks = vi.hoisted(() => ({
  id: "o1",
  query: "placed=1",
  logout: vi.fn(),
  auth: { session: { token: "fake-token", role: "USER", username: "buyer" } as unknown, ready: true },
}));
vi.mock("next/navigation", () => ({
  useParams: () => ({ id: mocks.id }),
  useSearchParams: () => new URLSearchParams(mocks.query),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock("@/cart/CartContext", () => ({ useCart: () => ({ clear: vi.fn() }) }));
vi.mock("@portone/browser-sdk/v2", () => ({ requestPayment: vi.fn() }));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => mocks.auth,
  useAuth: () => ({ logout: mocks.logout }),
}));

const order = {
  id: "o1",
  totalAmount: 16000,
  customerName: "홍길동",
  customerPhone: "010-0000-0000",
  customerAddress: "서울시 테스트구",
  items: [{ id: "i1", quantity: 2, price: 8000, productName: "린넨 셔츠" }],
};

const reply = (status: number, body: unknown = {}) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

function mockFetch(status: number, body: unknown = {}) {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(status, body)));
}

const RECEIVED_MAYBE = "주문은 접수되었을 수 있어요. 잠시 후 다시 확인해 주세요.";

beforeEach(() => {
  vi.unstubAllGlobals();
  mocks.id = "o1";
  mocks.query = "placed=1";
  mocks.logout.mockClear();
  mocks.auth = { session: { token: "fake-token", role: "USER", username: "buyer" }, ready: true };
});

const row = (label: string) => screen.getByText(label).closest("div")!;

describe("주문 금액 요약", () => {
  it("성공: 배송비가 있으면 상품 금액(총액-배송비)·배송비·총 주문금액을 보인다", async () => {
    mockFetch(200, { ...order, totalAmount: 19500, shippingFee: 3500 });
    render(<OrderComplete />);
    expect(await screen.findByText("총 주문금액")).toBeInTheDocument();
    expect(row("상품 금액")).toHaveTextContent("16,000원");
    expect(row("배송비")).toHaveTextContent("3,500원");
    expect(row("총 주문금액")).toHaveTextContent("19,500원");
  });

  it("성공: 배송비가 0이면 '무료'로 보인다", async () => {
    mockFetch(200, { ...order, totalAmount: 60000, shippingFee: 0 });
    render(<OrderComplete />);
    expect(await screen.findByText("총 주문금액")).toBeInTheDocument();
    expect(row("상품 금액")).toHaveTextContent("60,000원");
    expect(row("배송비")).toHaveTextContent("무료");
    expect(row("총 주문금액")).toHaveTextContent("60,000원");
  });

  it("실패: 응답에 shippingFee가 없어도(구버전) 0으로 보고 총액을 그대로 보인다", async () => {
    mockFetch(200, order);
    render(<OrderComplete />);
    expect(await screen.findByText("총 주문금액")).toBeInTheDocument();
    expect(row("상품 금액")).toHaveTextContent("16,000원");
    expect(row("배송비")).toHaveTextContent("무료");
    expect(row("총 주문금액")).toHaveTextContent("16,000원");
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });
});

describe("주문 완료 페이지", () => {
  it("성공: 토큰을 Authorization 헤더로 보내고 주문 내용을 보여준다", async () => {
    mockFetch(200, order);
    render(<OrderComplete />);
    expect(await screen.findByText("주문이 접수되었습니다")).toBeInTheDocument();
    expect(screen.getByText("주문번호 o1")).toBeInTheDocument();
    expect(screen.getByText("린넨 셔츠 x 2")).toBeInTheDocument();
    expect(screen.getByText("받는 사람: 홍길동")).toBeInTheDocument();
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toMatch(/\/api\/orders\/o1$/);
    expect(init?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
  });

  it("성공: 방금 주문한 게 아니면(placed 없음) 접수 문구 대신 '주문 상세'를 보이고, 목록·홈 링크가 있다", async () => {
    mocks.query = "";
    mockFetch(200, order);
    render(<OrderComplete />);
    expect(await screen.findByRole("heading", { name: "주문 상세" })).toBeInTheDocument();
    expect(screen.queryByText("주문이 접수되었습니다")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "주문내역으로" })).toHaveAttribute("href", "/orders");
    expect(screen.getByRole("link", { name: "홈으로" })).toHaveAttribute("href", "/");
  });

  it("실패: placed 값이 1이 아니면 접수 문구를 보이지 않는다", async () => {
    mocks.query = "placed=0";
    mockFetch(200, order);
    render(<OrderComplete />);
    expect(await screen.findByRole("heading", { name: "주문 상세" })).toBeInTheDocument();
    expect(screen.queryByText("주문이 접수되었습니다")).not.toBeInTheDocument();
  });

  it("성공: 불러오는 동안 status 영역에 로딩 문구를 보인다", async () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    render(<OrderComplete />);
    expect(screen.getByRole("status")).toHaveTextContent("불러오는 중...");
  });

  it("성공: id는 URL 인코딩해서 요청하고 timeout signal을 싣는다", async () => {
    mocks.id = "a/b c";
    mockFetch(404);
    render(<OrderComplete />);
    await screen.findByText("주문을 찾을 수 없습니다.");
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toMatch(/\/api\/orders\/a%2Fb%20c$/);
    expect(init?.signal).toHaveProperty("aborted", false);
  });

  it("실패: 404면 안내문과 홈 링크를 보여준다 (남의 주문도 같은 응답)", async () => {
    mockFetch(404);
    render(<OrderComplete />);
    expect(await screen.findByText("주문을 찾을 수 없습니다.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "홈으로" })).toHaveAttribute("href", "/");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText("주문이 접수되었습니다")).not.toBeInTheDocument();
  });

  it("실패: 401이면 로그아웃해서 로그인 페이지로 보낸다", async () => {
    mockFetch(401);
    render(<OrderComplete />);
    await waitFor(() => expect(mocks.logout).toHaveBeenCalledTimes(1));
    expect(screen.queryByText("주문이 접수되었습니다")).not.toBeInTheDocument();
  });

  it("실패: 서버 오류면 접수되었을 수 있다는 경고와 다시 불러오기 버튼을 보이고, 누르면 다시 가져온다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(reply(500)).mockResolvedValueOnce(reply(200, order)));
    render(<OrderComplete />);
    expect(await screen.findByRole("alert")).toHaveTextContent(RECEIVED_MAYBE);
    expect(screen.getByRole("link", { name: "홈으로" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
    expect(await screen.findByText("주문이 접수되었습니다")).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("실패: 네트워크 오류와 타임아웃도 같은 경고를 보인다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(Object.assign(new Error("timed out"), { name: "TimeoutError" })));
    render(<OrderComplete />);
    expect(await screen.findByRole("alert")).toHaveTextContent(RECEIVED_MAYBE);
  });

  it("실패: 다른 주문 id의 응답은 보여주지 않고 오류로 다룬다", async () => {
    mockFetch(200, { ...order, id: "other" });
    render(<OrderComplete />);
    expect(await screen.findByRole("alert")).toHaveTextContent(RECEIVED_MAYBE);
    expect(screen.queryByText("주문이 접수되었습니다")).not.toBeInTheDocument();
  });

  it("실패: 로그인하지 않았으면 요청하지 않고 아무것도 그리지 않는다", () => {
    mocks.auth = { session: null, ready: true };
    mockFetch(200, order);
    const { container } = render(<OrderComplete />);
    expect(container).toBeEmptyDOMElement();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("성공: id가 바뀌면 이전 주문을 지우고, 늦게 온 이전 응답은 무시한다", async () => {
    const pending: Record<string, (v: unknown) => void> = {};
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => new Promise((r) => (pending[String(url).split("/").pop()!] = r)))
    );
    const { rerender } = render(<OrderComplete />);
    mocks.id = "o2";
    rerender(<OrderComplete />);
    await waitFor(() => expect(Object.keys(pending)).toEqual(["o1", "o2"]));

    await act(async () => pending.o2(reply(200, { ...order, id: "o2" })));
    expect(await screen.findByText("주문번호 o2")).toBeInTheDocument();
    await act(async () => pending.o1(reply(200, order)));
    expect(screen.getByText("주문번호 o2")).toBeInTheDocument();
    expect(screen.queryByText("주문번호 o1")).not.toBeInTheDocument();
  });

  it("성공: 언로드된 뒤에 온 응답은 무시한다 (401이어도 로그아웃하지 않는다)", async () => {
    let finish!: (v: unknown) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise((r) => (finish = r))));
    const { unmount } = render(<OrderComplete />);
    unmount();
    await act(async () => finish(reply(401)));
    expect(mocks.logout).not.toHaveBeenCalled();
  });
});
