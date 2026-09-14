import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

type ProductRequest = {
  name: string;
  description: string;
  price: number;
  imageUrl: string;
  stock: number;
};

export async function POST(req: Request) {
  const body: Partial<ProductRequest> = await req.json();
  const { name, description, price, imageUrl, stock } = body;

  if (
    !name?.trim() ||
    !description?.trim() ||
    !imageUrl?.trim() ||
    name.length > 200 ||
    description.length > 2000 ||
    imageUrl.length > 2000
  ) {
    return NextResponse.json({ error: "이름, 설명, 이미지 URL을 올바르게 입력해주세요." }, { status: 400 });
  }
  if (typeof price !== "number" || !Number.isInteger(price) || price < 0) {
    return NextResponse.json({ error: "가격이 올바르지 않습니다." }, { status: 400 });
  }
  if (typeof stock !== "number" || !Number.isInteger(stock) || stock < 0) {
    return NextResponse.json({ error: "재고 수량이 올바르지 않습니다." }, { status: 400 });
  }

  try {
    const product = await prisma.product.create({
      data: { name, description, price, imageUrl, stock },
    });
    return NextResponse.json({ id: product.id });
  } catch {
    return NextResponse.json({ error: "상품 등록 중 오류가 발생했습니다." }, { status: 500 });
  }
}
