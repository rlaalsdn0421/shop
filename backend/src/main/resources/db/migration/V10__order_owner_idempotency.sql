-- 주문자와 중복 주문 방지 키를 기록한다. 기존 주문은 세 컬럼이 NULL인 채로 남고(주문자 없는 주문은 조회 대상에서 제외), 키가 없는 행은 유니크 제약 대상이 아니다.
ALTER TABLE orders ADD COLUMN user_id VARCHAR(36) REFERENCES users(id);
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(64);
ALTER TABLE orders ADD COLUMN request_hash VARCHAR(64);

-- 같은 사용자의 같은 요청 번호는 주문 1건만 허용(동시 요청 직렬화가 뚫렸을 때의 마지막 안전망).
CREATE UNIQUE INDEX uq_orders_user_idempotency ON orders(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- 사용자별 최신 주문 조회용.
CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
