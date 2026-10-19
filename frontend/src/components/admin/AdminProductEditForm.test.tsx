import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminProductEditForm } from "./AdminProductEditForm";
import { MAX_PRICE } from "@/lib/price";
import type { ProductDetail } from "@/lib/api";

const mocks = vi.hoisted(() => ({ push: vi.fn(), refresh: vi.fn(), onReload: vi.fn(), logout: vi.fn() }));
vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({ logout: mocks.logout }),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mocks.push, replace: vi.fn(), refresh: mocks.refresh }),
}));

const A = "https://example.com/a.jpg";
const B = "https://example.com/b.jpg";
const C = "https://example.com/c.jpg";

const plain = (extra: Partial<ProductDetail> = {}): ProductDetail => ({
  id: "p1",
  name: "린넨 셔츠",
  description: "시원한 셔츠",
  price: 8000,
  originalPrice: 10000,
  stock: 10,
  category: "상의",
  hashtags: ["여름", "린넨"],
  imageUrl: A,
  images: [A, B],
  ...extra,
});

const withOptions = (extra: Partial<ProductDetail> = {}): ProductDetail =>
  plain({
    price: 9000,
    originalPrice: null,
    stock: 0,
    options: [
      { id: "o1", colorName: "화이트", colorHex: "#ffffff", sizeLabel: "M", price: 9000, originalPrice: 12000, stock: 5 },
      { id: "o2", colorName: "블랙", colorHex: null, sizeLabel: "L", price: 9500, originalPrice: null, stock: 0 },
    ],
    ...extra,
  });

const reply = (status: number, body: unknown = {}) => ({ ok: status >= 200 && status < 300, status, json: async () => body });

function mockFetch(status = 200, body: unknown = { id: "p1" }) {
  const fetchMock = vi.fn().mockResolvedValue(reply(status, body));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

// A PUT that stays pending until `finish` is called.
function pendingFetch() {
  let finish: (r: unknown) => void = () => {};
  const fetchMock = vi.fn(() => new Promise((resolve) => (finish = resolve)));
  vi.stubGlobal("fetch", fetchMock);
  return { fetchMock, finish: (status = 200, body: unknown = { id: "p1" }) => finish(reply(status, body)) };
}

const renderForm = (product: ProductDetail = plain()) =>
  render(<AdminProductEditForm product={product} token="fake-token" onReload={mocks.onReload} />);

const sentBody = () => JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));
const saveButton = () => screen.getByRole("button", { name: /저장/ });
const save = () => userEvent.click(saveButton());
const set = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const input = (label: string) => screen.getByLabelText(label);
const imageInputs = () => screen.getAllByLabelText(/^이미지 주소 \d+$/) as HTMLInputElement[];

beforeEach(() => {
  vi.unstubAllGlobals();
  mocks.push.mockClear();
  mocks.refresh.mockClear();
  mocks.onReload.mockClear();
  mocks.logout.mockClear();
});

// A rejected save: nothing is sent, the message is attached to the field, and the cursor lands on it.
async function expectBlocked(label: string, message: string | RegExp) {
  await save();
  expect(fetch).not.toHaveBeenCalled();
  const field = input(label);
  expect(field).toHaveAttribute("aria-invalid", "true");
  expect(field).toHaveAccessibleDescription(message);
  expect(field).toHaveFocus();
  expect(screen.getByRole("alert")).toHaveTextContent(message);
}

