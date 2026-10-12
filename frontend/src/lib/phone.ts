// Mirrors the backend rule: ^\+?[0-9() -]+$ with 9 to 15 digits.
export const phoneDigits = (phone: string) => phone.replace(/\D/g, "");

export function isValidPhone(phone: string) {
  const digits = phoneDigits(phone).length;
  return /^\+?[0-9() -]+$/.test(phone) && digits >= 9 && digits <= 15;
}
