-- 결제(포트원)를 위해 주문에 만료 시각(expires_at), 결제 시각(paid_at), 결제 거래 id(payment_transaction_id)를 기록하고 status를 4개 값으로 제한한다.
-- 세 컬럼은 NULL 허용 컬럼 추가라 테이블을 다시 쓰지 않는 메타데이터 변경이다. 기존 주문은 결제 없이 만들어졌으므로 expires_at/paid_at을 NULL로 그대로 둔다(없던 결제 사실을 만들어내지 않는다).
-- 기존 행의 status는 애플리케이션이 항상 'PAID'로 저장해 왔으므로 CHECK 제약을 만족한다(다른 값이 있으면 이 마이그레이션이 실패하니 배포 전 SELECT DISTINCT status로 확인). 단, 제약 추가는 기존 행을 한 번 훑으며 ACCESS EXCLUSIVE 락을 잡는다(주문이 적은 지금은 순식간이지만 행이 많아지면 NOT VALID 후 VALIDATE로 나눈다).
-- 다음 PR에서 CANCELLED/REFUNDED 등을 추가할 때는 이 제약을 새 마이그레이션에서 바꾼다.
-- 부분 인덱스는 만료 정리 작업이 PENDING_PAYMENT 주문만 expires_at 순으로 훑게 한다. 새 인덱스 생성은 orders 쓰기를 잠시 막지만 행이 적어 짧다.
-- lock_timeout으로 orders 락 대기가 길어지면 배포를 실패시켜 주문이 오래 막히지 않게 한다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE orders
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN paid_at TIMESTAMPTZ,
    ADD COLUMN payment_transaction_id VARCHAR(100),
    ADD CONSTRAINT orders_status_valid CHECK (status IN ('PENDING_PAYMENT','PAID','EXPIRED','PAYMENT_REVIEW'));
CREATE INDEX idx_orders_pending_expires ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';
