import { orderRepository } from "../infrastructure/orderRepository";

export function getOrder(id: string) {
  return orderRepository.findByIdWithItems(id);
}
