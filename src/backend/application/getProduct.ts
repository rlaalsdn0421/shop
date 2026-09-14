import { productRepository } from "../infrastructure/productRepository";

export function getProduct(id: string) {
  return productRepository.findById(id);
}