describe("AdminProductEditForm 초기값", () => {
  it("성공: 옵션 없는 상품은 불러온 값으로 채워지고 옵션 표는 없다", () => {
    renderForm();
    expect(input("상품명")).toHaveValue("린넨 셔츠");
    expect(input("상품 설명")).toHaveValue("시원한 셔츠");
    expect(input("카테고리")).toHaveValue("상의");
    expect(input("해시태그 (최대 3개, 공백으로 구분)")).toHaveValue("여름 린넨");
    expect(imageInputs().map((i) => i.value)).toEqual([A, B]);
    expect(input("가격")).toHaveValue(8000);
    expect(input("정가 (선택)")).toHaveValue(10000);
    expect(input("재고 수량")).toHaveValue(10);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("성공: images가 없으면 imageUrl 한 줄, 정가·카테고리·해시태그가 비어 있으면 빈 칸이다", () => {
    renderForm(plain({ images: undefined, originalPrice: null, category: null, hashtags: undefined }));
    expect(imageInputs().map((i) => i.value)).toEqual([A]);
    expect(input("정가 (선택)")).toHaveValue(null);
    expect(input("카테고리")).toHaveValue("");
    expect(input("해시태그 (최대 3개, 공백으로 구분)")).toHaveValue("");
    // a single row cannot be removed
    expect(screen.queryByRole("button", { name: /삭제/ })).not.toBeInTheDocument();
  });

  it("성공: images가 빈 배열이어도 imageUrl로 채운다", () => {
    renderForm(plain({ images: [] }));
    expect(imageInputs().map((i) => i.value)).toEqual([A]);
  });

  it("성공: 목록에 없는 카테고리는 추가 선택지로 남아 선택돼 있다", () => {
    renderForm(plain({ category: "예전카테고리" }));
    expect(input("카테고리")).toHaveValue("예전카테고리");
    expect(screen.getByRole("option", { name: "예전카테고리" })).toBeInTheDocument();
  });

  it("성공: 목록에 있는 카테고리는 추가 선택지를 만들지 않는다", () => {
    renderForm();
    expect(screen.getAllByRole("option", { name: "상의" })).toHaveLength(1);
  });

  it("성공: 옵션 상품은 옵션 표만 보이고 최상위 가격·재고 칸은 없다", () => {
    renderForm(withOptions());
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByRole("rowheader", { name: "화이트 / M" })).toBeInTheDocument();
    expect(screen.getByRole("rowheader", { name: "블랙 / L" })).toBeInTheDocument();
    expect(input("화이트 / M 가격")).toHaveValue(9000);
    expect(input("화이트 / M 정가 (선택)")).toHaveValue(12000);
    expect(input("화이트 / M 재고")).toHaveValue(5);
    expect(input("블랙 / L 가격")).toHaveValue(9500);
    expect(input("블랙 / L 정가 (선택)")).toHaveValue(null);
    expect(input("블랙 / L 재고")).toHaveValue(0);
    expect(screen.queryByLabelText("가격")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("재고 수량")).not.toBeInTheDocument();
  });
});

describe("AdminProductEditForm 요청 본문", () => {
  it("성공: 옵션 없는 상품은 PUT으로 토큰과 함께 보내고 expectedStock은 불러온 재고, options 키는 없다", async () => {
    const fetchMock = mockFetch();
    renderForm();
    set("재고 수량", "8");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0];
    expect(new URL(String(url)).pathname).toBe("/api/admin/products/p1");
    expect(init.method).toBe("PUT");
    expect(init.headers).toMatchObject({ Authorization: "Bearer fake-token" });
    const body = sentBody();
    expect(body).toEqual({
      name: "린넨 셔츠",
      description: "시원한 셔츠",
      category: "상의",
      hashtags: ["여름", "린넨"],
      imageUrls: [A, B],
      price: 8000,
      originalPrice: 10000,
      stock: 8,
      expectedStock: 10,
    });
    expect(body).not.toHaveProperty("options");
  });

  it("성공: 재고를 안 바꾸면 stock과 expectedStock이 같고, 정가를 비우면 null이다", async () => {
    mockFetch();
    renderForm();
    set("정가 (선택)", "");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({ originalPrice: null, stock: 10, expectedStock: 10 });
  });

  it("성공: 가격·정가·재고는 문자열이 아닌 숫자로 보낸다", async () => {
    mockFetch();
    renderForm();
    set("가격", "9000");
    set("정가 (선택)", "12000");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({ price: 9000, originalPrice: 12000, stock: 10 });
  });

  it("성공: 옵션 상품은 모든 옵션을 options로만 보내고 최상위 가격·재고 필드는 없다", async () => {
    mockFetch();
    renderForm(withOptions());
    set("화이트 / M 가격", "8800");
    set("화이트 / M 정가 (선택)", "");
    set("블랙 / L 재고", "4");
    set("블랙 / L 정가 (선택)", "11000");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    const body = sentBody();
    expect(body.options).toEqual([
      { id: "o1", price: 8800, originalPrice: null, stock: 5, expectedStock: 5 },
      { id: "o2", price: 9500, originalPrice: 11000, stock: 4, expectedStock: 0 },
    ]);
    for (const key of ["price", "originalPrice", "stock", "expectedStock"]) expect(body).not.toHaveProperty(key);
    expect(Object.keys(body).sort()).toEqual(["category", "description", "hashtags", "imageUrls", "name", "options"]);
  });

  it("성공: 카테고리를 비우면 null, 처음부터 없어도 null이다", async () => {
    mockFetch();
    renderForm();
    await userEvent.selectOptions(input("카테고리"), "");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().category).toBeNull();
    cleanup();

    vi.unstubAllGlobals();
    mockFetch();
    renderForm(plain({ category: null }));
    await save();
    await waitFor(() => expect(vi.mocked(fetch).mock.calls).toHaveLength(1));
    expect(sentBody().category).toBeNull();
  });

  it("성공: 다른 카테고리를 고르면 그 값을 보낸다", async () => {
    mockFetch();
    renderForm();
    await userEvent.selectOptions(input("카테고리"), "신발");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().category).toBe("신발");
  });

  it("성공: 해시태그는 # 없는 배열로 정리해 보내고, 비우면 빈 배열이다", async () => {
    mockFetch();
    renderForm();
    set("해시태그 (최대 3개, 공백으로 구분)", "#여름  린넨 #린넨 Daily_Look");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().hashtags).toEqual(["여름", "린넨", "Daily_Look"]);
    cleanup();

    vi.unstubAllGlobals();
    mockFetch();
    renderForm();
    set("해시태그 (최대 3개, 공백으로 구분)", "");
    await save();
    await waitFor(() => expect(vi.mocked(fetch).mock.calls).toHaveLength(1));
    expect(sentBody().hashtags).toEqual([]);
  });
});

