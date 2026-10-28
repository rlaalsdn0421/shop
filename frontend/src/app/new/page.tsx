import { listProducts } from "@/lib/api";
import { PRODUCT_PAGE_SIZE } from "@/lib/constants";
import { InfiniteProductGrid } from "@/components/InfiniteProductGrid";
import { ListingHeader } from "@/components/ListingHeader";
import { SectionNote } from "@/components/ProductSection";

export default async function NewProductsPage() {
  // A failed list call throws, like the category page.
  const { items, hasMore, total } = await listProducts(undefined, 0, PRODUCT_PAGE_SIZE, "newest");

  return (
    <section aria-labelledby="all-title" className="pt-2">
      <ListingHeader title="신상품" total={total} />
      {items.length === 0 ? (
        <SectionNote>아직 신상품이 없어요</SectionNote>
      ) : (
        <div className="mt-8">
          <InfiniteProductGrid
            variant="listing"
            initialItems={items}
            initialHasMore={hasMore}
            sort="newest"
          />
        </div>
      )}
    </section>
  );
}
