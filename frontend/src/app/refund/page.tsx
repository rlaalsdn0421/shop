import type { Metadata } from "next";
import { PolicyPage, type PolicySection } from "@/components/PolicyPage";
import { getBusinessContact } from "@/lib/business";
import { FREE_SHIPPING_THRESHOLD, SHIPPING_FEE } from "@/lib/constants";

export const metadata: Metadata = {
  title: "교환·환불·배송 안내 | 쇼핑몰",
  description: "교환·환불(청약철회) 기간과 비용, 배송비 안내",
};

const won = (n: number) => `${n.toLocaleString("ko-KR")}원`;

export default function RefundPage() {
  const contact = getBusinessContact();
  const sections: PolicySection[] = [
    {
      id: "period",
      title: "교환·환불(청약철회) 기간",
      body: (
        <ul>
          <li>상품을 받은 날부터 7일 이내에 교환 또는 환불을 신청할 수 있습니다.</li>
          <li>받은 상품이 표시·광고와 다르거나 계약 내용과 다르게 이행된 경우(상품 하자, 오배송 등)에는 상품을 받은 날부터 3개월 이내, 또는 그 사실을 안 날부터 30일 이내에 신청할 수 있습니다.</li>
        </ul>
      ),
    },
    {
      id: "cost",
      title: "배송비와 반품 비용",
      body: (
        <ul>
          <li>단순 변심에 의한 교환·환불은 왕복 배송비를 구매자가 부담합니다.</li>
          <li>상품 하자, 오배송 등 판매자의 사정으로 인한 교환·환불은 배송비를 판매자가 부담합니다(무상 처리).</li>
        </ul>
      ),
    },
    {
      id: "cancel",
      title: "주문 취소와 환불 방법",
      body: (
        <ul>
          <li>배송이 시작되기 전에는 주문내역의 주문 상세 화면에서 직접 주문을 취소할 수 있으며, 배송비를 포함한 결제 금액 전액을 환불받습니다.</li>
          <li>배송이 시작된 뒤의 교환·환불은 안내된 연락처로 신청해 주세요.</li>
          <li>환불은 결제하신 원래 결제수단으로 돌려드리며, 카드 결제는 카드 승인 취소로 처리됩니다. 카드사에 따라 환불이 반영되기까지 며칠 걸릴 수 있습니다.</li>
        </ul>
      ),
    },
    {
      id: "limits",
      title: "교환·환불이 제한되는 경우",
      body: (
        <>
          <p>다음과 같이 법령이 정한 경우에는 청약철회가 제한될 수 있습니다.</p>
          <ul>
            <li>구매자의 책임 있는 사유로 상품이 멸실되거나 훼손된 경우(상품 확인을 위해 포장을 개봉한 경우는 제외)</li>
            <li>구매자의 사용 또는 일부 소비로 상품의 가치가 현저히 감소한 경우</li>
            <li>시간이 지나 다시 판매하기 곤란할 정도로 상품의 가치가 현저히 감소한 경우</li>
            <li>복제가 가능한 상품의 포장을 훼손한 경우</li>
          </ul>
        </>
      ),
    },
    {
      id: "shipping",
      title: "배송",
      body: (
        <ul>
          <li>배송비는 {won(SHIPPING_FEE)}이며, 주문 금액이 {won(FREE_SHIPPING_THRESHOLD)} 이상이면 무료입니다.</li>
        </ul>
      ),
    },
    ...(contact.phone || contact.email
      ? [
          {
            id: "how",
            title: "신청 방법",
            body: (
              <ul>
                {contact.phone && <li>전화: {contact.phone}</li>}
                {contact.email && <li>이메일: {contact.email}</li>}
              </ul>
            ),
          },
        ]
      : []),
  ];
  return <PolicyPage title="교환·환불·배송 안내" sections={sections} />;
}
