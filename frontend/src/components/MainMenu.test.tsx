import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MainMenu } from "./MainMenu";
import { MENU_ITEMS } from "@/lib/menu";

let pathname = "/";
let query = "";
vi.mock("next/navigation", () => ({
  usePathname: () => pathname,
  useSearchParams: () => new URLSearchParams(query),
}));

// jsdom has no layout: every menu item is 60px wide, the gap is 28px and the 더보기 button is
// 60px, and the width of the row is set per test. With 500px that is 5 items + 더보기.
let boxWidth = 500;
let observerCallbacks: (() => void)[] = [];

function resizeTo(width: number) {
  boxWidth = width;
  act(() => observerCallbacks.forEach((cb) => cb()));
}

// Labels of the visible menu links that carry aria-current, whatever its value.
const currentLinks = () =>
  screen
    .getAllByRole("link")
    .filter((l) => l.hasAttribute("aria-current"))
    .map((l) => l.textContent);

const LAST = MENU_ITEMS[MENU_ITEMS.length - 1].label;

beforeEach(() => {
  pathname = "/";
  query = "";
  boxWidth = 500;
  observerCallbacks = [];
  vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(60);
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockImplementation(() => boxWidth);
  vi.stubGlobal(
    "ResizeObserver",
    class {
      constructor(cb: () => void) {
        observerCallbacks.push(cb);
      }
      observe() {}
      disconnect() {}
    }
  );
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("MainMenu", () => {
  it("성공: 넘치는 항목은 더보기로 가고 나머지는 한 줄에 보인다", () => {
    render(<MainMenu />);
    expect(screen.getAllByRole("link")).toHaveLength(5);
    expect(screen.getByRole("button", { name: /더보기/ })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: LAST })).not.toBeInTheDocument();
  });

  it("성공: 창이 넓어지면 더보기가 사라지고 모든 항목이 보인다", () => {
    render(<MainMenu />);
    resizeTo(3000);
    expect(screen.getAllByRole("link")).toHaveLength(MENU_ITEMS.length);
    expect(screen.queryByRole("button", { name: /더보기/ })).not.toBeInTheDocument();
  });

  it("성공: 창이 좁아지면 줄에 보이는 항목이 줄어든다", () => {
    render(<MainMenu />);
    resizeTo(300);
    // 60(더보기) + 28 + 60 = 148, + 28 + 60 = 236, 다음은 324 > 300
    expect(screen.getAllByRole("link")).toHaveLength(2);
    expect(screen.getByRole("button", { name: /더보기/ })).toBeInTheDocument();
  });

  it("성공: 휴대폰 너비에서는 더보기 없이 모든 항목이 가로 스크롤 줄에 있다", () => {
    vi.stubGlobal("innerWidth", 375);
    render(<MainMenu />);
    expect(screen.getAllByRole("link")).toHaveLength(MENU_ITEMS.length);
    expect(screen.queryByRole("button", { name: /더보기/ })).not.toBeInTheDocument();
  });

  it("성공: 측정용 복사본은 보조기술에서 숨겨지고 링크가 아니다", () => {
    const { container } = render(<MainMenu />);
    const hidden = container.querySelector('[aria-hidden="true"]') as HTMLElement;
    expect(hidden.querySelector("a, button")).toBeNull();
  });

  it("성공: 측정이 끝나면 데스크톱에서 줄을 감추던 클래스가 빠진다", () => {
    const { container } = render(<MainMenu />);
    expect(container.querySelector("ul")).not.toHaveClass("md:invisible");
  });

  it("성공: 더보기를 누르면 열리고 Escape로 닫히며 포커스가 버튼으로 돌아간다", async () => {
    render(<MainMenu />);
    const more = screen.getByRole("button", { name: /더보기/ });
    await userEvent.click(more);
    expect(more).toHaveAttribute("aria-expanded", "true");
    const first = screen.getByRole("link", { name: LAST });
    first.focus();

    await userEvent.keyboard("{Escape}");
    expect(more).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("link", { name: LAST })).not.toBeInTheDocument();
    expect(more).toHaveFocus();
  });

  it("성공: 바깥을 누르면 닫힌다", async () => {
    render(<MainMenu />);
    const more = screen.getByRole("button", { name: /더보기/ });
    await userEvent.click(more);
    await userEvent.click(document.body);
    expect(more).toHaveAttribute("aria-expanded", "false");
  });

  it("실패: 닫힌 상태에서 Escape를 눌러도 아무 일도 없다", async () => {
    render(<MainMenu />);
    const more = screen.getByRole("button", { name: /더보기/ });
    await userEvent.keyboard("{Escape}");
    expect(more).toHaveAttribute("aria-expanded", "false");
    expect(more).not.toHaveFocus();
  });

  it("성공: 넘치는 항목이 없어지면 열림 상태가 초기화되어 다시 좁아져도 닫혀 있다", async () => {
    render(<MainMenu />);
    await userEvent.click(screen.getByRole("button", { name: /더보기/ }));
    resizeTo(3000);
    expect(screen.queryByRole("button", { name: /더보기/ })).not.toBeInTheDocument();

    resizeTo(500);
    expect(screen.getByRole("button", { name: /더보기/ })).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("link", { name: LAST })).not.toBeInTheDocument();
  });

  it("성공: 카테고리 항목은 aria-current=page 이다", () => {
    boxWidth = 5000;
    pathname = "/";
    query = "category=" + encodeURIComponent("상의");
    render(<MainMenu />);
    expect(screen.getByRole("link", { name: "상의" })).toHaveAttribute("aria-current", "page");
    expect(currentLinks()).toEqual(["상의"]);
  });

  it.each([
    ["/best", "", "베스트"],
    ["/products", "", "전체상품"],
    ["/new", "", "신상품"],
    ["/best", "best=weekly", "베스트"],
    ["/products", "sort=price_asc", "전체상품"],
  ])("성공: 주소가 %s (쿼리 '%s') 이면 %s 항목만 aria-current=page 이다 (location 이 아니다)", (path, search, label) => {
    boxWidth = 5000;
    pathname = path;
    query = search;
    render(<MainMenu />);
    expect(screen.getByRole("link", { name: label })).toHaveAttribute("aria-current", "page");
    expect(currentLinks()).toEqual([label]);
    expect(document.querySelector('[aria-current="location"]')).toBeNull();
  });

  it.each([
    ["/", "", "메인 페이지"],
    ["/", "best=weekly", "메인 페이지의 베스트 기간 탭"],
    ["/", "sort=newest", "메인 페이지의 정렬 탭"],
    ["/", "category=" + encodeURIComponent("없는분류"), "알 수 없는 카테고리"],
    ["/products/123", "", "상품 상세"],
    ["/products/new", "", "상품 등록 같은 하위 경로"],
    ["/cart", "", "다른 페이지"],
  ])("실패: %s (쿼리 '%s') 이면 어떤 메뉴 항목도 aria-current 가 없다 (%s)", (path, search) => {
    boxWidth = 5000;
    pathname = path;
    query = search;
    render(<MainMenu />);
    expect(currentLinks()).toEqual([]);
  });

  it("성공: 메뉴 링크는 각자의 페이지로 간다", () => {
    boxWidth = 5000;
    render(<MainMenu />);
    expect(screen.getByRole("link", { name: "베스트" })).toHaveAttribute("href", "/best");
    expect(screen.getByRole("link", { name: "전체상품" })).toHaveAttribute("href", "/products");
    expect(screen.getByRole("link", { name: "신상품" })).toHaveAttribute("href", "/new");
  });
});
