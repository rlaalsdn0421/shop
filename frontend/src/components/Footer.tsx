import Link from "next/link";
import { getBusinessInfo, getPrivacyOfficer } from "@/lib/business";

export function Footer() {
  const biz = getBusinessInfo();
  const officer = getPrivacyOfficer();
  return (
    // Bottom padding keeps the last line clear of the fixed mobile tab bar (3.5rem) and the chat button.
    <footer className="border-t border-neutral-200 bg-neutral-50 px-4 pt-6 pb-32 md:pb-20 text-xs text-neutral-500">
      <div className="max-w-7xl mx-auto flex flex-col gap-3">
        <nav aria-label="정책 안내">
          <ul className="flex flex-wrap gap-x-4 gap-y-1 text-neutral-700">
            <li><Link href="/terms" className="underline">이용약관</Link></li>
            <li><Link href="/privacy" className="underline font-semibold">개인정보 처리방침</Link></li>
            <li><Link href="/refund" className="underline">교환·환불·배송 안내</Link></li>
          </ul>
        </nav>
        {biz.ready && (
          <address className="not-italic leading-5">
            <p>상호 {biz.name} | 대표자 {biz.ceo}</p>
            <p>사업자등록번호 {biz.regNo} | 통신판매업 신고번호 {biz.mailOrderNo}</p>
            <p>주소 {biz.address}</p>
            <p>전화 {biz.phone} | 이메일 {biz.email}</p>
          </address>
        )}
        {officer.ready && (
          <p>개인정보 보호책임자 {officer.name} ({officer.contact})</p>
        )}
      </div>
    </footer>
  );
}
