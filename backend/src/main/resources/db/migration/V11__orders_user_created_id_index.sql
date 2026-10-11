-- 내 주문 목록 키셋 페이지네이션(created_at DESC, id DESC)용 인덱스로 교체한다. 새 인덱스를 먼저 만들고 옛 인덱스를 나중에 지워 조회가 인덱스 없는 상태에 놓이지 않게 하고, lock_timeout으로 락 대기가 길어지면 배포를 실패시켜 주문 INSERT가 오래 막히지 않게 한다.
SET LOCAL lock_timeout = '5s';
CREATE INDEX idx_orders_user_created_id ON orders(user_id, created_at DESC, id DESC);
DROP INDEX IF EXISTS idx_orders_user_created;
