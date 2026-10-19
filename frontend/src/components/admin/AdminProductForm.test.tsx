import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminProductForm } from "./AdminProductForm";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => ({ session: { token: "fake-token", role: "ADMIN", username: "seller" }, ready: true }),
}));

function mockFetchOnce(status: number, body: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({ ok: status >= 200 && status < 300, status, json: async () => body })
  );
}

beforeEach(() => {
  vi.unstubAllGlobals();
  push.mockClear();
});

const originalPriceInput = () => screen.getByLabelText("정가 (선택)");
const hashtagInput = () => screen.getByLabelText("해시태그 (최대 3개, 공백으로 구분)");

async function fillRequired(price = "8000") {
  await userEvent.type(screen.getByLabelText("상품명"), "린넨 셔츠");
  await userEvent.type(screen.getByLabelText("상품 설명"), "시원한 셔츠");
  await userEvent.type(screen.getByLabelText("가격"), price);
  await userEvent.type(screen.getByLabelText("이미지 주소 1"), "https://example.com/a.jpg");
  await userEvent.type(screen.getByLabelText("재고 수량"), "5");
}

// The inline messages live in always-mounted status regions; only the non-empty ones count.
const messages = () =>
  screen.getAllByRole("status").map((s) => s.textContent).filter(Boolean);
const form = () => screen.getByRole("button", { name: /등록/ }).closest("form")!;
const submit = () => userEvent.click(screen.getByRole("button", { name: "등록하기" }));
const sentBody = () => JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));

