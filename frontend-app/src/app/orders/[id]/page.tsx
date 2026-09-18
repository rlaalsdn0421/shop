import { notFound } from "next/navigation";
import { getOrder } from "@/lib/api";

export default async function OrderComplete({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const order = await getOrder(id);

  if (!order) notFound();

  return (
    <div className="flex flex-col gap-6 px-4 pt-2">
      <div>
        <h1 className="text-lg font-extrabold">주문이 완료되었습니다</h1>
        <p className="text-black/60 text-xs mt-1">주문번호 {order.id}</p>
      </div>

      <div className="flex flex-col gap-2 border-t border-black/10 pt-4">
        {order.items.map((item) => (
          <div key={item.id} className="flex justify-between text-sm">
            <span>
              {item.productName} x {item.quantity}
            </span>
            <span>{(item.price * item.quantity).toLocaleString()}원</span>
          </div>
        ))}
      </div>

      <div className="flex justify-between font-extrabold border-t border-black/10 pt-4">
        <span>총 결제금액</span>
        <span>{order.totalAmount.toLocaleString()}원</span>
      </div>

      <div className="border-t border-black/10 pt-4 text-sm text-black/60 flex flex-col gap-1">
        <p>받는 사람: {order.customerName}</p>
        <p>연락처: {order.customerPhone}</p>
        <p>배송지: {order.customerAddress}</p>
      </div>
    </div>
  );
}
