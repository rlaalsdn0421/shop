import { prisma } from "../infrastructure/prisma";
import { productRepository } from "../infrastructure/productRepository";
import { orderRepository } from "../infrastructure/orderRepository";
import {
  aggregateQuantities,
  validateCustomerInfo,
  validateOrderLines,
  type CustomerInfo,
  type OrderLine,
} from "../domain/order";
import { InsufficientStockError, ProductNotFoundError } from "../domain/errors";

export type CreateOrderInput = Partial<CustomerInfo> & { items?: OrderLine[] };

export async function createOrder(input: CreateOrderInput) {
  const customer = validateCustomerInfo(input);
  const lines = validateOrderLines(input.items);
  const quantityByProductId = aggregateQuantities(lines);

  return prisma.$transaction(async (tx) => {
    const products = await productRepository.findManyByIds(
      [...quantityByProductId.keys()],
      tx
    );

    let totalAmount = 0;
    const items = [...quantityByProductId.entries()].map(([productId, quantity]) => {
      const product = products.find((p) => p.id === productId);
      if (!product) throw new ProductNotFoundError(productId);
      if (product.stock < quantity) throw new InsufficientStockError(product.name);
      totalAmount += product.price * quantity;
      return { productId: product.id, quantity, price: product.price };
    });

    for (const item of items) {
      // ponytail: check-then-decrement isn't safe under concurrent orders for the
      // same product; the DB check constraint (stock >= 0) is the real backstop.
      await productRepository.decrementStock(item.productId, item.quantity, tx);
    }

    return orderRepository.create({ ...customer, totalAmount, items }, tx);
  });
}