describe("AdminProductForm 정가/해시태그", () => {
  it("성공: 정가와 해시태그를 안 쓰면 요청에 두 필드가 아예 없다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
    expect(sentBody()).toEqual({
      name: "린넨 셔츠",
      description: "시원한 셔츠",
      price: 8000,
      imageUrl: "https://example.com/a.jpg",
      imageUrls: ["https://example.com/a.jpg"],
      stock: 5,
      category: "",
    });
  });

  it("성공: 정가는 숫자로, 해시태그는 # 없는 배열로 보낸다 (공백/중복/# 정리)", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(originalPriceInput(), "10000");
    await userEvent.type(hashtagInput(), "#여름  린넨 #린넨 Daily_Look");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({
      price: 8000,
      originalPrice: 10000,
      hashtags: ["여름", "린넨", "Daily_Look"],
    });
  });

  it("성공: 정가 칸의 최솟값은 판매가 + 1이다", async () => {
    render(<AdminProductForm />);
    await userEvent.type(screen.getByLabelText("가격"), "8000");
    expect(originalPriceInput()).toHaveAttribute("min", "8001");
  });

  it("실패: 정가가 판매가 이하이면 오류를 보여주고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(originalPriceInput(), "8000");
    expect(messages()).toEqual(["정가는 판매가보다 커야 해요."]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(originalPriceInput()).toHaveAccessibleDescription("정가는 판매가보다 커야 해요.");
    await submit();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 해시태그가 4개면 오류를 보여주고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "a b c d");
    expect(messages()).toEqual(["해시태그는 최대 3개까지 입력할 수 있어요."]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(hashtagInput()).toHaveAccessibleDescription("해시태그는 최대 3개까지 입력할 수 있어요.");
    await submit();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 21자 해시태그는 오류", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "가".repeat(21));
    expect(messages()).toEqual(["해시태그는 하나당 20자까지 입력할 수 있어요."]);
    await submit();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 허용되지 않는 문자가 있으면 오류, 고치면 사라진다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "여름,린넨");
    expect(messages()).toEqual(["해시태그는 글자, 숫자, 밑줄(_)만 쓸 수 있어요."]);
    await submit();
    expect(fetch).not.toHaveBeenCalled();

    await userEvent.clear(hashtagInput());
    await userEvent.type(hashtagInput(), "여름 린넨");
    expect(messages()).toEqual([]);
    expect(hashtagInput()).not.toHaveAttribute("aria-invalid");
  });

  it("실패: 정가 가드 — 네이티브 검증을 거치지 않고 submit해도 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(originalPriceInput(), "8000");
    fireEvent.submit(form());
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 해시태그 가드 — 네이티브 검증 없이 submit해도 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "a b c d");
    fireEvent.submit(form());
    expect(fetch).not.toHaveBeenCalled();
  });

  it("성공: 막힌 제출은 조용하지 않다 — 첫 잘못된 입력으로 포커스가 가고 메시지는 그대로 보인다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "a b c d");
    await userEvent.type(originalPriceInput(), "8000");
    // Two invalid fields: the one that comes first in the form gets the focus.
    fireEvent.submit(form());
    expect(originalPriceInput()).toHaveFocus();
    expect(screen.getAllByRole("status").map((s) => s.textContent)).toEqual(
      expect.arrayContaining(["정가는 판매가보다 커야 해요.", "해시태그는 최대 3개까지 입력할 수 있어요."])
    );

    await userEvent.clear(originalPriceInput());
    fireEvent.submit(form());
    expect(hashtagInput()).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 판매가/정가가 2147483647을 넘으면 오류를 보여주고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired("2147483648");
    expect(screen.getByLabelText("가격")).toHaveAttribute("max", "2147483647");
    expect(originalPriceInput()).toHaveAttribute("max", "2147483647");
    expect(messages()).toEqual(["가격은 2,147,483,647원 이하로 입력해 주세요."]);
    fireEvent.submit(form());
    expect(screen.getByLabelText("가격")).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();

    await userEvent.clear(screen.getByLabelText("가격"));
    await userEvent.type(screen.getByLabelText("가격"), "8000");
    await userEvent.type(originalPriceInput(), "2147483648");
    expect(messages()).toEqual(["정가는 2,147,483,647원 이하로 입력해 주세요."]);
    fireEvent.submit(form());
    expect(originalPriceInput()).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("성공: 2147483647은 그대로 보낸다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired("2147483647");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().price).toBe(2147483647);
  });

  it("성공: 빠르게 두 번 눌러도 POST는 한 번이고, 성공 뒤에도 버튼은 잠겨 있다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    fireEvent.submit(form());
    fireEvent.submit(form());
    await waitFor(() => expect(push).toHaveBeenCalledTimes(1));
    await userEvent.click(screen.getByRole("button", { name: /등록/ }));
    fireEvent.submit(form());
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: /등록/ })).toBeDisabled();
  });

  it("실패: 서버 오류 뒤에는 잠금이 풀려 다시 제출할 수 있다", async () => {
    mockFetchOnce(400, { error: "등록 실패" });
    render(<AdminProductForm />);
    await fillRequired();
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("등록 실패");
    expect(screen.getByRole("button", { name: "등록하기" })).toBeEnabled();
    await submit();
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  });

  it("실패: 서버가 보낸 오류 메시지를 그대로 보여준다", async () => {
    mockFetchOnce(400, { error: "정가는 판매가보다 커야 해요." });
    render(<AdminProductForm />);
    await fillRequired();
    await userEvent.type(hashtagInput(), "ok");
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("정가는 판매가보다 커야 해요.");
    expect(push).not.toHaveBeenCalled();
  });
});

