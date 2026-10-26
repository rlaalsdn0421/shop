import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { WishButton } from "./WishButton";

const push = vi.hoisted(() => vi.fn());
const auth = vi.hoisted(() => ({ session: null as null | { token: string } }));
const wish = vi.hoisted(() => ({
  ready: true,
  loadFailed: false,
  wished: new Set<string>(),
  toggle: vi.fn(),
  pending: new Set<string>(),
  lastError: null as null | { productId: string; message: string },
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
vi.mock("@/auth/AuthContext", () => ({ useAuth: () => ({ session: auth.session }) }));
vi.mock("@/wishlist/WishlistContext", () => ({
  useWishlist: () => ({
    ready: wish.ready,
    loadFailed: wish.loadFailed,
    ids: wish.wished,
    isWished: (id: string) => wish.wished.has(id),
    toggle: wish.toggle,
    pending: wish.pending,
    lastError: wish.lastError,
  }),
}));

const LOAD_FAILED = "찜 정보를 불러오지 못했어요. 페이지를 새로고침해 주세요.";
const fetchMock = vi.fn();

function renderBtn(variant: "card" | "detail" = "card", productId = "p1") {
  return render(<WishButton productId={productId} name="린넨 셔츠" variant={variant} />);
}
const button = () => screen.getByRole("button", { name: "찜하기 - 린넨 셔츠" });
const heartSvg = () => document.querySelector("svg") as SVGElement;

beforeEach(() => {
  push.mockClear();
  wish.toggle.mockClear();
  fetchMock.mockClear();
  vi.stubGlobal("fetch", fetchMock);
  auth.session = { token: "fake-token" };
  wish.ready = true;
  wish.loadFailed = false;
  wish.wished = new Set();
  wish.pending = new Set();
  wish.lastError = null;
});

describe.each(["card", "detail"] as const)("WishButton 상태 표시 (%s)", (variant) => {
  it("성공: 찜하지 않은 상품은 aria-pressed=false, 빈 하트다", () => {
    renderBtn(variant);
    expect(button()).toHaveAttribute("aria-pressed", "false");
    expect(heartSvg()).toHaveClass("fill-none");
    expect(heartSvg()).not.toHaveClass("fill-current");
  });

  it("성공: 찜한 상품도 이름은 그대로 '찜하기 - 이름'이고 aria-pressed=true, 채운 하트다", () => {
    wish.wished = new Set(["p1"]);
    renderBtn(variant);
    // The accessible name never flips; aria-pressed carries the state.
    expect(button()).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByRole("button", { name: /찜 해제/ })).not.toBeInTheDocument();
    expect(heartSvg()).toHaveClass("fill-current");
    expect(heartSvg()).not.toHaveClass("fill-none");
  });

  it("실패: 다른 상품이 찜돼 있어도 이 상품은 찜 안 한 상태다", () => {
    wish.wished = new Set(["other"]);
    renderBtn(variant);
    expect(button()).toHaveAttribute("aria-pressed", "false");
  });

  it("성공: 버튼은 하나뿐이고 버튼별 role=status / role=alert 영역이 없다 (안내는 Provider의 한 곳)", () => {
    wish.lastError = { productId: "p1", message: "오류" };
    wish.loadFailed = true;
    renderBtn(variant);
    expect(screen.getAllByRole("button")).toHaveLength(1);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(document.querySelector(".sr-only")).toBeNull();
    expect(button().parentElement).toBe(document.body.firstElementChild);
  });
});

describe("WishButton 모양", () => {
  it("성공: card는 하트만 있고 보이는 글자가 없으며 오른쪽 위 h-10 w-10 자리다", () => {
    renderBtn("card");
    expect(button().textContent).toBe("");
    expect(button()).toHaveClass("absolute", "right-1", "top-1", "h-10", "w-10");
  });

  it("성공: detail의 보이는 글자는 찜한 상태여도 항상 '찜하기'다", () => {
    const { unmount } = renderBtn("detail");
    expect(button()).toHaveTextContent("찜하기");
    unmount();
    wish.wished = new Set(["p1"]);
    renderBtn("detail");
    expect(button().textContent).toBe("찜하기");
    expect(button()).not.toHaveClass("absolute");
  });
});

describe("WishButton 클릭", () => {
  it("성공: 로그인했으면 누르면 이 상품 id로 toggle을 부른다", async () => {
    renderBtn("card", "p9");
    await userEvent.click(screen.getByRole("button"));
    expect(wish.toggle).toHaveBeenCalledTimes(1);
    expect(wish.toggle).toHaveBeenCalledWith("p9");
    expect(push).not.toHaveBeenCalled();
  });

  it("실패: 로그아웃(auth 준비됨)이면 활성이고 흐리지 않으며, 누르면 /login으로 가고 toggle도 요청도 없다", async () => {
    auth.session = null;
    renderBtn();
    expect(button()).toBeEnabled();
    expect(button()).not.toHaveAttribute("aria-disabled");
    expect(button()).not.toHaveClass("opacity-50");
    await userEvent.click(button());
    expect(push).toHaveBeenCalledTimes(1);
    expect(push).toHaveBeenCalledWith("/login");
    expect(wish.toggle).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("실패: auth를 아직 못 읽었으면(세션 없음, ready 아님) 비활성은 아니지만 aria-disabled이고 흐리지 않으며, 눌러도 아무 일도 없다", async () => {
    auth.session = null;
    wish.ready = false;
    renderBtn();
    expect(button()).toBeEnabled();
    expect(button()).toHaveAttribute("aria-disabled", "true");
    expect(button()).not.toHaveClass("opacity-50");
    await userEvent.click(button());
    expect(push).not.toHaveBeenCalled();
    expect(wish.toggle).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("실패: 로그인했는데 id를 아직 못 불러왔으면 비활성이고 흐리며 눌러도 아무 일도 없다", async () => {
    wish.ready = false;
    renderBtn();
    expect(button()).toBeDisabled();
    expect(button()).toHaveClass("opacity-50");
    expect(button()).not.toHaveAttribute("aria-disabled");
    await userEvent.click(button());
    expect(wish.toggle).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });

  it("실패: 이 상품이 요청 중이면 비활성이고 흐리지만, 다른 상품이 요청 중이면 활성이다", async () => {
    wish.pending = new Set(["p1"]);
    const { unmount } = renderBtn("card", "p1");
    expect(button()).toBeDisabled();
    expect(button()).toHaveClass("opacity-50");
    await userEvent.click(button());
    expect(wish.toggle).not.toHaveBeenCalled();
    unmount();
    renderBtn("card", "p2");
    expect(button()).toBeEnabled();
    expect(button()).not.toHaveClass("opacity-50");
  });

  it("성공: 활성 상태에서는 흐리지 않는다", () => {
    renderBtn();
    expect(button()).not.toHaveClass("opacity-50");
    expect(button()).not.toHaveAttribute("aria-disabled");
  });
});

describe.each(["card", "detail"] as const)("WishButton title (%s)", (variant) => {
  it("성공: 이 상품의 변경 오류 문구가 title이다", () => {
    wish.lastError = { productId: "p1", message: "찜은 최대 200개까지 담을 수 있어요." };
    renderBtn(variant);
    expect(button()).toHaveAttribute("title", "찜은 최대 200개까지 담을 수 있어요.");
  });

  it("실패: 다른 상품의 오류는 title이 되지 않는다", () => {
    wish.lastError = { productId: "other", message: "남의 오류" };
    renderBtn(variant);
    expect(button()).not.toHaveAttribute("title");
  });

  it("실패: 오류가 없으면 title이 없다", () => {
    renderBtn(variant);
    expect(button()).not.toHaveAttribute("title");
  });

  it("성공: 로그인했고 loadFailed면 비활성이며 title이 불러오기 실패 안내다", () => {
    wish.loadFailed = true;
    wish.ready = false;
    renderBtn(variant);
    expect(button()).toBeDisabled();
    expect(button()).toHaveAttribute("title", LOAD_FAILED);
  });

  it("성공: loadFailed여도 이 상품의 변경 오류 문구가 title에서 이긴다", () => {
    wish.loadFailed = true;
    wish.ready = false;
    wish.lastError = { productId: "p1", message: "변경 오류" };
    renderBtn(variant);
    expect(button()).toHaveAttribute("title", "변경 오류");
  });

  it("실패: 로그아웃 상태에서는 loadFailed여도 title이 없고 활성이다", () => {
    auth.session = null;
    wish.loadFailed = true;
    wish.ready = true;
    renderBtn(variant);
    expect(button()).not.toHaveAttribute("title");
    expect(button()).toBeEnabled();
  });

  it("실패: loadFailed가 아니면 불러오기 실패 title이 없다", () => {
    renderBtn(variant);
    expect(button()).not.toHaveAttribute("title", LOAD_FAILED);
  });
});