describe("AdminProductEditForm 이미지 줄", () => {
  it("성공: 추가한 줄은 순서대로 보내고 앞뒤 공백은 잘라서 보낸다", async () => {
    mockFetch();
    renderForm();
    await userEvent.click(screen.getByRole("button", { name: "이미지 추가" }));
    expect(imageInputs()).toHaveLength(3);
    expect(imageInputs()[2]).toHaveFocus();
    fireEvent.change(imageInputs()[0], { target: { value: `  ${A}  ` } });
    fireEvent.change(imageInputs()[2], { target: { value: `\t${C} ` } });
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual([A, B, C]);
  });

  it("성공: 비어 있는 추가 줄은 보내지 않는다", async () => {
    mockFetch();
    renderForm();
    await userEvent.click(screen.getByRole("button", { name: "이미지 추가" }));
    await userEvent.click(screen.getByRole("button", { name: "이미지 추가" }));
    fireEvent.change(imageInputs()[3], { target: { value: "   " } });
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual([A, B]);
  });

  it("성공: 10장에 닿으면 추가 버튼이 꺼지고 안내가 보인다 (9장까지는 켜져 있다)", async () => {
    renderForm();
    const add = screen.getByRole("button", { name: "이미지 추가" });
    for (let i = 0; i < 7; i++) await userEvent.click(add);
    expect(imageInputs()).toHaveLength(9);
    expect(add).toBeEnabled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    await userEvent.click(add);
    expect(imageInputs()).toHaveLength(10);
    expect(add).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("이미지는 최대 10장까지 등록할 수 있어요.");
    await userEvent.click(add);
    expect(imageInputs()).toHaveLength(10);
  });

  it("성공: 처음부터 10장인 상품은 추가 버튼이 꺼져 있고 10장 모두 보낸다", async () => {
    mockFetch();
    const urls = Array.from({ length: 10 }, (_, i) => `https://example.com/${i}.jpg`);
    renderForm(plain({ images: urls }));
    expect(screen.getByRole("button", { name: "이미지 추가" })).toBeDisabled();
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual(urls);
  });

  it("성공: 가운데 줄을 지워도 나머지 줄의 입력값이 그대로 남고 그 순서로 보낸다", async () => {
    mockFetch();
    renderForm(plain({ images: [A, B, C] }));
    await userEvent.click(screen.getByRole("button", { name: "이미지 주소 2 삭제" }));
    expect(imageInputs().map((i) => i.value)).toEqual([A, C]);
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual([A, C]);
  });

  it("성공: 첫 줄을 지우면 다음 줄이 대표 이미지가 된다", async () => {
    mockFetch();
    renderForm();
    await userEvent.click(screen.getByRole("button", { name: "이미지 주소 1 삭제" }));
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual([B]);
  });

  it("성공: 줄이 2개 이상일 때만 삭제 버튼이 있고, 하나 남으면 사라진다", async () => {
    renderForm();
    expect(screen.getAllByRole("button", { name: /^이미지 주소 \d 삭제$/ })).toHaveLength(2);
    await userEvent.click(screen.getByRole("button", { name: "이미지 주소 2 삭제" }));
    expect(screen.queryByRole("button", { name: /삭제$/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /이동$/ })).not.toBeInTheDocument();
  });
});

