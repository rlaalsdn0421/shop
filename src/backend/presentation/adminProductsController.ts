import { NextResponse } from "next/server";
import { createProduct } from "../application/createProduct";
import { DomainError } from "../domain/errors";

export async function postAdminProduct(req: Request) {
  const body = await req.json();

  try {
    const product = await createProduct(body);
    return NextResponse.json({ id: product.id });
  } catch (err) {
    if (err instanceof DomainError) {
      return NextResponse.json({ error: err.message }, { status: 400 });
    }
    return NextResponse.json({ error: "상품 등록 중 오류가 발생했습니다." }, { status: 500 });
  }
}