describe("AdminProductForm 이미지 여러 장", () => {
  const addButton = () => screen.getByRole("button", { name: "이미지 추가" });
  const addRow = () => userEvent.click(addButton());
  const rowInput = (n: number) => screen.getByLabelText(`이미지 주소 ${n}`);

  it("성공: 처음에는 이미지 주소 한 줄만 있고 삭제 버튼은 없다", () => {
    render(<AdminProductForm />);
    expect(rowInput(1)).toBeRequired();
    expect(screen.queryByLabelText("이미지 주소 2")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
  });

  it("성공: 추가하면 새 줄에 포커스가 가고, 삭제하면 그 줄이 사라지고 값은 남은 줄에 그대로 있다", async () => {
    render(<AdminProductForm />);
    await userEvent.type(rowInput(1), "https://example.com/a.jpg");
    await addRow();
    expect(rowInput(2)).toHaveFocus();
    await userEvent.type(rowInput(2), "https://example.com/b.jpg");
    await addRow();
    await userEvent.type(rowInput(3), "https://example.com/c.jpg");

    await userEvent.click(screen.getByRole("button", { name: "이미지 주소 2 삭제" }));
    expect(screen.queryByLabelText("이미지 주소 3")).not.toBeInTheDocument();
    expect(rowInput(1)).toHaveValue("https://example.com/a.jpg");
    expect(rowInput(2)).toHaveValue("https://example.com/c.jpg");
    expect(rowInput(1)).toHaveFocus();
  });

  it("성공: 10장까지 늘릴 수 있고 그 이후에는 추가 버튼이 막힌다", async () => {
    render(<AdminProductForm />);
    for (let i = 0; i < 9; i++) await addRow();
    expect(rowInput(10)).toBeInTheDocument();
    expect(addButton()).toBeDisabled();
    expect(screen.getByText("이미지는 최대 10장까지 등록할 수 있어요.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "이미지 주소 10 삭제" }));
    expect(addButton()).toBeEnabled();
  });

  it("성공: imageUrl(첫 번째)과 imageUrls(전부)를 함께 보내고, 앞뒤 공백은 지우고 빈 줄은 뺀다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await addRow();
    await addRow();
    await addRow();
    await userEvent.type(rowInput(2), "  https://example.com/b.jpg  ");
    await userEvent.type(rowInput(4), "https://example.com/c.jpg");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
    expect(sentBody()).toMatchObject({
      imageUrl: "https://example.com/a.jpg",
      imageUrls: ["https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg"],
    });
  });

  it("성공: 한 장만 올리는 옛 방식도 imageUrl과 한 개짜리 imageUrls로 보낸다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    const body = sentBody();
    expect(body.imageUrl).toBe("https://example.com/a.jpg");
    expect(body.imageUrls).toEqual(["https://example.com/a.jpg"]);
  });

  it("실패: 같은 주소를 두 번 넣으면 그 줄에 오류를 보여주고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await addRow();
    await userEvent.type(rowInput(2), " https://example.com/a.jpg");
    expect(messages()).toEqual(["이미 입력한 이미지 주소예요."]);
    expect(rowInput(2)).toHaveAccessibleDescription("이미 입력한 이미지 주소예요.");
    expect(rowInput(1)).not.toHaveAttribute("aria-invalid");
    // The native check does not stop this one, so the guard has to.
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(rowInput(2)).toHaveFocus();

    await userEvent.clear(rowInput(2));
    await userEvent.type(rowInput(2), "https://example.com/b.jpg");
    expect(messages()).toEqual([]);
  });

  it("실패: 첫 줄이 비었거나 공백뿐이면 두 번째 줄이 차 있어도 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    await addRow();
    await userEvent.type(rowInput(2), "https://example.com/b.jpg");
    await userEvent.clear(rowInput(1));
    await userEvent.type(rowInput(1), "   ");
    fireEvent.submit(form());
    expect(await screen.findByRole("alert")).toHaveTextContent("첫 번째 이미지 주소를 입력해 주세요.");
    expect(rowInput(1)).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe("AdminProductForm 재고 한도", () => {
  const stockInput = () => screen.getByLabelText("재고 수량");
  const TOO_MANY = "재고는 1,000,000개 이하로 입력해 주세요.";

  it("실패: 재고가 1,000,000개를 넘으면 오류를 보여주고 요청하지 않고 재고 칸에 커서를 둔다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    expect(stockInput()).toHaveAttribute("max", "1000000");
    expect(stockInput()).not.toHaveAttribute("aria-invalid");
    fireEvent.change(stockInput(), { target: { value: "1000001" } });
    expect(messages()).toEqual([TOO_MANY]);
    expect(stockInput()).toHaveAttribute("aria-invalid", "true");
    expect(stockInput()).toHaveAccessibleDescription(TOO_MANY);
    // The native max check would stop a click first, so submit the form directly to reach the guard.
    fireEvent.submit(form());
    expect(fetch).not.toHaveBeenCalled();
    expect(stockInput()).toHaveFocus();
  });

  it("성공: 재고 1,000,000개는 통과하고, 고치면 오류가 사라진다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    fireEvent.change(stockInput(), { target: { value: "1000001" } });
    expect(messages()).toEqual([TOO_MANY]);
    fireEvent.change(stockInput(), { target: { value: "1000000" } });
    expect(messages()).toEqual([]);
    expect(stockInput()).not.toHaveAttribute("aria-invalid");
    expect(stockInput()).not.toHaveAttribute("aria-describedby");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
    expect(sentBody().stock).toBe(1000000);
  });

  it("성공: 옵션을 켜면 단일 재고 칸의 오류는 제출을 막지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillRequired();
    fireEvent.change(stockInput(), { target: { value: "1000001" } });
    await userEvent.click(screen.getByLabelText("옵션 사용"));
    await userEvent.type(screen.getByLabelText("옵션 1 색상 이름"), "화이트");
    await userEvent.type(screen.getByLabelText("옵션 1 사이즈"), "M");
    await userEvent.type(screen.getByLabelText("옵션 1 가격"), "1000");
    await userEvent.type(screen.getByLabelText("옵션 1 재고"), "4");
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().stock).toBe(4);
  });
});

