import { productRepository } from "../infrastructure/productRepository";

export function listProducts() {
  return productRepository.list();
}
