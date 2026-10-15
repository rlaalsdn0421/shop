import Link from "next/link";
import { notFound } from "next/navigation";
import { getProduct, listReviews } from "@/lib/api";
import { FREE_SHIPPING_THRESHOLD, SHIPPING_FEE } from "@/lib/constants";
import { MENU_ITEMS } from "@/lib/menu";
import { discountView, formatWon, visibleHashtags } from "@/lib/price";
import { AddToCartForm } from "@/components/AddToCartForm";
import { ProductGallery } from "@/components/ProductGallery";
import { ReviewList } from "@/components/ReviewList";
import { ReviewForm } from "@/components/ReviewForm";

export default async function ProductDetail({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const [product, reviews] = await Promise.all([getProduct(id), listReviews(id)]);

  if (!product) notFound();

  const { rate, original } = discountView(product);
  const tags = visibleHashtags(product.hashtags);
  const reviewCount = reviews?.reviewCount ?? 0;
  const average = (reviews?.averageRating ?? 0).toFixed(1);
  // Same link the menu uses for a category; an unknown name is not linkable, so it is skipped.
  const category = MENU_ITEMS.find((item) => item.key === `category:${product.category}`);
  const shippingNote = `배송비 ${formatWon(SHIPPING_FEE)} (${formatWon(FREE_SHIPPING_THRESHOLD)} 이상 무료배송)`;
  const link = "underline underline-offset-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-black";

  return (
    <div className="flex flex-col gap-10">
      <nav aria-label="현재 위치" className="text-sm text-gray-500">
        <ol className="flex flex-wrap items-center gap-x-2">
          <li>
            <Link href="/" className="hover:text-black hover:underline">
              홈
            </Link>
          </li>
          {category && (
            <li className="flex items-center gap-x-2">
              <span aria-hidden="true">/</span>
              <Link href={category.href} className="hover:text-black hover:underline">
                {category.label}
              </Link>
            </li>
          )}
        </ol>
      </nav>

      <div className="grid md:grid-cols-2 gap-8 lg:gap-12 -mt-6">
        <ProductGallery images={product.images?.length ? product.images : [product.imageUrl]} name={product.name} />
        <div className="flex flex-col gap-4">
          <h1 className="text-2xl font-bold">{product.name}</h1>
          <p className="text-sm text-gray-600">
            <span aria-hidden="true">★</span>
            <span className="sr-only">평점 </span> {average} · <a href="#reviews" className={link}>리뷰 {reviewCount}건</a>
          </p>

          <div>
            <div className="flex flex-wrap items-baseline gap-x-2">
              {rate !== null && (
                <span className="text-2xl font-bold text-red-600">
                  <span className="sr-only">할인율 </span>
                  {rate}%
                </span>
              )}
              <p className="text-3xl font-bold">{formatWon(product.price)}</p>
              {original !== null && (
                <del className="text-sm text-gray-400">
                  <span className="sr-only">정가 </span>
                  {formatWon(original)}
                </del>
              )}
            </div>
            <p className="mt-1 text-sm text-gray-600">{shippingNote}</p>
          </div>

          {tags.length > 0 && (
            <ul aria-label="해시태그" className="flex flex-wrap gap-x-2 gap-y-1 text-sm text-gray-500">
              {tags.map((tag) => (
                <li key={tag} className="max-w-full truncate">
                  {`#${tag}`}
                </li>
              ))}
            </ul>
          )}
          <p className="text-gray-600 line-clamp-3">{product.description}</p>

          <dl className="grid grid-cols-[4rem_1fr] gap-y-2 rounded border bg-gray-50 p-3 text-sm">
            <dt className="text-gray-500">배송</dt>
            <dd>
              {shippingNote}{" "}
              <Link href="/refund" className={link}>
                교환·환불 안내
              </Link>
            </dd>
          </dl>

          <AddToCartForm
            id={product.id}
            name={product.name}
            price={product.price}
            imageUrl={product.imageUrl}
            stock={product.stock}
          />
        </div>
      </div>

      <nav aria-label="상품 정보 이동" className="flex border-y text-center font-medium">
        <a href="#description" className="flex-1 py-3 hover:bg-gray-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-black">
          상품 설명
        </a>
        <a href="#reviews" className="flex-1 py-3 hover:bg-gray-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-black">
          리뷰 ({reviewCount})
        </a>
      </nav>

      <div className="max-w-3xl w-full mx-auto flex flex-col gap-12">
        <section id="description" aria-labelledby="description-title" className="scroll-mt-4">
          <h2 id="description-title" className="text-lg font-bold mb-3">
            상품 설명
          </h2>
          <p className="text-gray-700 whitespace-pre-line">{product.description}</p>
        </section>

        <section id="reviews" className="scroll-mt-4 flex flex-col gap-6">
          {reviews && <ReviewList data={reviews} />}
          <ReviewForm productId={product.id} />
        </section>

        <section aria-labelledby="delivery-title" className="text-sm text-gray-600">
          <h2 id="delivery-title" className="text-lg font-bold mb-3 text-black">
            배송·교환·환불
          </h2>
          <p>
            {shippingNote}
            <br />
            교환·환불은 상품을 받은 날부터 7일 이내에 신청할 수 있어요.{" "}
            <Link href="/refund" className={link}>
              교환·환불 안내 보기
            </Link>
          </p>
        </section>
      </div>
    </div>
  );
}
