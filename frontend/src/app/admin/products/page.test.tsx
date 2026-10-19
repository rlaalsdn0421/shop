import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import AdminProductsPage from "./page";

const mocks = vi.hoisted(() => ({
  auth: { session: { token: "fake-token", role: "ADMIN", username: "boss" } as unknown, ready: true },
}));
vi.mock("@/auth/AuthContext", () => ({
  useRequireRole: () => mocks.auth,
}));

const item = (id: string, name: string) => ({ id, name, price: 8000, stock: 3, createdAt: "2026-10-11T03:00:00Z" });
const reply = (status: number, body: unknown = {}) => ({ ok: status >= 200 && status < 300, status, json: async () => body });

beforeEach(() => {
  vi.unstubAllGlobals();
  mocks.auth = { session: { token: "fake-token", role: "ADMIN", username: "boss" }, ready: true };
});

describe("관리자 상품 목록의 수정 링크", () => {
  it("성공: 각 줄에 '수정 - 이름' 링크가 있고 /admin/products/{id}/edit으로 간다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, [item("p1", "린넨 셔츠"), item("p2", "울 코트")])));
    render(<AdminProductsPage />);
    expect(await screen.findByRole("link", { name: "수정 - 린넨 셔츠" })).toHaveAttribute("href", "/admin/products/p1/edit");
    expect(screen.getByRole("link", { name: "수정 - 울 코트" })).toHaveAttribute("href", "/admin/products/p2/edit");
    expect(screen.getAllByRole("link", { name: /^수정 - / })).toHaveLength(2);
  });

  it("성공: id의 특수문자는 경로에서 인코딩된다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, [item("a/b c", "특수")])));
    render(<AdminProductsPage />);
    expect(await screen.findByRole("link", { name: "수정 - 특수" })).toHaveAttribute("href", "/admin/products/a%2Fb%20c/edit");
  });

  it("성공: 같은 이름의 상품이어도 링크는 각자 자기 id로 간다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(200, [item("p1", "같은 이름"), item("p2", "같은 이름")])));
    render(<AdminProductsPage />);
    const links = await screen.findAllByRole("link", { name: "수정 - 같은 이름" });
    expect(links.map((l) => l.getAttribute("href"))).toEqual(["/admin/products/p1/edit", "/admin/products/p2/edit"]);
  });

  it("실패: 목록을 못 불러오면 수정 링크가 없다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(500)));
    render(<AdminProductsPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("상품 목록을 불러오지 못했습니다.");
    expect(screen.queryByRole("link", { name: /^수정 - / })).not.toBeInTheDocument();
  });

  it("실패: 비로그인이면 아무것도 그리지 않고 요청하지 않는다", () => {
    mocks.auth = { session: null, ready: true };
    vi.stubGlobal("fetch", vi.fn());
    const { container } = render(<AdminProductsPage />);
    expect(container).toBeEmptyDOMElement();
    expect(fetch).not.toHaveBeenCalled();
  });
});