describe("AdminProductEditForm 검증 (요청을 보내지 않고 첫 잘못된 칸으로 간다)", () => {
  it("성공: 저장을 누르기 전에는 잘못된 값이어도 오류가 보이지 않고, 누르면 나타나며 고치면 사라진다", async () => {
    mockFetch();
    renderForm();
    set("상품명", "");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(input("상품명")).not.toHaveAttribute("aria-invalid");
    expect(input("상품명")).not.toHaveAttribute("aria-describedby");
    await save();
    expect(screen.getByRole("alert")).toHaveTextContent("상품명을 입력해 주세요.");
    set("상품명", "새 이름");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(input("상품명")).not.toHaveAttribute("aria-invalid");
  });

  it("실패: 상품명이 비어 있거나 공백뿐이면 오류", async () => {
    mockFetch();
    renderForm();
    set("상품명", "   ");
    await expectBlocked("상품명", "상품명을 입력해 주세요.");
  });

  it("실패/성공: 상품명 201자는 오류, 200자는 통과", async () => {
    mockFetch();
    renderForm();
    set("상품명", "가".repeat(201));
    await expectBlocked("상품명", "상품명은 200자 이하로 입력해 주세요.");
    set("상품명", "가".repeat(200));
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().name).toHaveLength(200);
  });

  it("실패: 상품 설명이 비어 있으면 오류", async () => {
    mockFetch();
    renderForm();
    set("상품 설명", "");
    await expectBlocked("상품 설명", "상품 설명을 입력해 주세요.");
  });

  it("실패/성공: 상품 설명 2001자는 오류, 2000자는 통과", async () => {
    mockFetch();
    renderForm();
    set("상품 설명", "가".repeat(2001));
    await expectBlocked("상품 설명", "상품 설명은 2000자 이하로 입력해 주세요.");
    set("상품 설명", "가".repeat(2000));
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().description).toHaveLength(2000);
  });

  it("실패: 해시태그가 4개면 오류, 3개는 통과", async () => {
    mockFetch();
    renderForm();
    set("해시태그 (최대 3개, 공백으로 구분)", "a b c d");
    await expectBlocked("해시태그 (최대 3개, 공백으로 구분)", "해시태그는 최대 3개까지 입력할 수 있어요.");
    set("해시태그 (최대 3개, 공백으로 구분)", "a b c");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().hashtags).toEqual(["a", "b", "c"]);
  });

  it("실패: 해시태그에 쓸 수 없는 문자가 있으면 오류", async () => {
    mockFetch();
    renderForm();
    set("해시태그 (최대 3개, 공백으로 구분)", "good bad!");
    await expectBlocked("해시태그 (최대 3개, 공백으로 구분)", "해시태그는 글자, 숫자, 밑줄(_)만 쓸 수 있어요.");
  });

  it("실패: 첫 번째 이미지 주소가 비어 있으면 오류 (공백뿐이어도)", async () => {
    mockFetch();
    renderForm();
    fireEvent.change(imageInputs()[0], { target: { value: "   " } });
    await expectBlocked("이미지 주소 1", "첫 번째 이미지 주소를 입력해 주세요.");
  });

  it("실패: 같은 이미지 주소를 또 쓰면 뒤쪽 줄에 오류 (공백을 잘라 비교)", async () => {
    mockFetch();
    renderForm();
    fireEvent.change(imageInputs()[1], { target: { value: ` ${A} ` } });
    await expectBlocked("이미지 주소 2", "이미 입력한 이미지 주소예요.");
    expect(input("이미지 주소 1")).not.toHaveAttribute("aria-invalid");
  });

  it("실패: 가격이 비었거나 음수·소수·최댓값 초과이면 오류, 0과 최댓값은 통과", async () => {
    mockFetch();
    renderForm();
    for (const bad of ["", "-1", "1.5"]) {
      set("가격", bad);
      set("정가 (선택)", "");
      await expectBlocked("가격", "가격은 0 이상의 정수로 입력해 주세요.");
    }
    set("가격", String(MAX_PRICE + 1));
    await expectBlocked("가격", /2,147,483,647원 이하로 입력해 주세요\./);
    expect(fetch).not.toHaveBeenCalled();

    set("가격", String(MAX_PRICE));
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().price).toBe(MAX_PRICE);
  });

  it("성공: 가격 0도 보낼 수 있다", async () => {
    mockFetch();
    renderForm(plain({ originalPrice: null }));
    set("가격", "0");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().price).toBe(0);
  });

  it("실패: 정가가 판매가 이하이면 오류, 판매가 + 1이면 통과", async () => {
    mockFetch();
    renderForm();
    set("가격", "9000");
    set("정가 (선택)", "9000");
    await expectBlocked("정가 (선택)", "정가는 판매가보다 커야 해요.");
    set("정가 (선택)", "8999");
    await save();
    expect(fetch).not.toHaveBeenCalled();
    expect(input("정가 (선택)")).toHaveAttribute("aria-invalid", "true");
    set("정가 (선택)", "9001");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({ price: 9000, originalPrice: 9001 });
  });

  it("실패: 정가가 소수이거나 최댓값을 넘으면 오류", async () => {
    mockFetch();
    renderForm();
    set("정가 (선택)", "10000.5");
    await expectBlocked("정가 (선택)", "정가는 정수로 입력해 주세요.");
    set("정가 (선택)", String(MAX_PRICE + 1));
    await save();
    expect(input("정가 (선택)")).toHaveAccessibleDescription("정가는 2,147,483,647원 이하로 입력해 주세요.");
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 재고가 비었거나 음수·소수이면 오류, 0은 통과", async () => {
    mockFetch();
    renderForm();
    for (const bad of ["", "-1", "2.5"]) {
      set("재고 수량", bad);
      await expectBlocked("재고 수량", "재고는 0 이상의 정수로 입력해 주세요.");
    }
    set("재고 수량", "0");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({ stock: 0, expectedStock: 10 });
  });

  it("성공: 여러 칸이 잘못되면 화면 순서상 첫 칸(상품명 -> 설명 -> ... -> 가격)에 커서가 간다", async () => {
    mockFetch();
    renderForm();
    set("상품 설명", "");
    set("가격", "");
    await save();
    expect(input("상품 설명")).toHaveFocus();
    set("상품 설명", "설명");
    await save();
    expect(input("가격")).toHaveFocus();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("실패: 옵션 행의 잘못된 값은 그 행 칸에 오류를 붙이고 커서를 보낸다", async () => {
    mockFetch();
    renderForm(withOptions());
    set("블랙 / L 가격", "-3");
    await expectBlocked("블랙 / L 가격", "가격은 0 이상의 정수로 입력해 주세요.");
    expect(input("화이트 / M 가격")).not.toHaveAttribute("aria-invalid");
  });

  it("실패: 옵션 행의 정가·재고도 검사하고 첫 잘못된 행으로 간다", async () => {
    mockFetch();
    renderForm(withOptions());
    set("블랙 / L 재고", "1.5");
    set("화이트 / M 정가 (선택)", "9000");
    await save();
    expect(fetch).not.toHaveBeenCalled();
    expect(input("화이트 / M 정가 (선택)")).toHaveFocus();
    expect(input("화이트 / M 정가 (선택)")).toHaveAccessibleDescription("정가는 판매가보다 커야 해요.");
    expect(input("블랙 / L 재고")).toHaveAccessibleDescription("재고는 0 이상의 정수로 입력해 주세요.");
  });

  it("성공: 옵션 행 값이 모두 맞고 재고 0이어도 저장된다", async () => {
    mockFetch();
    renderForm(withOptions());
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().options).toHaveLength(2);
  });
});

