import { listProducts } from "@/lib/api";
import { ProductCard } from "@/components/ProductCard";

export default async function Home() {
  const products = await listProducts();

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-lg font-extrabold">🔥 실시간 랭킹</h1>
      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-x-4 gap-y-6">
        {products.map((p, i) => (
          <ProductCard
            key={p.id}
            id={p.id}
            name={p.name}
            price={p.price}
            imageUrl={p.imageUrl}
            rank={i + 1}
          />
        ))}
      </div>
    </div>
  );
}
