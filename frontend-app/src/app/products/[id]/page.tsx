import Image from "next/image";
import { notFound } from "next/navigation";
import { getProduct, listReviews } from "@/lib/api";
import { AddToCartForm } from "@/components/AddToCartForm";
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

  return (
    <div className="flex flex-col gap-6">
      <div className="relative aspect-square bg-gray-100">
        <Image
          src={product.imageUrl}
          alt={product.name}
          fill
          className="object-cover"
          unoptimized
        />
      </div>

      <div className="flex flex-col gap-3 px-4">
        <h1 className="text-lg font-extrabold leading-snug">{product.name}</h1>
        {reviews && reviews.reviewCount > 0 && (
          <span className="text-xs text-black/60">
            ⭐ {reviews.averageRating?.toFixed(1)} ({reviews.reviewCount}개 리뷰)
          </span>
        )}
        <p className="text-xl font-extrabold">{product.price.toLocaleString()}원</p>
        <p className="text-sm text-black/60 whitespace-pre-wrap">{product.description}</p>
        <AddToCartForm
          id={product.id}
          name={product.name}
          price={product.price}
          imageUrl={product.imageUrl}
          stock={product.stock}
        />
      </div>

      <div className="flex flex-col gap-6 px-4">
        {reviews && <ReviewList data={reviews} />}
        <ReviewForm productId={product.id} />
      </div>
    </div>
  );
}
