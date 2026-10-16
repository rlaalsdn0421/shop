import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ProductOption } from "@/lib/api";
import { AddToCartForm } from "./AddToCartForm";

const push = vi.hoisted(() => vi.fn());
const add = vi.hoisted(() => vi.fn());
const auth = vi.hoisted(() => ({ session: null as null | { token: string } }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
vi.mock("@/cart/CartContext", () => ({ useCart: () => ({ add }) }));
vi.mock("@/auth/AuthContext", () => ({ useAuth: () => ({ session: auth.session }) }));

const opt = (id: string, colorName: string | null, sizeLabel: string | null, stock: number, price = 23000, extra: Partial<ProductOption> = {}): ProductOption => ({
  id,
  colorName,
  colorHex: colorName === "화이트" ? "#FFFFFF" : colorName === "블랙" ? "#000000" : null,
  sizeLabel,
  price,
  originalPrice: null,
  discountRate: null,
  stock,
  ...extra,
});

const OPTIONS: ProductOption[] = [
  opt("w-s", "화이트", "S", 3),
  opt("w-m", "화이트", "M", 0),
  opt("b-s", "블랙", "S", 2, 25000, { originalPrice: 30000, discountRate: 17 }),
  opt("b-m", "블랙", "M", 10, 25000),
];

function renderForm(options: ProductOption[] | undefined = OPTIONS, priceVaries = true) {
  render(
    <AddToCartForm id="p1" name="린넨 셔츠" price={23000} imageUrl="https://example.com/a.jpg" stock={0} options={options} priceVaries={priceVaries} />
  );
}
const color = (name: string) => screen.getByRole("button", { name });
const size = (name: string) => within(screen.getByRole("group", { name: "사이즈" })).getByRole("button", { name });

beforeEach(() => {
  push.mockClear();
  add.mockClear();
  auth.session = { token: "fake-token" };
});

describe("옵션 선택기 구성", () => {
  it("성공: 색상 칩(이름이 접근성 이름)과 사이즈 버튼을 그룹으로 보여준다", () => {
    renderForm();
    const colors = screen.getByRole("group", { name: "색상" });
    expect(within(colors).getAllByRole("button").map((b) => b.getAttribute("aria-label"))).toEqual(["화이트", "블랙"]);
    expect(within(screen.getByRole("group", { name: "사이즈" })).getAllByRole("button").map((b) => b.textContent)).toEqual(["S", "M"]);
    expect(color("화이트")).toHaveStyle({ backgroundColor: "#FFFFFF" });
    expect(color("화이트")).toHaveAttribute("aria-pressed", "false");
  });

  it("성공: 색 코드가 없는 색상은 글자 칩으로 보인다", () => {
    renderForm([opt("g-s", "그레이", "S", 3)]);
    expect(color("그레이")).toHaveTextContent("그레이");
    expect(color("그레이").getAttribute("style")).toBeNull();
  });

  it("성공: 사이즈만 있는 상품은 색상 줄이 없다", () => {
    renderForm([opt("s", null, "S", 3), opt("m", null, "M", 3)]);
    expect(screen.queryByRole("group", { name: "색상" })).not.toBeInTheDocument();
    expect(screen.getByRole("group", { name: "사이즈" })).toBeInTheDocument();
  });

  it("성공: 색상만 있는 상품은 사이즈 줄이 없고 색상만 고르면 옵션이 정해진다", async () => {
    renderForm([opt("w", "화이트", null, 3), opt("b", "블랙", null, 3)]);
    expect(screen.queryByRole("group", { name: "사이즈" })).not.toBeInTheDocument();
    await userEvent.click(color("블랙"));
    expect(screen.getByText("선택한 옵션")).toHaveTextContent("블랙");
    expect(screen.getByRole("button", { name: "구매하기" })).toBeEnabled();
  });

  it("성공: 눌린 색상은 aria-pressed이고 고른 색상 이름이 글자로 보인다", async () => {
    renderForm();
    await userEvent.click(color("블랙"));
    expect(color("블랙")).toHaveAttribute("aria-pressed", "true");
    expect(color("화이트")).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByText("색상").parentElement).toHaveTextContent("색상 블랙");
  });
});

describe("옵션 품절 규칙", () => {
  it("성공: 고른 색상에서 재고가 0인 사이즈는 비활성이고 취소선이다", async () => {
    renderForm();
    await userEvent.click(color("화이트"));
    expect(size("M")).toBeDisabled();
    expect(size("M")).toHaveClass("disabled:line-through");
    expect(size("S")).toBeEnabled();
    await userEvent.click(color("블랙"));
    expect(size("M")).toBeEnabled();
  });

  it("성공: 색상을 고르기 전에는 어느 색에든 재고가 있는 사이즈를 누를 수 있다", () => {
    renderForm();
    expect(size("M")).toBeEnabled();
  });

  it("실패: 모든 사이즈가 품절인 색상 칩은 비활성이다", () => {
    renderForm([opt("w-s", "화이트", "S", 0), opt("w-m", "화이트", "M", 0), opt("b-s", "블랙", "S", 2)]);
    expect(color("화이트")).toBeDisabled();
    expect(color("블랙")).toBeEnabled();
  });

  it("실패: 어느 색에도 재고가 없는 사이즈는 색을 고르기 전에도 비활성이다", () => {
    renderForm([opt("w-s", "화이트", "S", 0), opt("b-s", "블랙", "S", 0), opt("w-m", "화이트", "M", 1)]);
    expect(size("S")).toBeDisabled();
    expect(size("M")).toBeEnabled();
  });

  it("성공: 사이즈를 먼저 고르고 그 사이즈가 품절인 색으로 바꾸면 사이즈 선택이 풀린다", async () => {
    renderForm();
    await userEvent.click(size("M"));
    await userEvent.click(color("블랙"));
    expect(screen.getByRole("button", { name: "구매하기" })).toBeEnabled();
    await userEvent.click(color("화이트"));
    expect(size("M")).toBeDisabled();
    expect(size("M")).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "구매하기" })).toBeDisabled();
    expect(screen.getByText("옵션을 선택해 주세요")).toBeInTheDocument();
  });

  it("실패: 모든 옵션이 품절이면 품절 문구를 보이고 구매 버튼이 막힌다", async () => {
    renderForm([opt("w-s", "화이트", "S", 0), opt("b-s", "블랙", "S", 0)]);
    expect(screen.getByText("품절된 상품입니다.")).toBeInTheDocument();
    expect(screen.queryByText("옵션을 선택해 주세요")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "구매하기" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "장바구니" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "구매하기" }));
    expect(add).not.toHaveBeenCalled();
  });
});

