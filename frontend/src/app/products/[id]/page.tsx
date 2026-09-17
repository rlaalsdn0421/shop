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
    <div className="flex flex-col gap-10">
      <div className="grid sm:grid-cols-2 gap-8">
        <div className="relative aspect-square bg-gray-100 rounded-lg overflow-hidden">
          <Image
            src={product.imageUrl}
            alt={product.name}
            fill
            className="object-cover"
            unoptimized
          />
        </div>
        <div className="flex flex-col gap-4">
          <h1 className="text-2xl font-bold">{product.name}</h1>
          {reviews && reviews.reviewCount > 0 && (
            <span className="text-sm text-gray-600">
              ⭐ {reviews.averageRating?.toFixed(1)} ({reviews.reviewCount}개 리뷰)
            </span>
          )}
          <p className="text-gray-600">{product.description}</p>
          <p className="text-xl font-semibold">{product.price.toLocaleString()}원</p>
          <AddToCartForm
            id={product.id}
            name={product.name}
            price={product.price}
            imageUrl={product.imageUrl}
            stock={product.stock}
          />
        </div>
      </div>

      <div className="max-w-lg flex flex-col gap-6">
        {reviews && <ReviewList data={reviews} />}
        <ReviewForm productId={product.id} />
      </div>
    </div>
  );
}
