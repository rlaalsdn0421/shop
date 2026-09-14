import { ValidationError } from "./errors";

export type NewProductInput = {
  name: string;
  description: string;
  price: number;
  imageUrl: string;
  stock: number;
};

export function validateNewProduct(input: Partial<NewProductInput>): NewProductInput {
  const { name, description, price, imageUrl, stock } = input;

  if (
    !name?.trim() ||
    !description?.trim() ||
    !imageUrl?.trim() ||
    name.length > 200 ||
    description.length > 2000 ||
    imageUrl.length > 2000
  ) {
    throw new ValidationError("이름, 설명, 이미지 URL을 올바르게 입력해주세요.");
  }
  if (typeof price !== "number" || !Number.isInteger(price) || price < 0) {
    throw new ValidationError("가격이 올바르지 않습니다.");
  }
  if (typeof stock !== "number" || !Number.isInteger(stock) || stock < 0) {
    throw new ValidationError("재고 수량이 올바르지 않습니다.");
  }

  return { name, description, price, imageUrl, stock };
}
