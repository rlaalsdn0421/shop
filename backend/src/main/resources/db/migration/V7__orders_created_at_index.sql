-- 베스트 상품 집계(기간 내 주문)가 created_at 범위 조건을 쓰므로 인덱스를 둔다.
CREATE INDEX idx_orders_created_at ON orders(created_at);
