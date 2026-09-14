import { productRepository } from "../infrastructure/productRepository";

export function listAdminProducts() {
  return productRepository.listForAdmin();
}
