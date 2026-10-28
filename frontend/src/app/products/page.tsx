import { listProducts } from "@/lib/api";
import { PRODUCT_PAGE_SIZE } from "@/lib/constants";
import { parseSort } from "@/lib/storefront";
import { InfiniteProductGrid } from "@/components/InfiniteProductGrid";
import { ListingHeader } from "@/components/ListingHeader";
import { SectionNote } from "@/components/ProductSection";
import { SortTabs } from "@/components/SortTabs";

type SearchParams = Promise<{ [key: string]: string | string[] | undefined }>;

export default async function AllProductsPage({ searchParams }: { searchParams: SearchParams }) {
  const sort = parseSort((await searchParams).sort);
  // A failed list call throws, like the category page.
  const { items, hasMore, total } = await listProducts(undefined, 0, PRODUCT_PAGE_SIZE, sort);

  return (
    <section aria-labelledby="all-title" className="pt-2">
      <ListingHeader title="전체상품" total={total} />
      {items.length === 0 ? (
        <SectionNote>아직 상품이 없어요</SectionNote>
      ) : (
        <>
          <SortTabs variant="listing" selected={sort} basePath="/products" />
          <InfiniteProductGrid
            variant="listing"
            key={sort}
            initialItems={items}
            initialHasMore={hasMore}
            sort={sort}
          />
        </>
      )}
    </section>
  );
}