describe("옵션 가격과 안내", () => {
  it("성공: 선택 전에는 최저가(가격이 다르면 ~)와 안내를 보이고 구매 버튼과 수량이 없다", () => {
    renderForm();
    expect(screen.getByText("23,000원~")).toBeInTheDocument();
    expect(screen.getByText("옵션을 선택해 주세요")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "구매하기" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "장바구니" })).toBeDisabled();
    expect(screen.queryByRole("group", { name: "수량" })).not.toBeInTheDocument();
    expect(screen.queryByText(/개 남음/)).not.toBeInTheDocument();
  });

  it("성공: 가격이 모두 같으면 ~를 붙이지 않는다", () => {
    renderForm([opt("w-s", "화이트", "S", 3), opt("b-s", "블랙", "S", 3)], false);
    expect(screen.getByText("23,000원")).toBeInTheDocument();
    expect(screen.queryByText("23,000원~")).not.toBeInTheDocument();
  });

  it("성공: 색상만 고르고 사이즈를 안 골랐으면 아직 선택 전이다", async () => {
    renderForm();
    await userEvent.click(color("블랙"));
    expect(screen.getByText("옵션을 선택해 주세요")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "구매하기" })).toBeDisabled();
  });

  it("성공: 둘 다 고르면 그 옵션의 가격, 할인, 이름, 합계를 보이고 버튼이 켜진다", async () => {
    renderForm();
    await userEvent.click(color("블랙"));
    await userEvent.click(size("S"));
    expect(screen.queryByText("옵션을 선택해 주세요")).not.toBeInTheDocument();
    expect(screen.getByText("선택한 옵션")).toHaveTextContent("블랙 / S");
    expect(screen.getByText("17%")).toBeInTheDocument();
    expect(screen.getByText("30,000원").tagName).toBe("DEL");
    expect(screen.getAllByText("25,000원")).toHaveLength(2); // option price + total x 1
    expect(screen.getByRole("button", { name: "구매하기" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "장바구니" })).toBeEnabled();
  });

  it("성공: 거의 매진 안내와 수량 한도는 고른 옵션의 재고 기준이다", async () => {
    renderForm();
    await userEvent.click(color("블랙"));
    await userEvent.click(size("S"));
    expect(screen.getByText("2개 남음 · 거의 매진")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "수량 증가" }));
    expect(screen.getByRole("button", { name: "수량 증가" })).toBeDisabled();
    expect(screen.getByText("50,000원")).toBeInTheDocument();
    await userEvent.click(size("M"));
    expect(screen.queryByText(/개 남음/)).not.toBeInTheDocument();
    expect(screen.getByRole("group", { name: "수량" })).toHaveTextContent("1");
    await userEvent.click(screen.getByRole("button", { name: "수량 증가" }));
    expect(screen.getByRole("button", { name: "수량 증가" })).toBeEnabled();
  });

  it("성공: 옵션 가격이 할인 없이 같으면 할인 표시가 없다", async () => {
    renderForm();
    await userEvent.click(color("화이트"));
    await userEvent.click(size("S"));
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
    expect(document.querySelector("del")).toBeNull();
  });
});

