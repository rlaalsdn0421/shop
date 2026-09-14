import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

type OrderRequest = {
  customerName: string;
  customerPhone: string;
  customerAddress: string;
  items: { productId: string; quantity: number }[];
};

export async function POST(req: Request) {
  const body: OrderRequest = await req.json();

  if (
    !body.customerName?.trim() ||
    !body.customerPhone?.trim() ||
    !body.customerAddress?.trim() ||
    !body.items?.length ||
    body.customerName.length > 200 ||
    body.customerPhone.length > 50 ||
    body.customerAddress.length > 500
  ) {
    return NextResponse.json({ error: "필수 정보가 누락되었거나 형식이 올바르지 않습니다." }, { status: 400 });
  }

  if (body.items.some((i) => !Number.isInteger(i.quantity) || i.quantity <= 0)) {
    return NextResponse.json({ error: "수량이 올바르지 않습니다." }, { status: 400 });
  }

  // Aggregate duplicate productIds so stock is checked against the total requested,
  // not per line item — otherwise repeated lines for one product bypass the stock check.
  const quantityByProductId = new Map<string, number>();
  for (const item of body.items) {
    quantityByProductId.set(
      item.productId,
      (quantityByProductId.get(item.productId) ?? 0) + item.quantity
    );
  }

  try {
    // ponytail: check-then-decrement isn't safe under concurrent orders for the
    // same product; add a DB check constraint (stock >= 0) if overselling matters.
    const order = await prisma.$transaction(async (tx) => {
      const products = await tx.product.findMany({
        where: { id: { in: [...quantityByProductId.keys()] } },
        select: { id: true, name: true, price: true, stock: true },
      });

      let totalAmount = 0;
      const itemsData = [...quantityByProductId.entries()].map(([productId, quantity]) => {
        const product = products.find((p) => p.id === productId);
        if (!product) throw new Error(`상품을 찾을 수 없습니다: ${productId}`);
        if (product.stock < quantity) {
          throw new Error(`재고가 부족합니다: ${product.name}`);
        }
        totalAmount += product.price * quantity;
        return {
          productId: product.id,
          quantity,
          price: product.price,
        };
      });

      for (const item of itemsData) {
        await tx.product.update({
          where: { id: item.productId },
          data: { stock: { decrement: item.quantity } },
        });
      }

      return tx.order.create({
        data: {
          customerName: body.customerName,
          customerPhone: body.customerPhone,
          customerAddress: body.customerAddress,
          totalAmount,
          items: { create: itemsData },
        },
      });
    });

    return NextResponse.json({ orderId: order.id });
  } catch (err) {
    const message = err instanceof Error ? err.message : "주문 처리 중 오류가 발생했습니다.";
    return NextResponse.json({ error: message }, { status: 400 });
  }
}