describe("AdminProductEditForm 저장", () => {
  it("성공: 저장 중에는 버튼이 비활성이고 문구가 바뀌며, 끝나면 안내를 보이고 목록으로 이동·새로고침한다", async () => {
    const { finish } = pendingFetch();
    renderForm();
    expect(saveButton()).toHaveTextContent("저장하기");
    await save();
    expect(saveButton()).toHaveTextContent("저장 중...");
    expect(saveButton()).toBeDisabled();
    expect(mocks.push).not.toHaveBeenCalled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    await act(async () => finish());
    expect(screen.getByRole("status")).toHaveTextContent("저장했어요. 상품 목록으로 돌아가요.");
    expect(mocks.push).toHaveBeenCalledWith("/admin/products");
    expect(mocks.refresh).toHaveBeenCalledTimes(1);
    // stays locked until the route changes: no second PUT
    expect(saveButton()).toBeDisabled();
    await act(async () => {
      fireEvent.submit(saveButton().closest("form")!);
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("실패: 같은 순간에 두 번 눌러도 PUT은 한 번만 간다", async () => {
    const { finish } = pendingFetch();
    renderForm();
    await act(async () => {
      fireEvent.click(saveButton());
      fireEvent.click(saveButton());
    });
    expect(fetch).toHaveBeenCalledTimes(1);
    await act(async () => finish());
  });

  it("실패: 서버가 400을 주면 서버 문구를 alert로 보이고 버튼이 다시 켜지며 다시 저장할 수 있다", async () => {
    mockFetch(400, { error: "옵션이 빠졌습니다." });
    renderForm();
    await save();
    expect(await screen.findByRole("alert")).toHaveTextContent("옵션이 빠졌습니다.");
    expect(saveButton()).toBeEnabled();
    expect(saveButton()).toHaveTextContent("저장하기");
    // a 400 may mean the product changed (e.g. its options): reload is offered, saving stays possible
    expect(screen.getByRole("button", { name: "새로고침" })).toBeInTheDocument();
    expect(mocks.logout).not.toHaveBeenCalled();
    expect(mocks.push).not.toHaveBeenCalled();
    expect(mocks.refresh).not.toHaveBeenCalled();
    expect(screen.queryByText("저장했어요. 상품 목록으로 돌아가요.")).not.toBeInTheDocument();

    mockFetch();
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalledWith("/admin/products"));
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "새로고침" })).not.toBeInTheDocument();
  });

  it("실패: 400의 새로고침 버튼은 onReload를 부른다", async () => {
    mockFetch(400, { error: "옵션이 빠졌습니다." });
    renderForm();
    await save();
    await userEvent.click(await screen.findByRole("button", { name: "새로고침" }));
    expect(mocks.onReload).toHaveBeenCalledTimes(1);
  });

  it("실패: 401은 로그인 안내 alert과 logout 한 번이고, 저장 버튼은 켜져 있으며 새로고침 버튼은 없다", async () => {
    mockFetch(401, {});
    renderForm();
    await save();
    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 필요합니다.");
    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(saveButton()).toBeEnabled();
    expect(saveButton()).toHaveTextContent("저장하기");
    expect(screen.queryByRole("button", { name: "새로고침" })).not.toBeInTheDocument();
    expect(mocks.push).not.toHaveBeenCalled();
  });

  it("실패: 403도 로그인 안내와 logout이다", async () => {
    mockFetch(403, {});
    renderForm();
    await save();
    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 필요합니다.");
    expect(mocks.logout).toHaveBeenCalledTimes(1);
  });

  it("성공/실패: 로그인 오류가 아닌 다른 오류에서는 logout을 부르지 않는다", async () => {
    for (const [status, body] of [[400, { error: "x" }], [404, {}], [409, { code: "STOCK_CHANGED" }], [500, {}]] as const) {
      mockFetch(status, body);
      const { unmount } = renderForm();
      await save();
      await screen.findByRole("alert");
      unmount();
    }
    mockFetch();
    renderForm();
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it("실패: 404는 찾을 수 없다는 alert과 새로고침 버튼이고 저장은 막지 않는다", async () => {
    mockFetch(404, {});
    renderForm();
    await save();
    expect(await screen.findByRole("alert")).toHaveTextContent("상품을 찾을 수 없어요.");
    expect(saveButton()).toBeEnabled();
    await userEvent.click(screen.getByRole("button", { name: "새로고침" }));
    expect(mocks.onReload).toHaveBeenCalledTimes(1);
  });

  it("실패: 500은 일반 문구이고 새로고침 버튼 없이 다시 저장할 수 있다", async () => {
    mockFetch(500, {});
    renderForm();
    await save();
    expect(await screen.findByRole("alert")).toHaveTextContent("저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    expect(screen.queryByRole("button", { name: "새로고침" })).not.toBeInTheDocument();
    expect(saveButton()).toBeEnabled();
  });

  it("실패: 409 STOCK_CHANGED는 안내와 새로고침 버튼을 보이고 새로고침 전까지 저장하기가 꺼져 있다", async () => {
    mockFetch(409, { error: "raw", code: "STOCK_CHANGED" });
    renderForm();
    await save();
    expect(await screen.findByRole("alert")).toHaveTextContent("그 사이 재고가 바뀌었어요. 새로고침한 뒤 다시 입력해 주세요.");
    expect(saveButton()).toBeDisabled();
    expect(saveButton()).toHaveTextContent("저장하기");
    expect(mocks.push).not.toHaveBeenCalled();
    // typing does not re-enable it
    set("재고 수량", "7");
    expect(saveButton()).toBeDisabled();
    expect(mocks.onReload).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "새로고침" }));
    expect(mocks.onReload).toHaveBeenCalledTimes(1);
  });

  it("실패: code가 없는 409는 일반 오류라 새로고침 버튼 없이 다시 저장할 수 있다", async () => {
    mockFetch(409, { error: "충돌" });
    renderForm();
    await save();
    expect(await screen.findByRole("alert")).toHaveTextContent("저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    expect(screen.queryByRole("button", { name: "새로고침" })).not.toBeInTheDocument();
    expect(saveButton()).toBeEnabled();
  });

  it("실패: 네트워크 오류와 timeout은 저장 여부를 모른다고 알리고 새로고침 전까지 저장을 막는다", async () => {
    for (const err of [new TypeError("network"), new DOMException("timeout", "TimeoutError")]) {
      mocks.onReload.mockClear();
      vi.stubGlobal("fetch", vi.fn().mockRejectedValue(err));
      const { unmount } = renderForm();
      await save();
      expect(await screen.findByRole("alert")).toHaveTextContent("저장됐는지 확인하지 못했어요. 새로고침해서 확인해 주세요.");
      expect(saveButton()).toBeDisabled();
      expect(saveButton()).toHaveTextContent("저장하기");
      expect(mocks.push).not.toHaveBeenCalled();
      expect(mocks.logout).not.toHaveBeenCalled();
      // typing does not re-enable it
      set("재고 수량", "7");
      expect(saveButton()).toBeDisabled();
      expect(fetch).toHaveBeenCalledTimes(1);
      await userEvent.click(screen.getByRole("button", { name: "새로고침" }));
      expect(mocks.onReload).toHaveBeenCalledTimes(1);
      unmount();
    }
  });

  it("성공: 이전 오류는 다시 저장을 시작하면 지워진다", async () => {
    mockFetch(400, { error: "첫 오류" });
    renderForm();
    await save();
    expect(await screen.findByRole("alert")).toHaveTextContent("첫 오류");
    const { finish } = pendingFetch();
    await save();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await act(async () => finish());
  });
});

describe("AdminProductEditForm 이미지 주소 형식", () => {
  const SCHEME = "이미지 주소는 http:// 또는 https://로 시작해야 해요.";

  it("실패: http(s)로 시작하지 않으면 그 줄에 오류를 붙이고 요청하지 않고 그 줄로 커서를 보낸다", async () => {
    for (const bad of ["ftp://example.com/a.jpg", "example.com/a.jpg", "javascript:alert(1)", "httpx://example.com/a.jpg", "http:/example.com/a.jpg", "httpexample.com", "//example.com/a.jpg", "data:image/png;base64,AAAA"]) {
      mockFetch();
      const { unmount } = renderForm();
      fireEvent.change(imageInputs()[1], { target: { value: bad } });
      await save();
      expect(fetch).not.toHaveBeenCalled();
      expect(input("이미지 주소 2")).toHaveAttribute("aria-invalid", "true");
      expect(input("이미지 주소 2")).toHaveAccessibleDescription(SCHEME);
      expect(input("이미지 주소 2")).toHaveFocus();
      expect(input("이미지 주소 1")).not.toHaveAttribute("aria-invalid");
      unmount();
    }
  });

  it("실패: 대표(첫) 이미지의 잘못된 형식도 막고, 오류가 둘이면 앞쪽 줄로 간다", async () => {
    mockFetch();
    renderForm(plain({ images: [A, B, C] }));
    fireEvent.change(imageInputs()[2], { target: { value: "ftp://x/c.jpg" } });
    fireEvent.change(imageInputs()[1], { target: { value: "ftp://x/b.jpg" } });
    await save();
    expect(input("이미지 주소 2")).toHaveFocus();
    expect(screen.getAllByRole("alert").map((a) => a.textContent)).toEqual([SCHEME, SCHEME]);
    fireEvent.change(imageInputs()[0], { target: { value: "www.example.com" } });
    await save();
    expect(input("이미지 주소 1")).toHaveFocus();
    expect(input("이미지 주소 1")).toHaveAccessibleDescription(SCHEME);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("성공: 오류는 저장을 누르기 전에는 보이지 않고, 주소를 고치면 사라진다", async () => {
    mockFetch();
    renderForm();
    fireEvent.change(imageInputs()[1], { target: { value: "ftp://x/b.jpg" } });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(input("이미지 주소 2")).not.toHaveAttribute("aria-invalid");
    await save();
    expect(screen.getByRole("alert")).toHaveTextContent(SCHEME);
    fireEvent.change(imageInputs()[1], { target: { value: B } });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
  });

  it("성공: 대문자 HTTP://와 Https://, 앞뒤 공백이 있는 주소는 받아들인다", async () => {
    mockFetch();
    renderForm();
    fireEvent.change(imageInputs()[0], { target: { value: " HTTP://EXAMPLE.com/a.jpg " } });
    fireEvent.change(imageInputs()[1], { target: { value: "Https://example.com/b.jpg" } });
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual(["HTTP://EXAMPLE.com/a.jpg", "Https://example.com/b.jpg"]);
  });

  it("성공: 비어 있는 추가 줄은 오류 없이 빠진다 (형식 검사 대상이 아니다)", async () => {
    mockFetch();
    renderForm();
    await userEvent.click(screen.getByRole("button", { name: "이미지 추가" }));
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual([A, B]);
  });

  it("실패: 형식이 틀린 같은 주소가 두 번이면 뒤쪽 줄에는 형식 오류가 중복 오류보다 먼저 나온다", async () => {
    mockFetch();
    renderForm();
    fireEvent.change(imageInputs()[0], { target: { value: "ftp://x/a.jpg" } });
    fireEvent.change(imageInputs()[1], { target: { value: "ftp://x/a.jpg" } });
    await save();
    expect(input("이미지 주소 2")).toHaveAccessibleDescription(SCHEME);
    expect(screen.queryByText("이미 입력한 이미지 주소예요.")).not.toBeInTheDocument();
  });
});

describe("AdminProductEditForm 이미지 순서", () => {
  const move = (n: number, dir: "위로" | "아래로") => screen.getByRole("button", { name: `이미지 ${n} ${dir} 이동` });
  const D = "https://example.com/d.jpg";

  it("성공: 안내 문구가 있고, 줄이 2개 이상일 때만 위로·아래로 버튼이 있다", async () => {
    renderForm();
    expect(screen.getByText("위로·아래로 버튼으로 순서를 바꿀 수 있어요.")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^이미지 \d+ (위로|아래로) 이동$/ })).toHaveLength(4);
    await userEvent.click(screen.getByRole("button", { name: "이미지 주소 2 삭제" }));
    expect(screen.queryByRole("button", { name: /(위로|아래로) 이동$/ })).not.toBeInTheDocument();
  });

  it("성공: 첫 줄은 위로, 마지막 줄은 아래로가 꺼져 있고 나머지는 켜져 있다", () => {
    renderForm(plain({ images: [A, B, C] }));
    expect(move(1, "위로")).toBeDisabled();
    expect(move(1, "아래로")).toBeEnabled();
    expect(move(2, "위로")).toBeEnabled();
    expect(move(2, "아래로")).toBeEnabled();
    expect(move(3, "위로")).toBeEnabled();
    expect(move(3, "아래로")).toBeDisabled();
  });

  it("성공: 위로·아래로는 두 줄만 맞바꾸고, 새 첫 줄이 대표 이미지(imageUrls[0])로 간다", async () => {
    mockFetch();
    renderForm(plain({ images: [A, B, C] }));
    await userEvent.click(move(3, "위로"));
    expect(imageInputs().map((i) => i.value)).toEqual([A, C, B]);
    await userEvent.click(move(1, "아래로"));
    expect(imageInputs().map((i) => i.value)).toEqual([C, A, B]);
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual([C, A, B]);
  });

  it("성공: 옮기기 전에 고친 내용과 옮긴 뒤에 입력한 내용이 사라지지 않는다", async () => {
    mockFetch();
    renderForm(plain({ images: [A, B] }));
    fireEvent.change(imageInputs()[1], { target: { value: `${B}?v=2` } });
    await userEvent.click(move(2, "위로"));
    expect(imageInputs().map((i) => i.value)).toEqual([`${B}?v=2`, A]);
    await userEvent.click(imageInputs()[0]);
    await userEvent.type(imageInputs()[0], "&x=1");
    expect(imageInputs().map((i) => i.value)).toEqual([`${B}?v=2&x=1`, A]);
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual([`${B}?v=2&x=1`, A]);
  });

  it("성공: 옮긴 줄의 같은 방향 버튼에 커서가 남고, 그 버튼이 꺼지면 반대쪽 버튼으로 간다", async () => {
    renderForm(plain({ images: [A, B, C, D] }));
    await userEvent.click(move(3, "위로")); // now row 2: up still enabled
    expect(move(2, "위로")).toHaveFocus();
    await userEvent.click(move(2, "위로")); // now row 1: up disabled -> down
    expect(move(1, "아래로")).toHaveFocus();
    await userEvent.click(move(1, "아래로")); // row 2: down enabled
    expect(move(2, "아래로")).toHaveFocus();
    await userEvent.click(move(2, "아래로")); // row 3
    await userEvent.click(move(3, "아래로")); // row 4: down disabled -> up
    expect(move(4, "위로")).toHaveFocus();
  });

  it("성공: 옮긴 줄의 URL과 오류 표시가 함께 움직인다", async () => {
    mockFetch();
    renderForm(plain({ images: [A, B] }));
    fireEvent.change(imageInputs()[1], { target: { value: "ftp://x/b.jpg" } });
    await save();
    expect(input("이미지 주소 2")).toHaveAccessibleDescription("이미지 주소는 http:// 또는 https://로 시작해야 해요.");
    await userEvent.click(move(2, "위로"));
    expect(input("이미지 주소 1")).toHaveValue("ftp://x/b.jpg");
    expect(input("이미지 주소 1")).toHaveAccessibleDescription("이미지 주소는 http:// 또는 https://로 시작해야 해요.");
    expect(input("이미지 주소 2")).not.toHaveAttribute("aria-invalid");
  });

  it("성공: 줄을 지우면 앞 줄의 입력칸에, 첫 줄을 지우면 새 첫 줄에 커서가 간다", async () => {
    renderForm(plain({ images: [A, B, C] }));
    await userEvent.click(screen.getByRole("button", { name: "이미지 주소 2 삭제" }));
    expect(input("이미지 주소 1")).toHaveFocus();
    await userEvent.click(screen.getByRole("button", { name: "이미지 주소 1 삭제" }));
    expect(imageInputs().map((i) => i.value)).toEqual([C]);
    expect(input("이미지 주소 1")).toHaveFocus();
  });

  it("성공: 줄을 추가하면 새 마지막 줄에 커서가 가고, 바로 입력한 내용이 그대로 남는다", async () => {
    mockFetch();
    renderForm();
    await userEvent.click(screen.getByRole("button", { name: "이미지 추가" }));
    expect(imageInputs()[2]).toHaveFocus();
    await userEvent.keyboard(C);
    expect(imageInputs().map((i) => i.value)).toEqual([A, B, C]);
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().imageUrls).toEqual([A, B, C]);
  });

  it("성공: 같은 순간의 추가 두 번과 두 줄 입력도 모두 반영된다 (직전 상태 기준으로 계산)", async () => {
    renderForm();
    const add = screen.getByRole("button", { name: "이미지 추가" });
    await act(async () => {
      fireEvent.click(add);
      fireEvent.click(add);
    });
    expect(imageInputs()).toHaveLength(4);
    const [first, second] = imageInputs();
    await act(async () => {
      fireEvent.change(first, { target: { value: `${A}?1` } });
      fireEvent.change(second, { target: { value: `${B}?2` } });
    });
    expect(imageInputs().slice(0, 2).map((i) => i.value)).toEqual([`${A}?1`, `${B}?2`]);
  });

  it("성공: 연달아 두 번 눌러도 둘 다 반영된다 (직전 상태 기준으로 계산)", async () => {
    renderForm(plain({ images: [A, B, C] }));
    const up3 = move(3, "위로");
    const up2 = move(2, "위로");
    await act(async () => {
      fireEvent.click(up3);
      fireEvent.click(up2);
    });
    // C moves above B -> [A, C, B]; then B (now last) moves above C -> [A, B, C]. A stale snapshot would give [B, A, C].
    expect(imageInputs().map((i) => i.value)).toEqual([A, B, C]);
  });
});

