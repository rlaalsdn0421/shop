-- 주문 줄이 어떤 옵션을 샀는지 기억하도록 order_items에 option_id(옵션 참조)와 option_label(주문 시점의 "화이트 / M" 이름 스냅샷)을 추가한다.
-- 두 컬럼 모두 NULL 허용이고 DEFAULT가 없어 테이블을 다시 쓰지 않는 메타데이터 변경이다. 기존 주문 줄은 옵션이 없으므로 NULL 그대로 둔다(없던 사실을 만들어내지 않는다). 다만 ALTER TABLE ADD COLUMN은 ACCESS EXCLUSIVE 락을 잡아 order_items의 읽기·쓰기를 순식간 막는다.
-- 외래 키는 NOT VALID로 추가한다. 일반 ADD FOREIGN KEY는 SHARE ROW EXCLUSIVE 락을 쥔 채 order_items 전체를 훑어 검증하므로(테이블 스캔) 주문이 쌓일수록 그 시간만큼 주문 쓰기가 막힌다. NOT VALID는 스캔 없이 제약만 등록하고, 이후 새로 들어오는 행(INSERT/UPDATE)부터 바로 검사한다.
-- 기존 행의 검증은 V20이 따로 한다. 한 트랜잭션에 order_items의 ACCESS EXCLUSIVE 락(ADD COLUMN)과 검증 스캔이 같이 들어가지 않게 파일을 나눈 것이다. 이 파일은 products를 건드리지 않는다(V18에서 끝났다).
-- 롤아웃: 이전 버전 앱의 order_items INSERT는 새 컬럼을 쓰지 않으므로 NULL이 들어가 그대로 동작한다. 새 앱이 먼저 뜨고 마이그레이션이 실패하면 ddl-auto=validate로 기동이 실패해 배포가 중단된다(트래픽은 이전 리비전에 남는다).
-- 옵션 행 삭제는 지원하지 않는다(주문 기록이 order_items.option_id로 가리킨다). 삭제 기능이 생기면 order_items.option_id 인덱스를 함께 만든다(FK 검사가 order_items를 훑지 않게).
-- lock_timeout으로 order_items 락 대기가 길어지면 배포를 실패시켜 주문이 오래 막히지 않게 한다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE order_items
    ADD COLUMN option_id VARCHAR(36),
    ADD COLUMN option_label VARCHAR(80),
    ADD CONSTRAINT fk_order_items_option FOREIGN KEY (option_id) REFERENCES product_options(id) NOT VALID;
