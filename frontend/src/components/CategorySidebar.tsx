import Link from "next/link";

const CATEGORIES = [
  "뷰티",
  "신발",
  "상의",
  "아우터",
  "바지",
  "원피스/스커트",
  "가방",
  "모자",
  "소품",
  "속옷/홈웨어",
  "스포츠/레저",
];

export function CategorySidebar({ active }: { active?: string }) {
  return (
    <aside className="hidden lg:block w-44 shrink-0">
      <div className="sticky top-20 border border-gray-200 rounded-lg overflow-hidden">
        <h2 className="px-4 py-3 text-sm font-bold border-b border-gray-200">
          카테고리
        </h2>
        <ul className="text-sm">
          <li className="border-b border-gray-100">
            <Link
              href="/"
              className={`block px-4 py-2.5 ${
                !active ? "font-bold text-black bg-gray-50" : "text-gray-700 hover:bg-gray-50"
              }`}
            >
              전체
            </Link>
          </li>
          {CATEGORIES.map((category) => (
            <li key={category} className="border-b border-gray-100 last:border-b-0">
              <Link
                href={`/?category=${encodeURIComponent(category)}`}
                className={`block px-4 py-2.5 ${
                  active === category
                    ? "font-bold text-black bg-gray-50"
                    : "text-gray-700 hover:bg-gray-50"
                }`}
              >
                {category}
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </aside>
  );
}
