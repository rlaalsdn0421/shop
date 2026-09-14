import { NextResponse } from "next/server";
import { createOrder } from "../application/createOrder";
import { DomainError } from "../domain/errors";

export async function postOrder(req: Request) {
  const body = await req.json();

  try {
    const order = await createOrder(body);
    return NextResponse.json({ orderId: order.id });
  } catch (err) {
    if (err instanceof DomainError) {
      return NextResponse.json({ error: err.message }, { status: 400 });
    }
    return NextResponse.json({ error: "주문 처리 중 오류가 발생했습니다." }, { status: 500 });
  }
}