describe("AdminProductForm 옵션", () => {
  async function fillBase() {
    await userEvent.type(screen.getByLabelText("상품명"), "린넨 셔츠");
    await userEvent.type(screen.getByLabelText("상품 설명"), "시원한 셔츠");
    await userEvent.type(screen.getByLabelText("이미지 주소 1"), "https://example.com/a.jpg");
  }
  const type = (label: string, value: string) => userEvent.type(screen.getByLabelText(label), value);
  async function fillRow(n: number, v: { color?: string; size?: string; price?: string; original?: string; stock?: string }) {
    if (v.color) await type(`옵션 ${n} 색상 이름`, v.color);
    if (v.size) await type(`옵션 ${n} 사이즈`, v.size);
    if (v.price) await type(`옵션 ${n} 가격`, v.price);
    if (v.original) await type(`옵션 ${n} 정가 (선택)`, v.original);
    if (v.stock) await type(`옵션 ${n} 재고`, v.stock);
  }
  const optionsToggle = () => screen.getByLabelText("옵션 사용");
  const addRow = () => userEvent.click(screen.getByRole("button", { name: "옵션 추가" }));
  const note = () => screen.getByText(/개 옵션 ·/).textContent;

  it("성공: 옵션 사용을 켜면 단일 가격·정가·재고 칸이 사라지고 옵션 행이 나온다", async () => {
    render(<AdminProductForm />);
    expect(optionsToggle()).not.toBeChecked();
    expect(screen.queryByLabelText("색상 옵션 사용")).not.toBeInTheDocument();
    await userEvent.click(optionsToggle());
    expect(screen.queryByLabelText("가격")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("정가 (선택)")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("재고 수량")).not.toBeInTheDocument();
    expect(screen.getByLabelText("색상 옵션 사용")).toBeChecked();
    expect(screen.getByLabelText("사이즈 옵션 사용")).toBeChecked();
    for (const label of ["색상 이름", "색상 선택", "사이즈", "가격", "정가 (선택)", "재고"]) {
      expect(screen.getByLabelText(`옵션 1 ${label}`)).toBeInTheDocument();
    }
    await userEvent.click(optionsToggle());
    expect(screen.getByLabelText("가격")).toBeInTheDocument();
    expect(screen.getByLabelText("재고 수량")).toBeInTheDocument();
    expect(screen.queryByLabelText("옵션 1 가격")).not.toBeInTheDocument();
  });

  it("성공: 색상과 사이즈 옵션을 보내며 최저가와 재고 합계도 최상위에 함께 보낸다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "25000", original: "30000", stock: "3" });
    fireEvent.change(screen.getByLabelText("옵션 1 색상 선택"), { target: { value: "#ffffff" } });
    await addRow();
    await fillRow(2, { color: " 블랙 ", size: "M", price: "23000", stock: "10" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/admin/products"));
    expect(sentBody()).toEqual({
      name: "린넨 셔츠",
      description: "시원한 셔츠",
      price: 23000,
      imageUrl: "https://example.com/a.jpg",
      imageUrls: ["https://example.com/a.jpg"],
      stock: 13,
      category: "",
      options: [
        { colorName: "화이트", colorHex: "#ffffff", sizeLabel: "M", price: 25000, originalPrice: 30000, stock: 3 },
        { colorName: "블랙", sizeLabel: "M", price: 23000, stock: 10 },
      ],
    });
  });

  it("성공: 사이즈만 쓰면 색상 칸이 없고 요청에도 색상 필드가 없다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await userEvent.click(screen.getByLabelText("색상 옵션 사용"));
    expect(screen.queryByLabelText("옵션 1 색상 이름")).not.toBeInTheDocument();
    await fillRow(1, { size: "FREE", price: "9000", stock: "4" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().options).toEqual([{ sizeLabel: "FREE", price: 9000, stock: 4 }]);
  });

  it("성공: 색상만 쓰면 사이즈 칸이 없고 색 선택을 지우면 색 코드를 보내지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await userEvent.click(screen.getByLabelText("사이즈 옵션 사용"));
    expect(screen.queryByLabelText("옵션 1 사이즈")).not.toBeInTheDocument();
    await fillRow(1, { color: "민트", price: "9000", stock: "4" });
    expect(screen.getByText("색 선택 안 함 (글자 칩)")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("옵션 1 색상 선택"), { target: { value: "#00ff00" } });
    expect(screen.getByText("#00ff00")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "옵션 1 색상 지우기" }));
    expect(screen.getByText("색 선택 안 함 (글자 칩)")).toBeInTheDocument();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().options).toEqual([{ colorName: "민트", price: 9000, stock: 4 }]);
  });

  it("성공: 옵션을 켰다 꺼도 옛 방식 요청에는 options가 없다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await userEvent.click(optionsToggle());
    await userEvent.click(optionsToggle());
    await fillRequired();
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody()).toEqual({
      name: "린넨 셔츠",
      description: "시원한 셔츠",
      price: 8000,
      imageUrl: "https://example.com/a.jpg",
      imageUrls: ["https://example.com/a.jpg"],
      stock: 5,
      category: "",
    });
  });

  it("실패: 색상과 사이즈를 둘 다 끄면 안내를 보이고 요청하지 않으며 색상 체크박스로 포커스를 옮긴다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await userEvent.click(screen.getByLabelText("색상 옵션 사용"));
    await userEvent.click(screen.getByLabelText("사이즈 옵션 사용"));
    expect(screen.getByRole("alert")).toHaveTextContent("색상 또는 사이즈 중 하나는 사용해야 해요.");
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByLabelText("색상 옵션 사용")).toHaveFocus();
  });

  it("실패: 비어 있는 옵션 행은 제출 전에는 조용하고, 제출하면 인라인 오류와 첫 칸 포커스가 생기며 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getAllByRole("alert").map((a) => a.textContent)).toEqual([
      "색상 이름을 입력해 주세요.",
      "사이즈를 입력해 주세요.",
      "가격은 0 이상의 정수로 입력해 주세요.",
      "재고는 0 이상의 정수로 입력해 주세요.",
    ]);
    const colorInput = screen.getByLabelText("옵션 1 색상 이름");
    expect(colorInput).toHaveFocus();
    expect(colorInput).toHaveAttribute("aria-invalid", "true");
    expect(colorInput).toHaveAccessibleDescription("색상 이름을 입력해 주세요.");
  });

  it("실패: 오류가 있는 첫 칸으로 포커스를 옮기고, 고치면 오류가 사라진다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "25000", stock: "3" });
    await addRow();
    await fillRow(2, { color: "블랙", size: "M", price: "25000", original: "20000", stock: "3" });
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("정가는 판매가보다 커야 해요.");
    expect(screen.getByLabelText("옵션 2 정가 (선택)")).toHaveFocus();
    await userEvent.clear(screen.getByLabelText("옵션 2 정가 (선택)"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("실패: 같은 색상·사이즈 조합은 공백과 대소문자 차이가 있어도 중복 오류가 난다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "White", size: "m", price: "1000", stock: "1" });
    await addRow();
    await fillRow(2, { color: " white ", size: "M", price: "1000", stock: "1" });
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("같은 색상과 사이즈 조합의 옵션이 이미 있어요.");
    expect(screen.getByLabelText("옵션 2 색상 이름")).toHaveFocus();
  });

  it("실패: 31자 이름과 쓸 수 없는 문자는 막는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "가".repeat(31), size: "M", price: "1000", stock: "1" });
    await submit();
    expect(screen.getByRole("alert")).toHaveTextContent("30자 이하");
    expect(fetch).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("옵션 1 색상 이름"), { target: { value: "화이트​" } });
    expect(screen.getByRole("alert")).toHaveTextContent("옵션 이름에 쓸 수 없는 문자가 있어요.");
  });

  it("성공: 행을 추가하고 삭제하며, 행이 하나뿐이면 삭제 버튼이 없다", async () => {
    render(<AdminProductForm />);
    await userEvent.click(optionsToggle());
    expect(screen.queryByRole("button", { name: /옵션 1 삭제/ })).not.toBeInTheDocument();
    await addRow();
    expect(screen.getByLabelText("옵션 2 색상 이름")).toHaveFocus();
    await type("옵션 2 색상 이름", "블랙");
    await userEvent.click(screen.getByRole("button", { name: "옵션 1 삭제" }));
    // The remaining row keeps its own text and is renumbered.
    expect(screen.queryByLabelText("옵션 2 색상 이름")).not.toBeInTheDocument();
    expect(screen.getByLabelText("옵션 1 색상 이름")).toHaveValue("블랙");
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
  });

  it("실패: 옵션은 50개까지만 추가할 수 있다", async () => {
    render(<AdminProductForm />);
    await userEvent.click(optionsToggle());
    // fireEvent: 49 userEvent clicks (each re-rendering up to 50 rows) would time out
    for (let i = 1; i < 50; i++) fireEvent.click(screen.getByText("옵션 추가"));
    expect(screen.getByLabelText("옵션 50 색상 이름")).toBeInTheDocument();
    expect(screen.getByText("옵션 추가")).toBeDisabled();
    expect(screen.getByText("옵션은 최대 50개까지 등록할 수 있어요.")).toBeInTheDocument();
  }, 30_000);

  it("성공: 옵션 개수·최저가·재고 합계를 바로 보여준다", async () => {
    render(<AdminProductForm />);
    await userEvent.click(optionsToggle());
    expect(note()).toBe("1개 옵션 · 최저가 - · 재고 합계 0개");
    await fillRow(1, { price: "30000", stock: "2" });
    await addRow();
    await fillRow(2, { price: "20000", stock: "3" });
    expect(note()).toBe("2개 옵션 · 최저가 20,000원 · 재고 합계 5개");
  });

  it("성공: 이미지 여러 장과 함께 옵션을 보내도 imageUrls가 유지된다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(screen.getByRole("button", { name: "이미지 추가" }));
    await type("이미지 주소 2", "https://example.com/b.jpg");
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "1" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({
      imageUrl: "https://example.com/a.jpg",
      imageUrls: ["https://example.com/a.jpg", "https://example.com/b.jpg"],
    });
  });

  it("실패: 서버가 거절하면 role=alert로 메시지를 보이고 다시 제출할 수 있다", async () => {
    mockFetchOnce(400, { error: "같은 색상과 사이즈 조합의 옵션이 중복되어 있어요." });
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "1" });
    await submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("중복되어 있어요");
    expect(screen.getByRole("button", { name: "등록하기" })).toBeEnabled();
    expect(push).not.toHaveBeenCalled();
  });

  it("성공: 옵션 재고 합계가 1,000,000을 넘으면 최상위 재고는 1,000,000으로 줄여 보낸다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "600000" });
    await addRow();
    await fillRow(2, { color: "블랙", size: "M", price: "1000", stock: "600000" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    const body = sentBody();
    expect(body.stock).toBe(1000000);
    expect(body.options.map((o: { stock: number }) => o.stock)).toEqual([600000, 600000]);
  });

  it("실패: 옵션 한 줄의 재고가 1,000,000을 넘으면 그 칸에 오류를 보이고 요청하지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "1000001" });
    await submit();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("재고는 1,000,000개 이하로 입력해 주세요.");
    expect(screen.getByLabelText("옵션 1 재고")).toHaveFocus();
  });

  it("성공: 옵션을 켜면 옛 가격 입력의 오류는 제출을 막지 않는다", async () => {
    mockFetchOnce(201, {});
    render(<AdminProductForm />);
    await fillBase();
    fireEvent.change(screen.getByLabelText("가격"), { target: { value: "99999999999" } });
    await userEvent.click(optionsToggle());
    await fillRow(1, { color: "화이트", size: "M", price: "1000", stock: "1" });
    await submit();
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(sentBody().price).toBe(1000);
  });
});
