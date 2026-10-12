// Must match the policy_version the backend records at signup. Bump both together when a policy page changes.
export const POLICY_VERSION = "2026-10-12";

// "2026-10-12" -> "2026년 10월 12일"; derived so the version and the date shown on the pages cannot drift apart.
export function policyEffectiveDateLabel(version: string = POLICY_VERSION): string {
  const [y, m, d] = version.split("-").map(Number);
  return `${y}년 ${m}월 ${d}일`;
}

export const POLICY_EFFECTIVE_DATE_LABEL = policyEffectiveDateLabel();
