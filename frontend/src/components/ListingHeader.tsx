import Link from "next/link";

// Title block of a listing page: small breadcrumb on the right, big centered h1 below it.
export function ListingHeader({ title }: { title: string }) {
  return (
    <div className="pb-6 pt-2 md:pb-8">
      <nav aria-label="현재 위치" className="text-right text-xs text-neutral-500">
        <ol className="flex items-center justify-end gap-1.5">
          <li>
            <Link href="/" className="hover:text-neutral-900">
              홈
            </Link>
          </li>
          <li aria-hidden="true">›</li>
          <li>{title}</li>
        </ol>
      </nav>
      <h1 id="all-title" className="mt-6 text-center text-2xl font-bold tracking-tight text-neutral-900 md:mt-8 md:text-3xl">
        {title}
      </h1>
    </div>
  );
}