describe("AdminProductEditForm 숫자 칸과 재고 한도", () => {
  it("성공: 숫자 칸 위에서 마우스 휠을 돌리면 포커스가 빠진다 (값이 바뀌지 않게)", () => {
    renderForm();
    for (const label of ["가격", "정가 (선택)", "재고 수량"]) {
      const field = input(label);
      field.focus();
      expect(field).toHaveFocus();
      fireEvent.wheel(field);
      expect(field).not.toHaveFocus();
    }
  });

  it("성공: 옵션 표의 숫자 칸도 휠에서 포커스가 빠진다", () => {
    renderForm(withOptions());
    for (const label of ["화이트 / M 가격", "화이트 / M 정가 (선택)", "블랙 / L 재고"]) {
      const field = input(label);
      field.focus();
      fireEvent.wheel(field);
      expect(field).not.toHaveFocus();
    }
  });

  it("성공: 숫자가 아닌 칸(상품명)은 휠에서 포커스를 잃지 않는다", () => {
    renderForm();
    input("상품명").focus();
    fireEvent.wheel(input("상품명"));
    expect(input("상품명")).toHaveFocus();
  });

  it("실패/성공: 재고 1,000,001은 오류, 1,000,000은 통과 (expectedStock은 불러온 값 그대로)", async () => {
    mockFetch();
    renderForm();
    set("재고 수량", "1000001");
    await expectBlocked("재고 수량", "재고는 1,000,000개 이하로 입력해 주세요.");
    set("재고 수량", "1000000");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody()).toMatchObject({ stock: 1000000, expectedStock: 10 });
  });

  it("실패/성공: 옵션 재고도 1,000,001은 그 칸의 오류, 1,000,000은 통과", async () => {
    mockFetch();
    renderForm(withOptions());
    set("블랙 / L 재고", "1000001");
    await expectBlocked("블랙 / L 재고", "재고는 1,000,000개 이하로 입력해 주세요.");
    set("블랙 / L 재고", "1000000");
    await save();
    await waitFor(() => expect(mocks.push).toHaveBeenCalled());
    expect(sentBody().options[1]).toMatchObject({ stock: 1000000, expectedStock: 0 });
  });
});
