import { listProducts } from "@/lib/api";
import { ProductCard } from "@/components/ProductCard";
import { CategorySidebar } from "@/components/CategorySidebar";
import { PromoBanner } from "@/components/PromoBanner";

export default async function Home({
  searchParams,
}: {
  searchParams: Promise<{ category?: string | string[] }>;
}) {
  const { category: rawCategory } = await searchParams;
  const category = Array.isArray(rawCategory) ? rawCategory[0] : rawCategory;
  const products = await listProducts(category);

  return (
    <div className="flex flex-col gap-6">
      <PromoBanner />
      <div className="flex gap-6 items-start">
        <CategorySidebar active={category} />
        <div className="flex-1 flex flex-col gap-4 min-w-0">
          <h1 className="text-lg font-extrabold">
            {category ? `${category} 상품` : "🔥 실시간 랭킹"}
          </h1>
          {products.length === 0 ? (
            <p className="text-sm text-gray-500">해당 카테고리에 상품이 없습니다.</p>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-x-4 gap-y-6">
              {products.map((p, i) => (
                <ProductCard
                  key={p.id}
                  id={p.id}
                  name={p.name}
                  price={p.price}
                  imageUrl={p.imageUrl}
                  rank={category ? undefined : i + 1}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
