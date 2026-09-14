import type { Prisma } from "@/generated/prisma/client";
import { prisma } from "./prisma";
import type { CustomerInfo } from "../domain/order";

type Client = typeof prisma | Prisma.TransactionClient;

type OrderItemInput = { productId: string; quantity: number; price: number };

export const orderRepository = {
  create(
    data: CustomerInfo & { totalAmount: number; items: OrderItemInput[] },
    client: Client = prisma
  ) {
    return client.order.create({
      data: {
        customerName: data.customerName,
        customerPhone: data.customerPhone,
        customerAddress: data.customerAddress,
        totalAmount: data.totalAmount,
        items: { create: data.items },
      },
    });
  },

  findByIdWithItems(id: string, client: Client = prisma) {
    return client.order.findUnique({
      where: { id },
      include: { items: { include: { product: true } } },
    });
  },
};
