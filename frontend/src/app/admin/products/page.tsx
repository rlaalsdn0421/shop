import Link from "next/link";
import { listAdminProducts } from "@/lib/api";

export default async function AdminProductsPage() {
  const products = await listAdminProducts();

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold">상품 관리</h1>
        <Link
          href="/admin/products/new"
          className="bg-black text-white rounded px-4 py-2 text-sm"
        >
          새 상품 등록
        </Link>
      </div>

      <table className="w-full text-sm border-t">
        <thead>
          <tr className="text-left text-gray-500 border-b">
            <th className="py-2">이름</th>
            <th className="py-2">가격</th>
            <th className="py-2">재고</th>
            <th className="py-2">등록일</th>
          </tr>
        </thead>
        <tbody>
          {products.map((p) => (
            <tr key={p.id} className="border-b">
              <td className="py-2">{p.name}</td>
              <td className="py-2">{p.price.toLocaleString()}원</td>
              <td className="py-2">{p.stock}</td>
              <td className="py-2 text-gray-500">
                {new Date(p.createdAt).toLocaleDateString("ko-KR")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
