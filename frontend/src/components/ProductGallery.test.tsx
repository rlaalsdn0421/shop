import { describe, it, expect } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProductGallery } from "./ProductGallery";

describe("ProductGallery", () => {
  it("성공: 이미지가 한 장이면 큰 이미지만 보여주고 썸네일은 없다", () => {
    render(<ProductGallery images={["https://example.com/a.jpg"]} name="린넨 셔츠" />);
    expect(screen.getByRole("img", { name: "린넨 셔츠" })).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "상품 이미지 목록" })).not.toBeInTheDocument();
  });

  it("성공: 여러 장이면 썸네일을 보여주고 누르면 큰 이미지가 바뀐다", async () => {
    render(<ProductGallery images={["https://example.com/a.jpg", "https://example.com/b.jpg"]} name="린넨 셔츠" />);
    expect(screen.getAllByRole("button", { name: /이미지 \d 보기/ })).toHaveLength(2);
    expect(screen.getByRole("img", { name: "린넨 셔츠" }).getAttribute("src")).toContain("a.jpg");
    await userEvent.click(screen.getByRole("button", { name: "린넨 셔츠 이미지 2 보기" }));
    expect(screen.getByRole("img", { name: "린넨 셔츠" }).getAttribute("src")).toContain("b.jpg");
    expect(screen.getByRole("button", { name: "린넨 셔츠 이미지 2 보기" })).toHaveAttribute("aria-pressed", "true");
  });

  it("성공: 썸네일은 이름 있는 버튼이고 선택된 것만 눌림 상태이며, 다른 것을 누르면 눌림이 옮겨간다", async () => {
    render(
      <ProductGallery
        images={["https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg"]}
        name="린넨 셔츠"
      />
    );
    const list = within(screen.getByRole("list", { name: "상품 이미지 목록" }));
    expect(list.getAllByRole("button")).toHaveLength(3);
    expect(list.getByRole("button", { name: "린넨 셔츠 이미지 1 보기" })).toHaveAttribute("aria-pressed", "true");
    expect(list.getByRole("button", { name: "린넨 셔츠 이미지 3 보기" })).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(list.getByRole("button", { name: "린넨 셔츠 이미지 3 보기" }));
    expect(list.getByRole("button", { name: "린넨 셔츠 이미지 1 보기" })).toHaveAttribute("aria-pressed", "false");
    expect(list.getByRole("button", { name: "린넨 셔츠 이미지 3 보기" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("img", { name: "린넨 셔츠" }).getAttribute("src")).toContain("c.jpg");
  });

  it("성공: 키보드로 썸네일에 포커스를 옮겨 Enter로 고를 수 있다", async () => {
    render(<ProductGallery images={["https://example.com/a.jpg", "https://example.com/b.jpg"]} name="린넨 셔츠" />);
    await userEvent.tab();
    expect(screen.getByRole("button", { name: "린넨 셔츠 이미지 1 보기" })).toHaveFocus();
    await userEvent.tab();
    await userEvent.keyboard("{Enter}");
    expect(screen.getByRole("img", { name: "린넨 셔츠" }).getAttribute("src")).toContain("b.jpg");
  });

  it("실패: 이미지가 하나도 없으면 아무것도 그리지 않는다", () => {
    const { container } = render(<ProductGallery images={[]} name="린넨 셔츠" />);
    expect(container).toBeEmptyDOMElement();
  });
});
