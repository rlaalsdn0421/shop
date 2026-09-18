import type { ReviewList as ReviewListData } from "@/lib/api";

export function ReviewList({ data }: { data: ReviewListData }) {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <h2 className="text-sm font-extrabold">리뷰</h2>
        {data.reviewCount > 0 ? (
          <span className="text-xs text-black/60">
            ⭐ {data.averageRating?.toFixed(1)} ({data.reviewCount}개)
          </span>
        ) : (
          <span className="text-xs text-black/40">아직 리뷰가 없습니다</span>
        )}
      </div>

      <div className="flex flex-col gap-3">
        {data.reviews.map((review) => (
          <div key={review.id} className="border-b border-black/10 pb-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold">{review.reviewerName}</span>
              <span className="text-xs text-yellow-600">{"⭐".repeat(review.rating)}</span>
            </div>
            <p className="text-xs text-black/70 mt-1">{review.comment}</p>
            <p className="text-[11px] text-black/40 mt-1">
              {new Date(review.createdAt).toLocaleDateString("ko-KR")}
            </p>
          </div>
        ))}
      </div>
    </div>
  );
}
