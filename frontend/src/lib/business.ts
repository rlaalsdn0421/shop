// Business details are filled in by the operator through NEXT_PUBLIC_* env vars (inlined at build time).
// Missing, empty or whitespace-only values count as "not set": nothing may be shown from a partial set.
const clean = (value: string | undefined) => (value ?? "").trim();

export type BusinessInfo =
  | { ready: false }
  | {
      ready: true;
      name: string;
      ceo: string;
      regNo: string;
      mailOrderNo: string;
      address: string;
      phone: string;
      email: string;
    };

export function getBusinessInfo(): BusinessInfo {
  const info = {
    name: clean(process.env.NEXT_PUBLIC_BIZ_NAME),
    ceo: clean(process.env.NEXT_PUBLIC_BIZ_CEO),
    regNo: clean(process.env.NEXT_PUBLIC_BIZ_REG_NO),
    mailOrderNo: clean(process.env.NEXT_PUBLIC_BIZ_MAIL_ORDER_NO),
    address: clean(process.env.NEXT_PUBLIC_BIZ_ADDRESS),
    phone: clean(process.env.NEXT_PUBLIC_BIZ_PHONE),
    email: clean(process.env.NEXT_PUBLIC_BIZ_EMAIL),
  };
  return Object.values(info).every(Boolean) ? { ready: true, ...info } : { ready: false };
}

// Contact lines for the refund page: each one is shown on its own as soon as it is set, even if the rest of the business info is not.
export function getBusinessContact(): { phone: string; email: string } {
  return {
    phone: clean(process.env.NEXT_PUBLIC_BIZ_PHONE),
    email: clean(process.env.NEXT_PUBLIC_BIZ_EMAIL),
  };
}

export type PrivacyOfficer = { ready: false } | { ready: true; name: string; contact: string };

export function getPrivacyOfficer(): PrivacyOfficer {
  const name = clean(process.env.NEXT_PUBLIC_PRIVACY_OFFICER);
  const contact = clean(process.env.NEXT_PUBLIC_PRIVACY_OFFICER_CONTACT);
  return name && contact ? { ready: true, name, contact } : { ready: false };
}
