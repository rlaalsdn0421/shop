import { listBestProducts, listProducts } from "@/lib/api";
import { PRODUCT_PAGE_SIZE } from "@/lib/constants";
import { parseBestPeriod, parseCategory, parseSort } from "@/lib/storefront";
import { BestTabs } from "@/components/BestTabs";
import { InfiniteProductGrid } from "@/components/InfiniteProductGrid";
import { ProductCard } from "@/components/ProductCard";
import { ProductSection, SectionNote } from "@/components/ProductSection";
import { SortTabs } from "@/components/SortTabs";

const BEST_COUNT = 4;
const NEW_COUNT = 10;
const LOAD_ERROR = "상품을 불러오지 못했어요";

type SearchParams = Promise<{ [key: string]: string | string[] | undefined }>;

export default async function Home({ searchParams }: { searchParams: SearchParams }) {
  const query = await searchParams;
  const category = parseCategory(query.category);
  const sort = parseSort(query.sort);
  const bestPeriod = parseBestPeriod(query.best);
  // Only links that already carry a choice keep it; the defaults stay out of the URL.
  const chosenSort = query.sort === undefined ? undefined : sort;
  const chosenBest = query.best === undefined ? undefined : bestPeriod;

  // 베스트/신상품 may call endpoints a not-yet-deployed backend lacks, so a failure there
  // only degrades its own section. Only the 전체상품 list is allowed to fail the page.
  const [all, best, fresh] = await Promise.allSettled([
    listProducts(category, 0, PRODUCT_PAGE_SIZE, sort),
    category ? Promise.resolve([]) : listBestProducts(bestPeriod, BEST_COUNT),
    category
      ? Promise.resolve({ items: [], hasMore: false })
      : listProducts(undefined, 0, NEW_COUNT, "newest"),
  ]);
  if (all.status === "rejected") throw all.reason;

  const allSection = (
    <ProductSection id="all" title={category ?? "전체상품"}>
      <SortTabs selected={sort} category={category} best={category ? undefined : chosenBest} />
      <InfiniteProductGrid
        key={`${category ?? ""}|${sort}`}
        initialItems={all.value.items}
        initialHasMore={all.value.hasMore}
        category={category}
        sort={sort}
      />
    </ProductSection>
  );

  if (category) return allSection;

  return (
    <>
      <ProductSection
        id="best"
        title="베스트 상품"
        viewAllHref="/?sort=popular#all"
        tabs={<BestTabs selected={bestPeriod} sort={chosenSort} />}
      >
        {best.status === "rejected" ? (
          <SectionNote>{LOAD_ERROR}</SectionNote>
        ) : best.value.length === 0 ? (
          <SectionNote>아직 베스트 상품이 없어요</SectionNote>
        ) : (
          <div className="grid grid-cols-2 gap-x-4 gap-y-8 lg:grid-cols-4">
            {best.value.map((p, i) => (
              <ProductCard key={p.id} {...p} rank={i + 1} />
            ))}
          </div>
        )}
      </ProductSection>
      <ProductSection id="new" title="신상품" viewAllHref="/?sort=newest#all">
        {fresh.status === "rejected" ? (
          <SectionNote>{LOAD_ERROR}</SectionNote>
        ) : fresh.value.items.length === 0 ? (
          <SectionNote>아직 신상품이 없어요</SectionNote>
        ) : (
          <div className="grid grid-cols-2 gap-x-4 gap-y-8 md:grid-cols-5">
            {fresh.value.items.map((p) => (
              <ProductCard key={p.id} {...p} />
            ))}
          </div>
        )}
      </ProductSection>
      {allSection}
    </>
  );
}
