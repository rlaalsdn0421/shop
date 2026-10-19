import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AdminProductEditPage from "./page";

const mocks = vi.hoisted(() => ({
  id: "p1",
  push: vi.fn(),
  refresh: vi.fn(),
  auth: { session: { token: "fake-token", role: "ADMIN", username: "boss" } as unknown, ready: true },
}));
vi.mock("next/navigation", () => ({
  useParams: () => ({ id: mocks.id }),
  useRouter: () => ({ push: mocks.push, replace: vi.fn(), refresh: mocks.refresh }),
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => mocks.auth,
  useAuth: () => ({ logout: vi.fn() }),
}));

const product = (extra: object = {}) => ({
  id: "p1",
  name: "린넨 셔츠",
  description: "시원한 셔츠",
  price: 8000,
  originalPrice: null,
  stock: 10,
  category: "상의",
  hashtags: [],
  imageUrl: "https://example.com/a.jpg",
  images: ["https://example.com/a.jpg"],
  ...extra,
});

const reply = (status: number, body: unknown = {}) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
const isPut = (init?: RequestInit) => init?.method === "PUT";

// GETs answer from `gets` in order (the last one repeats; an Error is thrown as a network failure); PUTs answer with `put`.
function mockApi(gets: unknown[], put: unknown = reply(200, { id: "p1" })) {
  let n = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url: string, init?: RequestInit) => {
      if (isPut(init)) return put;
      const next = gets[Math.min(n++, gets.length - 1)];
      if (next instanceof Error) throw next;
      return next;
    })
  );
}
const getCalls = () => vi.mocked(fetch).mock.calls.filter(([, init]) => !isPut(init));

beforeEach(() => {
  vi.unstubAllGlobals();
  mocks.id = "p1";
  mocks.push.mockClear();
  mocks.refresh.mockClear();
  mocks.auth = { session: { token: "fake-token", role: "ADMIN", username: "boss" }, ready: true };
});

describe("상품 수정 화면 접근", () => {
  it("실패: 비로그인이거나 준비 전이면 아무것도 그리지 않고 요청하지 않는다", () => {
    for (const auth of [
      { session: null, ready: true },
      { session: { token: "t", role: "ADMIN", username: "u" }, ready: false },
    ]) {
      mocks.auth = auth;
      mockApi([reply(200, product())]);
      const { container, unmount } = render(<AdminProductEditPage />);
      expect(container).toBeEmptyDOMElement();
      expect(fetch).not.toHaveBeenCalled();
      unmount();
    }
  });

  // useRequireRole still returns a USER session while it redirects to /login; the page must not show the form meanwhile.
  it("실패: 일반 회원(USER) 세션이면 아무것도 그리지 않는다", () => {
    mocks.auth = { session: { token: "t", role: "USER", username: "u" }, ready: true };
    mockApi([reply(200, product())]);
    const { container } = render(<AdminProductEditPage />);
    expect(container).toBeEmptyDOMElement();
  });

  it("성공: 관리자와 판매자는 상품을 불러와 수정 폼을 본다", async () => {
    for (const role of ["ADMIN", "SELLER"]) {
      mocks.auth = { session: { token: "fake-token", role, username: "boss" }, ready: true };
      mockApi([reply(200, product())]);
      const { unmount } = render(<AdminProductEditPage />);
      expect(await screen.findByRole("heading", { name: "상품 수정" })).toBeInTheDocument();
      unmount();
    }
  });
});

