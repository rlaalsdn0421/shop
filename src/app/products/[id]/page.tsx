import Image from "next/image";
import { notFound } from "next/navigation";
import { getProduct } from "@/backend/application/getProduct";
import { AddToCartForm } from "@/frontend/components/AddToCartForm";

export default async function ProductDetail({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const product = await getProduct(id);

  if (!product) notFound();

  return (
    <div className="grid sm:grid-cols-2 gap-8">
      <div className="relative aspect-square bg-gray-100 rounded-lg overflow-hidden">
        <Image
          src={product.imageUrl}
          alt={product.name}
          fill
          className="object-cover"
          unoptimized
        />
      </div>
      <div className="flex flex-col gap-4">
        <h1 className="text-2xl font-bold">{product.name}</h1>
        <p className="text-gray-600">{product.description}</p>
        <p className="text-xl font-semibold">{product.price.toLocaleString()}원</p>
        <AddToCartForm
          id={product.id}
          name={product.name}
          price={product.price}
          imageUrl={product.imageUrl}
          stock={product.stock}
        />
      </div>
    </div>
  );
}
