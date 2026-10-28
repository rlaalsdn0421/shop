import Link from "next/link";
import { listBestProducts, listProducts } from "@/lib/api";
import { PRODUCT_PAGE_SIZE } from "@/lib/constants";
import { parseBestPeriod, parseCategory, parseSort } from "@/lib/storefront";
import { BestTabs } from "@/components/BestTabs";
import { InfiniteProductGrid } from "@/components/InfiniteProductGrid";
import { ProductCard } from "@/components/ProductCard";
import { ProductSection, SectionNote } from "@/components/ProductSection";
import { SortTabs } from "@/components/SortTabs";
import { WithdrawnNotice } from "@/components/WithdrawnNotice";

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

  if (category) {
    const { items, hasMore, total } = all.value;
    return (
      <section id="all" aria-labelledby="all-title" className="scroll-mt-14 pt-2">
        <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2 border-b-2 border-black pb-4">
          <h1 id="all-title" className="text-2xl font-bold tracking-tight text-neutral-900 md:text-3xl">
            {category}
          </h1>
          <nav aria-label="현재 위치" className="text-xs text-neutral-500">
            <ol className="flex items-center gap-1.5">
              <li>
                <Link href="/" className="hover:text-neutral-900">
                  홈
                </Link>
              </li>
              <li aria-hidden="true">›</li>
              <li>
                {category}
                {total !== undefined && `(총 ${total.toLocaleString("ko-KR")}개)`}
              </li>
            </ol>
          </nav>
        </div>
        {items.length === 0 ? (
          <SectionNote>이 카테고리에는 아직 상품이 없어요</SectionNote>
        ) : (
          <>
            <SortTabs variant="listing" selected={sort} category={category} />
            <InfiniteProductGrid
              variant="listing"
              key={`${category}|${sort}`}
              initialItems={items}
              initialHasMore={hasMore}
              category={category}
              sort={sort}
            />
          </>
        )}
      </section>
    );
  }

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

  return (
    <>
      {query.withdrawn === "1" && <WithdrawnNotice />}
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
