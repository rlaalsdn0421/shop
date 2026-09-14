import { productRepository } from "../infrastructure/productRepository";
import { validateNewProduct, type NewProductInput } from "../domain/product";

export async function createProduct(input: Partial<NewProductInput>) {
  const data = validateNewProduct(input);
  return productRepository.create(data);
}
