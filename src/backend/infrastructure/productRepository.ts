import type { Prisma } from "@/generated/prisma/client";
import { prisma } from "./prisma";
import type { NewProductInput } from "../domain/product";

type Client = typeof prisma | Prisma.TransactionClient;

export const productRepository = {
  list(client: Client = prisma) {
    return client.product.findMany({
      orderBy: { createdAt: "desc" },
      select: { id: true, name: true, price: true, imageUrl: true },
    });
  },

  listForAdmin(client: Client = prisma) {
    return client.product.findMany({
      orderBy: { createdAt: "desc" },
      select: { id: true, name: true, price: true, stock: true, createdAt: true },
    });
  },

  findById(id: string, client: Client = prisma) {
    return client.product.findUnique({ where: { id } });
  },

  findManyByIds(ids: string[], client: Client = prisma) {
    return client.product.findMany({
      where: { id: { in: ids } },
      select: { id: true, name: true, price: true, stock: true },
    });
  },

  create(data: NewProductInput, client: Client = prisma) {
    return client.product.create({ data });
  },

  decrementStock(id: string, quantity: number, client: Client = prisma) {
    return client.product.update({
      where: { id },
      data: { stock: { decrement: quantity } },
    });
  },
};
