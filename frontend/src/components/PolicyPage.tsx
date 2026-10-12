import type { ReactNode } from "react";
import { POLICY_EFFECTIVE_DATE_LABEL } from "@/lib/policy";

export type PolicySection = { id: string; title: string; body: ReactNode };

// Shared layout for the static policy pages: title, effective date, table of contents, numbered sections.
export function PolicyPage({ title, sections }: { title: string; sections: PolicySection[] }) {
  return (
    <article className="max-w-3xl mx-auto flex flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-2xl font-bold">{title}</h1>
        <p className="text-sm text-neutral-500">시행일 {POLICY_EFFECTIVE_DATE_LABEL}</p>
      </header>
      <nav aria-label="목차">
        <ol className="list-decimal pl-5 text-sm text-neutral-700 space-y-1">
          {sections.map((s) => (
            <li key={s.id}>
              <a href={`#${s.id}`} className="underline">{s.title}</a>
            </li>
          ))}
        </ol>
      </nav>
      {sections.map((s, i) => (
        <section key={s.id} id={s.id} aria-labelledby={`${s.id}-title`} className="flex flex-col gap-2 scroll-mt-4">
          <h2 id={`${s.id}-title`} className="text-lg font-semibold">{i + 1}. {s.title}</h2>
          <div className="space-y-2 text-sm leading-7 text-neutral-700 [&_ul]:list-disc [&_ul]:pl-5 [&_ol]:list-decimal [&_ol]:pl-5 [&_a]:underline">
            {s.body}
          </div>
        </section>
      ))}
    </article>
  );
}
