import Link from "next/link";
import type { ReactNode } from "react";

type Props = {
  id: string;
  title: string;
  viewAllHref?: string;
  tabs?: ReactNode;
  children: ReactNode;
};

// scroll-mt keeps the heading clear of the sticky menu row when an anchor link jumps here.
export function ProductSection({ id, title, viewAllHref, tabs, children }: Props) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="scroll-mt-14 pt-12 first:pt-2">
      <div className="relative mb-6 flex flex-col items-center gap-4">
        <h2 id={`${id}-title`} className="text-xl font-extrabold tracking-tight text-neutral-900">
          {title}
        </h2>
        {tabs}
        {viewAllHref && (
          <Link
            href={viewAllHref}
            className="text-xs text-neutral-500 hover:text-neutral-900 absolute right-0 top-1"
          >
            전체보기 →
          </Link>
        )}
      </div>
      {children}
    </section>
  );
}

export function SectionNote({ children }: { children: ReactNode }) {
  return (
    <p role="status" className="py-12 text-center text-sm text-neutral-500">
      {children}
    </p>
  );
}
