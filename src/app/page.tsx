import { prisma } from "@/lib/prisma";
import { ProductCard } from "@/components/ProductCard";

export default async function Home() {
  const products = await prisma.product.findMany({
    orderBy: { createdAt: "desc" },
    select: { id: true, name: true, price: true, imageUrl: true },
  });

  return (
    <div className="flex flex-col gap-4">
      <h1 className="sr-only">상품 목록</h1>
      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
        {products.map((p) => (
          <ProductCard
            key={p.id}
            id={p.id}
            name={p.name}
            price={p.price}
            imageUrl={p.imageUrl}
          />
        ))}
      </div>
    </div>
  );
}