describe("상품 수정 화면 불러오기", () => {
  it("성공: 불러오는 동안 안내가 보이고, 끝나면 URL의 id 상품으로 채운 폼이 나온다", async () => {
    mocks.id = "p-42";
    mockApi([reply(200, product({ id: "p-42", name: "울 코트" }))]);
    render(<AdminProductEditPage />);
    expect(screen.getByRole("status")).toHaveTextContent("불러오는 중...");
    expect(screen.queryByRole("heading", { name: "상품 수정" })).not.toBeInTheDocument();
    expect(await screen.findByLabelText("상품명")).toHaveValue("울 코트");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(new URL(String(getCalls()[0][0])).pathname).toBe("/api/products/p-42");
  });

  it("실패: 상품이 없으면(404) 찾을 수 없다는 alert이고 폼은 없다", async () => {
    mockApi([reply(404)]);
    render(<AdminProductEditPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("상품을 찾을 수 없어요.");
    expect(screen.queryByLabelText("상품명")).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("실패: 불러오기가 서버 오류나 네트워크 오류면 불러오지 못했다는 alert이고 폼은 없다", async () => {
    for (const failure of [reply(500), new TypeError("network")]) {
      mockApi([failure]);
      const { unmount } = render(<AdminProductEditPage />);
      expect(await screen.findByRole("alert")).toHaveTextContent("상품 정보를 불러오지 못했습니다.");
      expect(screen.queryByLabelText("상품명")).not.toBeInTheDocument();
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
      unmount();
    }
  });

  it("성공: 불러오기에 실패하면 다시 시도 버튼이 있고, 누르면 불러오는 중 표시 뒤 다시 불러와 폼이 나온다", async () => {
    let finish: (r: unknown) => void = () => {};
    mockApi([reply(500), new Promise((resolve) => (finish = resolve))]);
    render(<AdminProductEditPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("상품 정보를 불러오지 못했습니다.");
    expect(getCalls()).toHaveLength(1);
    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(screen.getByRole("status")).toHaveTextContent("불러오는 중...");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();
    finish(reply(200, product({ name: "다시 불러온 상품" })));
    expect(await screen.findByLabelText("상품명")).toHaveValue("다시 불러온 상품");
    expect(getCalls()).toHaveLength(2);
    expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();
  });

  it("실패: 다시 시도도 실패하면 오류와 다시 시도 버튼이 다시 나온다", async () => {
    mockApi([new TypeError("network")]);
    render(<AdminProductEditPage />);
    await userEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(getCalls()).toHaveLength(2));
    expect(await screen.findByRole("alert")).toHaveTextContent("상품 정보를 불러오지 못했습니다.");
    expect(screen.getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
    expect(screen.queryByLabelText("상품명")).not.toBeInTheDocument();
  });

  it("성공: 상품이 없을 때(404)와 로딩 중에는 다시 시도 버튼이 없다", async () => {
    mockApi([reply(404)]);
    render(<AdminProductEditPage />);
    expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();
    await screen.findByRole("alert");
    expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();
  });

  it("성공: 상품 관리로 돌아가는 링크가 항상 있다 (로딩·오류·폼)", async () => {
    mockApi([reply(404)]);
    const { unmount } = render(<AdminProductEditPage />);
    expect(screen.getByRole("link", { name: "상품 관리로 돌아가기" })).toHaveAttribute("href", "/admin/products");
    await screen.findByRole("alert");
    expect(screen.getByRole("link", { name: "상품 관리로 돌아가기" })).toHaveAttribute("href", "/admin/products");
    unmount();

    mockApi([reply(200, product())]);
    render(<AdminProductEditPage />);
    await screen.findByLabelText("상품명");
    expect(screen.getByRole("link", { name: "상품 관리로 돌아가기" })).toHaveAttribute("href", "/admin/products");
  });
});

describe("상품 수정 화면 저장과 새로고침", () => {
  it("성공: 저장하면 폼이 토큰과 함께 PUT을 보내고 목록으로 이동한다", async () => {
    mockApi([reply(200, product())]);
    render(<AdminProductEditPage />);
    await userEvent.click(await screen.findByRole("button", { name: "저장하기" }));
    await waitFor(() => expect(mocks.push).toHaveBeenCalledWith("/admin/products"));
    const put = vi.mocked(fetch).mock.calls.find(([, init]) => isPut(init))!;
    expect(new URL(String(put[0])).pathname).toBe("/api/admin/products/p1");
    expect(put[1]?.headers).toMatchObject({ Authorization: "Bearer fake-token" });
  });

  it("성공: 재고 충돌 뒤 새로고침하면 상품을 다시 불러와 폼을 새 값으로 다시 만든다 (고친 내용은 버려진다)", async () => {
    mockApi(
      [reply(200, product({ stock: 10 })), reply(200, product({ name: "린넨 셔츠 (최신)", stock: 7 }))],
      reply(409, { error: "x", code: "STOCK_CHANGED" })
    );
    render(<AdminProductEditPage />);
    const name = await screen.findByLabelText("상품명");
    await userEvent.clear(name);
    await userEvent.type(name, "내가 고친 이름");
    await userEvent.click(screen.getByRole("button", { name: "저장하기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("그 사이 재고가 바뀌었어요");
    expect(screen.getByRole("button", { name: "저장하기" })).toBeDisabled();
    expect(getCalls()).toHaveLength(1);

    await userEvent.click(screen.getByRole("button", { name: "새로고침" }));
    expect(getCalls()).toHaveLength(2);
    // fresh form built from the second response: edits gone, stock reloaded, error gone, save enabled again
    expect(await screen.findByLabelText("상품명")).toHaveValue("린넨 셔츠 (최신)");
    expect(screen.getByLabelText("재고 수량")).toHaveValue(7);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "새로고침" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "저장하기" })).toBeEnabled();
  });

  it("성공: 새로고침 뒤 보내는 expectedStock은 새로 불러온 재고다", async () => {
    mockApi(
      [reply(200, product({ stock: 10 })), reply(200, product({ stock: 7 }))],
      reply(409, { code: "STOCK_CHANGED" })
    );
    render(<AdminProductEditPage />);
    await userEvent.click(await screen.findByRole("button", { name: "저장하기" }));
    await userEvent.click(await screen.findByRole("button", { name: "새로고침" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "저장하기" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "저장하기" }));
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    const puts = vi.mocked(fetch).mock.calls.filter(([, init]) => isPut(init));
    expect(puts).toHaveLength(2);
    expect(JSON.parse(String(puts[0][1]?.body))).toMatchObject({ stock: 10, expectedStock: 10 });
    expect(JSON.parse(String(puts[1][1]?.body))).toMatchObject({ stock: 7, expectedStock: 7 });
  });

  it("실패: 새로고침 중 다시 불러오기가 실패하면 폼 대신 불러오지 못했다는 alert이 나온다", async () => {
    mockApi([reply(200, product()), reply(500)], reply(409, { code: "STOCK_CHANGED" }));
    render(<AdminProductEditPage />);
    await userEvent.click(await screen.findByRole("button", { name: "저장하기" }));
    await userEvent.click(await screen.findByRole("button", { name: "새로고침" }));
    expect(await screen.findByText("상품 정보를 불러오지 못했습니다.")).toBeInTheDocument();
    expect(screen.queryByLabelText("상품명")).not.toBeInTheDocument();
  });
});
