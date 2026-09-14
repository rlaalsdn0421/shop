import { listProducts } from "@/backend/application/listProducts";
import { ProductCard } from "@/frontend/components/ProductCard";

export default async function Home() {
  const products = await listProducts();

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
