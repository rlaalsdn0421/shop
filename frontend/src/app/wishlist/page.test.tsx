import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import WishlistPage from "./page";
import { AuthError } from "@/lib/api";

const mocks = vi.hoisted(() => ({
  logout: vi.fn(),
  listWishlist: vi.fn(),
  auth: { session: { token: "token-a", role: "USER", username: "buyer" } as unknown, ready: true },
  wl: { ids: new Set<string>(), ready: true },
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => mocks.auth,
  useAuth: () => ({ logout: mocks.logout }),
}));
vi.mock("@/lib/api", async (orig) => ({ ...(await orig<typeof import("@/lib/api")>()), listWishlist: mocks.listWishlist }));
vi.mock("@/wishlist/WishlistContext", () => ({ useWishlist: () => mocks.wl }));
// The real heart is covered by WishButton.test.tsx.
vi.mock("@/components/WishButton", () => ({
  WishButton: ({ productId }: { productId: string }) => <button type="button">heart-{productId}</button>,
}));

const item = (id: string, name = `상품${id}`) => ({ id, name, price: 1000, imageUrl: "https://example.com/x.jpg" });

// A new Set each time: the page reacts to the identity of `ids`, like the real provider that builds a new Set per change.
function setIds(ids: string[], ready = true) {
  mocks.wl = { ids: new Set(ids), ready };
}
// The always-mounted sr-only live region (the loading line is a second role=status without sr-only).
const announce = () => document.querySelector('p[role="status"].sr-only') as HTMLElement;
const heading = () => screen.getByRole("heading", { level: 1, name: "찜한 상품" });

beforeEach(() => {
  mocks.logout.mockClear();
  mocks.listWishlist.mockReset();
  mocks.auth = { session: { token: "token-a", role: "USER", username: "buyer" }, ready: true };
  setIds([], false);
});

describe("찜한 상품 페이지 접근", () => {
  it("실패: 세션이 없으면 아무것도 그리지 않고 요청하지 않는다 (리다이렉트는 useRequireRole 몫)", () => {
    mocks.auth = { session: null, ready: true };
    const { container } = render(<WishlistPage />);
    expect(container).toBeEmptyDOMElement();
    expect(mocks.listWishlist).not.toHaveBeenCalled();
  });

  it("실패: auth가 아직 준비되지 않았으면 아무것도 그리지 않는다", () => {
    mocks.auth = { session: { token: "token-a", role: "USER" }, ready: false };
    const { container } = render(<WishlistPage />);
    expect(container).toBeEmptyDOMElement();
    expect(mocks.listWishlist).not.toHaveBeenCalled();
  });
});