describe("옵션 상품 담기", () => {
  it("성공: 장바구니는 optionId, 옵션 이름, 옵션 가격과 함께 고른 수량을 담는다", async () => {
    renderForm();
    await userEvent.click(color("블랙"));
    await userEvent.click(size("M"));
    await userEvent.click(screen.getByRole("button", { name: "수량 증가" }));
    await userEvent.click(screen.getByRole("button", { name: "장바구니" }));
    expect(add).toHaveBeenCalledWith(
      { productId: "p1", optionId: "b-m", optionLabel: "블랙 / M", name: "린넨 셔츠", price: 25000, imageUrl: "https://example.com/a.jpg" },
      2
    );
    expect(screen.getByRole("status")).toHaveTextContent("장바구니에 담았습니다.");
    expect(push).not.toHaveBeenCalled();
  });

  it("성공: 구매하기는 담고 장바구니로 이동한다", async () => {
    renderForm();
    await userEvent.click(color("화이트"));
    await userEvent.click(size("S"));
    await userEvent.click(screen.getByRole("button", { name: "구매하기" }));
    expect(add).toHaveBeenCalledWith(expect.objectContaining({ optionId: "w-s", price: 23000 }), 1);
    expect(push).toHaveBeenCalledWith("/cart");
  });

  it("실패: 로그인하지 않았으면 담지 않고 로그인으로 보낸다", async () => {
    auth.session = null;
    renderForm();
    await userEvent.click(color("화이트"));
    await userEvent.click(size("S"));
    await userEvent.click(screen.getByRole("button", { name: "구매하기" }));
    expect(add).not.toHaveBeenCalled();
    expect(push).toHaveBeenCalledWith("/login");
  });

  it("실패: 옵션을 고르기 전에는 눌러도 담기지 않는다", async () => {
    renderForm();
    await userEvent.click(screen.getByRole("button", { name: "구매하기" }));
    await userEvent.click(screen.getByRole("button", { name: "장바구니" }));
    expect(add).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });
});

describe("옵션이 없는 상품(옛 방식)", () => {
  it.each([[undefined], [[]]])("성공: options가 %j이면 옛 화면 그대로 선택기 없이 재고로 판매한다", async (options) => {
    render(<AddToCartForm id="p1" name="린넨 셔츠" price={8000} imageUrl="https://example.com/a.jpg" stock={3} options={options as ProductOption[] | undefined} />);
    expect(screen.queryByRole("group", { name: "색상" })).not.toBeInTheDocument();
    expect(screen.queryByText("옵션을 선택해 주세요")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "구매하기" })).toBeEnabled();
    await userEvent.click(screen.getByRole("button", { name: "장바구니" }));
    expect(add).toHaveBeenCalledWith({ productId: "p1", name: "린넨 셔츠", price: 8000, imageUrl: "https://example.com/a.jpg" }, 1);
    expect(Object.keys(add.mock.calls[0][0])).not.toContain("optionId");
  });

  it("실패: options가 null로 와도 깨지지 않고 옛 방식으로 판매한다", () => {
    render(<AddToCartForm id="p1" name="린넨 셔츠" price={8000} imageUrl="x" stock={0} options={null as unknown as undefined} />);
    expect(screen.getByText("품절된 상품입니다.")).toBeInTheDocument();
  });
});
