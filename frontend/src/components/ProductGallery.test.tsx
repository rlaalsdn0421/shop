import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
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

  it("실패: 이미지가 하나도 없으면 아무것도 그리지 않는다", () => {
    const { container } = render(<ProductGallery images={[]} name="린넨 셔츠" />);
    expect(container).toBeEmptyDOMElement();
  });
});
