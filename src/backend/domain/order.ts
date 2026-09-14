import { ValidationError } from "./errors";

export type OrderLine = { productId: string; quantity: number };

export type CustomerInfo = {
  customerName: string;
  customerPhone: string;
  customerAddress: string;
};

export function validateCustomerInfo(info: Partial<CustomerInfo>): CustomerInfo {
  if (
    !info.customerName?.trim() ||
    !info.customerPhone?.trim() ||
    !info.customerAddress?.trim() ||
    info.customerName.length > 200 ||
    info.customerPhone.length > 50 ||
    info.customerAddress.length > 500
  ) {
    throw new ValidationError("필수 정보가 누락되었거나 형식이 올바르지 않습니다.");
  }
  return {
    customerName: info.customerName,
    customerPhone: info.customerPhone,
    customerAddress: info.customerAddress,
  };
}

export function validateOrderLines(lines: OrderLine[] | undefined): OrderLine[] {
  if (!lines?.length) {
    throw new ValidationError("주문할 상품이 없습니다.");
  }
  if (lines.some((line) => !Number.isInteger(line.quantity) || line.quantity <= 0)) {
    throw new ValidationError("수량이 올바르지 않습니다.");
  }
  return lines;
}

// Aggregate duplicate productIds so stock is checked against the total requested,
// not per line item — otherwise repeated lines for one product bypass the stock check.
export function aggregateQuantities(lines: OrderLine[]): Map<string, number> {
  const quantityByProductId = new Map<string, number>();
  for (const line of lines) {
    quantityByProductId.set(
      line.productId,
      (quantityByProductId.get(line.productId) ?? 0) + line.quantity
    );
  }
  return quantityByProductId;
}