describe("찜한 상품 페이지 목록", () => {
  it("성공: 제목과 상품 카드 격자를 그리고 토큰으로 한 번 불러온다", async () => {
    setIds(["1", "2"]);
    mocks.listWishlist.mockResolvedValue([item("1"), item("2")]);
    render(<WishlistPage />);
    expect(await screen.findByText("상품1")).toBeInTheDocument();
    expect(screen.getByText("상품2")).toBeInTheDocument();
    expect(heading()).toBeInTheDocument();
    expect(screen.getByText("상품1").closest("a")).toHaveAttribute("href", "/products/1");
    expect(screen.getByRole("button", { name: "heart-1" })).toBeInTheDocument();
    expect(mocks.listWishlist).toHaveBeenCalledTimes(1);
    expect(mocks.listWishlist).toHaveBeenCalledWith("token-a");
    expect(screen.queryByText("불러오는 중...")).not.toBeInTheDocument();
  });

  it("성공: 처음에는 알림 영역이 마운트돼 있지만 비어 있고 제목에 포커스도 없다", async () => {
    setIds(["1"]);
    mocks.listWishlist.mockResolvedValue([item("1")]);
    render(<WishlistPage />);
    await screen.findByText("상품1");
    expect(announce()).toBeInTheDocument();
    expect(announce()).toHaveAttribute("aria-live", "polite");
    expect(announce().textContent).toBe("");
    expect(heading()).not.toHaveFocus();
    expect(heading()).toHaveAttribute("tabindex", "-1");
  });

  it("성공: 목록을 불러오는 동안은 '불러오는 중...'만 보이고, 비어 있음 안내는 없다", async () => {
    let resolve!: (v: unknown[]) => void;
    mocks.listWishlist.mockReturnValue(new Promise((r) => (resolve = r)));
    setIds(["1"]);
    render(<WishlistPage />);
    expect(screen.getByText("불러오는 중...")).toBeInTheDocument();
    expect(screen.queryByText("아직 찜한 상품이 없어요.")).not.toBeInTheDocument();
    resolve([item("1")]);
    expect(await screen.findByText("상품1")).toBeInTheDocument();
    expect(screen.queryByText("불러오는 중...")).not.toBeInTheDocument();
  });

  it("성공: 로딩 표시는 id 준비 여부가 아니라 목록 요청에만 달려 있다", async () => {
    setIds([], true);
    mocks.listWishlist.mockReturnValue(new Promise(() => {}));
    render(<WishlistPage />);
    expect(screen.getByText("불러오는 중...")).toBeInTheDocument();
    expect(screen.queryByText("아직 찜한 상품이 없어요.")).not.toBeInTheDocument();
  });

  it("성공: 비어 있으면 안내 문구와 홈 링크를 보여준다", async () => {
    mocks.listWishlist.mockResolvedValue([]);
    render(<WishlistPage />);
    expect(await screen.findByText("아직 찜한 상품이 없어요.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "홈으로" })).toHaveAttribute("href", "/");
    expect(screen.queryByText("불러오는 중...")).not.toBeInTheDocument();
  });

  it("실패: 목록이 실패하면 role=alert와 '다시 불러오기'가 나오고, 누르면 다시 요청해 복구된다", async () => {
    mocks.listWishlist.mockRejectedValueOnce(new Error("down")).mockResolvedValueOnce([item("1")]);
    setIds(["1"]);
    render(<WishlistPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("찜한 상품을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
    expect(screen.queryByText("불러오는 중...")).not.toBeInTheDocument();
    expect(screen.queryByText("아직 찜한 상품이 없어요.")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "다시 불러오기" }));
    expect(await screen.findByText("상품1")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(mocks.listWishlist).toHaveBeenCalledTimes(2);
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("실패: AuthError면 주문 재시도 키를 지키며(keepOrderAttempt) 로그아웃하고 오류 화면은 보이지 않는다", async () => {
    mocks.listWishlist.mockRejectedValue(new AuthError());
    render(<WishlistPage />);
    await waitFor(() => expect(mocks.logout).toHaveBeenCalledTimes(1));
    expect(mocks.logout).toHaveBeenCalledWith({ keepOrderAttempt: true });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("찜한 상품 페이지: 받은 목록이 기준이다", () => {
  it("성공: ids가 아직 안 불러와졌어도(비어 있고 ready 아님) 받은 목록을 모두 보여준다", async () => {
    setIds([], false);
    mocks.listWishlist.mockResolvedValue([item("1"), item("2")]);
    render(<WishlistPage />);
    expect(await screen.findByText("상품1")).toBeInTheDocument();
    expect(screen.getByText("상품2")).toBeInTheDocument();
    expect(screen.queryByText("아직 찜한 상품이 없어요.")).not.toBeInTheDocument();
  });

  it("성공: ids가 낡아서 받은 상품이 ids에 없어도 숨기지 않는다 (ready여도)", async () => {
    setIds(["unrelated"], true);
    mocks.listWishlist.mockResolvedValue([item("1"), item("2")]);
    render(<WishlistPage />);
    expect(await screen.findByText("상품1")).toBeInTheDocument();
    expect(screen.getByText("상품2")).toBeInTheDocument();
    expect(announce().textContent).toBe("");
  });

  it("성공: ids가 비어 있다가 불러와져도 아무 카드도 사라지지 않는다 (처음 불러오기는 해제가 아니다)", async () => {
    setIds([], false);
    mocks.listWishlist.mockResolvedValue([item("1"), item("2")]);
    const { rerender } = render(<WishlistPage />);
    await screen.findByText("상품1");
    setIds(["1"], true);
    rerender(<WishlistPage />);
    expect(screen.getByText("상품1")).toBeInTheDocument();
    expect(screen.getByText("상품2")).toBeInTheDocument();
    expect(announce().textContent).toBe("");
    expect(heading()).not.toHaveFocus();
  });
});

describe("찜한 상품 페이지: 이 화면에서 해제한 카드", () => {
  it("성공: ids에서 빠진 카드는 다시 요청 없이 바로 사라지고, 알림 문구가 나오며, 마지막이면 빈 화면이 된다", async () => {
    setIds(["1", "2"]);
    mocks.listWishlist.mockResolvedValue([item("1"), item("2")]);
    const { rerender } = render(<WishlistPage />);
    expect(await screen.findByText("상품1")).toBeInTheDocument();
    setIds(["2"]);
    rerender(<WishlistPage />);
    expect(screen.queryByText("상품1")).not.toBeInTheDocument();
    expect(screen.getByText("상품2")).toBeInTheDocument();
    expect(announce()).toHaveTextContent("찜을 해제했어요");
    setIds([]);
    rerender(<WishlistPage />);
    expect(screen.queryByText("상품2")).not.toBeInTheDocument();
    expect(screen.getByText("아직 찜한 상품이 없어요.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "홈으로" })).toHaveAttribute("href", "/");
    expect(mocks.listWishlist).toHaveBeenCalledTimes(1);
  });

  it("성공: 해제가 실패해 ids에 다시 들어오면 카드가 돌아오고 알림 문구는 지워진다", async () => {
    setIds(["1", "2"]);
    mocks.listWishlist.mockResolvedValue([item("1"), item("2")]);
    const { rerender } = render(<WishlistPage />);
    await screen.findByText("상품1");
    setIds(["2"]);
    rerender(<WishlistPage />);
    expect(screen.queryByText("상품1")).not.toBeInTheDocument();
    setIds(["1", "2"]);
    rerender(<WishlistPage />);
    expect(screen.getByText("상품1")).toBeInTheDocument();
    expect(screen.getByText("상품2")).toBeInTheDocument();
    expect(announce().textContent).toBe("");
  });

  it("성공: 카드가 사라지면 포커스가 제목으로 가고, 또 사라질 때마다 다시 간다", async () => {
    setIds(["1", "2", "3"]);
    mocks.listWishlist.mockResolvedValue([item("1"), item("2"), item("3")]);
    const { rerender } = render(<WishlistPage />);
    await screen.findByText("상품1");
    expect(heading()).not.toHaveFocus();
    setIds(["2", "3"]);
    rerender(<WishlistPage />);
    expect(heading()).toHaveFocus();
    (document.activeElement as HTMLElement).blur();
    expect(heading()).not.toHaveFocus();
    setIds(["3"]);
    rerender(<WishlistPage />);
    expect(heading()).toHaveFocus();
  });

  it("실패: 해제가 롤백돼 카드가 돌아올 때는 포커스를 제목으로 가져가지 않는다", async () => {
    setIds(["1", "2"]);
    mocks.listWishlist.mockResolvedValue([item("1"), item("2")]);
    const { rerender } = render(<WishlistPage />);
    await screen.findByText("상품1");
    setIds(["2"]);
    rerender(<WishlistPage />);
    (document.activeElement as HTMLElement).blur();
    setIds(["1", "2"]);
    rerender(<WishlistPage />);
    expect(heading()).not.toHaveFocus();
  });
});
