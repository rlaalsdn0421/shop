import { listProducts } from "@/lib/api";
import { CategorySidebar } from "@/components/CategorySidebar";
import { PromoBanner } from "@/components/PromoBanner";
import { InfiniteProductGrid } from "@/components/InfiniteProductGrid";

const BANNER_SLIDES = 5;

export default async function Home({
  searchParams,
}: {
  searchParams: Promise<{ category?: string | string[] }>;
}) {
  const { category: rawCategory } = await searchParams;
  const category = Array.isArray(rawCategory) ? rawCategory[0] : rawCategory;
  const [{ items, hasMore }, featured] = await Promise.all([
    listProducts(category),
    listProducts(undefined, 0, BANNER_SLIDES),
  ]);

  return (
    <div className="flex flex-col gap-6">
      <PromoBanner products={featured.items} />
      <div className="flex gap-6 items-start">
        <CategorySidebar active={category} />
        <div className="flex-1 flex flex-col gap-4 min-w-0">
          <h1 className="text-lg font-extrabold">
            {category ? `${category} 상품` : "🔥 실시간 랭킹"}
          </h1>
          <InfiniteProductGrid
            key={category ?? "all"}
            initialItems={items}
            initialHasMore={hasMore}
            category={category}
            showRank={!category}
          />
        </div>
      </div>
    </div>
  );
}
