import { listProducts } from "@/lib/api";
import { RankingCard } from "@/components/RankingCard";

export default async function HomePage() {
  const products = await listProducts();

  if (products.length === 0) {
    return <p className="px-4 py-10 text-center text-sm text-black/50">상품이 없습니다.</p>;
  }

  return (
    <div className="px-3 pt-3">
      <div className="grid grid-cols-2 gap-x-3 gap-y-5">
        {products.map((product, index) => (
          <RankingCard key={product.id} product={product} rank={index + 1} />
        ))}
      </div>
    </div>
  );
}
