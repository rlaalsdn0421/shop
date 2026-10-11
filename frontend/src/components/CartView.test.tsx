import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CartProvider } from "@/cart/CartContext";
import { CartView } from "./CartView";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => ({ session: { token: "fake-token", role: "USER", username: "buyer" }, ready: true }),
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

beforeEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
  sessionStorage.clear();
  push.mockClear();
});

describe("CartView 주문 멱등 키", () => {
  it("성공: Idempotency-Key와 토큰을 보내고, 성공하면 장바구니를 비우고 이동한다", async () => {
    mockReplies({ status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[0]).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    expect(vi.mocked(fetch).mock.calls[0][1]?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    expect(await screen.findByText("장바구니가 비어있습니다.")).toBeInTheDocument();
  });

  it("성공: 5xx 후 다시 눌러도 같은 키로 보낸다", async () => {
    mockReplies({ status: 500, body: { error: "서버 오류" } }, { status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("서버 오류");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: 네트워크 오류 후 다시 눌러도 같은 키로 보낸다", async () => {
    mockReplies(new TypeError("Failed to fetch"), { status: 201, body: { orderId: "o1" } });
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
      { status: 201, body: { orderId: "o1" } }
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
    mockReplies({ status: 503, body: { error: "lock timeout" } }, { status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("잠시 후 다시 시도해주세요.");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("실패: 4xx(재고 부족 등) 후에는 새 키로 보내고 서버 메시지를 보여준다", async () => {
    mockReplies({ status: 400, body: { error: "재고가 부족합니다." } }, { status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("재고가 부족합니다.");
    expect(push).not.toHaveBeenCalled();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it("실패: 409 후에도 새 키로 보낸다", async () => {
    mockReplies({ status: 409, body: { error: "같은 키에 다른 내용" } }, { status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("같은 키에 다른 내용");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it("성공: 실패 뒤 입력을 바꾸면 새 키로 보낸다", async () => {
    mockReplies({ status: 500, body: {} }, { status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    await userEvent.type(screen.getByLabelText("배송 주소"), " 101호");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).not.toBe(keys()[0]);
  });

  it("성공: 실패 뒤 수량을 바꾸면 새 키로 보낸다", async () => {
    mockReplies({ status: 500, body: {} }, { status: 201, body: { orderId: "o1" } });
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
    mockReplies({ status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(vi.mocked(fetch).mock.calls[0][1]?.signal).toHaveProperty("aborted", false);
  });

  it("성공: 타임아웃 후에는 구분 문구를 보이고 같은 키를 유지한다", async () => {
    mockReplies(Object.assign(new Error("timed out"), { name: "TimeoutError" }), { status: 201, body: { orderId: "o1" } });
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
    mockReplies({ status: 200, body: {} }, { status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("서버 오류로 주문에 실패했습니다");
    expect(push).not.toHaveBeenCalled();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: 새로고침(재마운트)해도 같은 내용이면 같은 키, 내용이 다르면 새 키를 쓴다", async () => {
    mockReplies(new TypeError("Failed to fetch"), new TypeError("Failed to fetch"), { status: 201, body: { orderId: "o1" } });
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
    mockReplies({ status: 400, body: { error: "재고가 부족합니다." } }, { status: 201, body: { orderId: "o1" } });
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

  it("성공: 성공하면 저장된 키를 지운다", async () => {
    mockReplies({ status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sessionStorage.getItem("shop-order-attempt")).toBeNull();
  });

  it("성공: sessionStorage를 쓸 수 없어도 주문되고 재시도 키는 유지된다", async () => {
    const broken = { getItem: () => { throw new Error("blocked"); }, setItem: () => { throw new Error("blocked"); }, removeItem: () => { throw new Error("blocked"); } };
    vi.stubGlobal("sessionStorage", broken);
    mockReplies({ status: 500, body: {} }, { status: 201, body: { orderId: "o1" } });
    await renderCart();
    await submit();
    await screen.findByRole("alert");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/orders/o1?placed=1"));
    expect(keys()[1]).toBe(keys()[0]);
  });

  it("성공: 같은 틱에 두 번 제출해도 요청은 한 번이다", async () => {
    mockReplies({ status: 201, body: { orderId: "o1" } });
    await renderCart();
    const form = screen.getByRole("button", { name: "주문하기" }).closest("form")!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    await waitFor(() => expect(push).toHaveBeenCalledTimes(1));
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("실패: 입력이 비어 있으면 요청하지 않는다", async () => {
    mockReplies({ status: 201, body: { orderId: "o1" } });
    await renderCart();
    await userEvent.clear(screen.getByLabelText("이름"));
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("이름, 연락처, 주소를 모두 입력해주세요.");
    expect(fetch).not.toHaveBeenCalled();
  });
});
