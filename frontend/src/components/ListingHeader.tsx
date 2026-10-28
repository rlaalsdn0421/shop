import Link from "next/link";

// Title row of a listing page: big left-aligned h1, breadcrumb on the right, thick rule below.
// `total` is optional so an older backend without it only loses the "(총 N개)" part.
export function ListingHeader({ title, total }: { title: string; total?: number }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2 border-b-2 border-black pb-4">
      <h1 id="all-title" className="text-2xl font-bold tracking-tight text-neutral-900 md:text-3xl">
        {title}
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
            {title}
            {total !== undefined && `(총 ${total.toLocaleString("ko-KR")}개)`}
          </li>
        </ol>
      </nav>
    </div>
  );
}
