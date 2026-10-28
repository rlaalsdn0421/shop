import { listBestProducts } from "@/lib/api";
import { parseBestPeriod } from "@/lib/storefront";
import { BestTabs } from "@/components/BestTabs";
import { ListingHeader } from "@/components/ListingHeader";
import { ProductCard } from "@/components/ProductCard";
import { SectionNote } from "@/components/ProductSection";

const BEST_COUNT = 20;

type SearchParams = Promise<{ [key: string]: string | string[] | undefined }>;

export default async function BestPage({ searchParams }: { searchParams: SearchParams }) {
  const period = parseBestPeriod((await searchParams).best);
  // Unlike the other listing pages a failure only replaces the grid; the header still renders.
  const best = await listBestProducts(period, BEST_COUNT).then(
    (items) => ({ items }),
    () => undefined
  );

  return (
    <section aria-labelledby="all-title" className="pt-2">
      <ListingHeader title="베스트" total={best?.items.length} />
      <BestTabs variant="listing" selected={period} basePath="/best" />
      {!best ? (
        <SectionNote>상품을 불러오지 못했어요</SectionNote>
      ) : best.items.length === 0 ? (
        <SectionNote>아직 베스트 상품이 없어요</SectionNote>
      ) : (
        <div className="grid grid-cols-2 gap-x-4 gap-y-10 md:grid-cols-3 lg:grid-cols-4">
          {best.items.map((p, i) => (
            <ProductCard key={p.id} {...p} rank={i + 1} />
          ))}
        </div>
      )}
    </section>
  );
}
