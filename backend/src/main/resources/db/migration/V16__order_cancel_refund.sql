-- 주문 취소·환불·배송 상태를 위해 시각 3개(shipped_at, delivered_at, cancelled_at)와 취소 정보 2개(cancel_from, cancel_reason)를 추가하고 status 허용 값을 9개로 늘린다.
-- 다섯 컬럼은 모두 NULL 허용 컬럼 추가라 테이블을 다시 쓰지 않는 메타데이터 변경이다. 기존 주문은 배송/취소 이력이 없으므로 그대로 NULL로 둔다(없던 사실을 만들어내지 않는다).
-- cancel_from은 취소 요청(CANCELING) 중 포트원 취소가 실패했을 때 되돌아갈 직전 상태이며 PAID/SHIPPED/DELIVERED만 허용한다.
-- orders_canceling_has_origin: CANCELING 행은 반드시 cancel_from을 가진다(되돌릴 곳이 없는 취소중 행이 생기지 않게). 기존 행은 CANCELING이 없으므로 모두 통과한다.
-- orders_status_valid는 같은 이름으로 다시 만든다. 새 값 목록은 기존 4개 값을 모두 포함하므로 기존 행은 모두 통과한다. 제약 추가는 기존 행을 한 번 훑으며 ACCESS EXCLUSIVE 락을 잡는다(주문이 적은 지금은 순식간이지만 행이 많아지면 NOT VALID 후 VALIDATE로 나눈다).
-- 롤아웃: 이전 버전 앱은 기존 4개 상태만 쓰므로 새 제약과 충돌하지 않고, 새 컬럼은 NULL 허용이라 이전 버전 INSERT도 그대로 동작한다. 새 앱이 먼저 뜨고 이 마이그레이션이 실패하면 ddl-auto=validate로 기동이 실패하니 배포는 중단된다(트래픽은 이전 리비전에 남는다).
-- 관리자 주문 목록은 created_at DESC, id DESC 순이고 기존 idx_orders_created_at이 받쳐 주므로(상태 탭 필터는 그 위에서 걸러낸다) 새 인덱스는 만들지 않는다. 주문이 수십만 건이 넘으면 (status, created_at DESC, id DESC) 인덱스를 검토한다.
-- lock_timeout으로 orders 락 대기가 길어지면 배포를 실패시켜 주문이 오래 막히지 않게 한다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE orders
    ADD COLUMN shipped_at TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancel_from VARCHAR(20),
    ADD COLUMN cancel_reason VARCHAR(200),
    DROP CONSTRAINT orders_status_valid,
    ADD CONSTRAINT orders_status_valid CHECK (status IN ('PENDING_PAYMENT','PAID','EXPIRED','PAYMENT_REVIEW','CANCELING','CANCELLED','SHIPPED','DELIVERED','REFUNDED')),
    ADD CONSTRAINT orders_cancel_from_valid CHECK (cancel_from IS NULL OR cancel_from IN ('PAID','SHIPPED','DELIVERED')),
    ADD CONSTRAINT orders_canceling_has_origin CHECK (status <> 'CANCELING' OR cancel_from IS NOT NULL);
