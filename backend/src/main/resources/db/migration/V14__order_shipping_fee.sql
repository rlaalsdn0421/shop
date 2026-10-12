-- 주문에 배송비(shipping_fee)를 기록한다. total_amount는 앞으로 상품 금액 + 배송비다.
-- 기존 주문은 당시 배송비를 받지 않았으므로 0으로 둔다(없던 배송비를 만들어내지 않고, 기존 total_amount도 그대로 맞다).
-- 상수 DEFAULT를 가진 NOT NULL 컬럼 추가는 PostgreSQL 11+에서 테이블을 다시 쓰지 않는 메타데이터 변경이라 빠르다.
-- CHECK 제약 추가는 기존 행을 한 번 훑으며 ACCESS EXCLUSIVE 락을 잡는다(주문이 적은 지금은 순식간이지만 행이 많아지면 NOT VALID 후 VALIDATE로 나눈다).
-- lock_timeout으로 orders 락 대기가 길어지면 배포를 실패시켜 주문이 오래 막히지 않게 한다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE orders
    ADD COLUMN shipping_fee INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT orders_shipping_fee_non_negative CHECK (shipping_fee >= 0);
