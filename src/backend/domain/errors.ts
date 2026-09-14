export class DomainError extends Error {}

export class ValidationError extends DomainError {}

export class ProductNotFoundError extends DomainError {
  constructor(productId: string) {
    super(`상품을 찾을 수 없습니다: ${productId}`);
  }
}

export class InsufficientStockError extends DomainError {
  constructor(productName: string) {
    super(`재고가 부족합니다: ${productName}`);
  }
}
