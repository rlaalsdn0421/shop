-- V19가 NOT VALID로 등록한 order_items.option_id 외래 키(fk_order_items_option)를 기존 행에 대해서도 검증한다.
-- VALIDATE CONSTRAINT는 order_items에 SHARE UPDATE EXCLUSIVE 락만 잡으므로(참조되는 product_options에는 ROW SHARE) 검증 스캔 중에도 주문 읽기·쓰기가 막히지 않는다. 일반 ADD FOREIGN KEY처럼 쓰기를 막은 채 훑지 않는다.
-- 기존 주문 줄의 option_id는 모두 NULL이라 위반할 행이 없고, 스캔은 order_items 크기에 비례할 뿐 결과는 항상 통과다. 검증이 끝나면 제약은 일반 외래 키와 같다.
-- 롤아웃: 스키마(컬럼·제약 이름)가 바뀌지 않는 파일이라 이전 버전 앱에 영향이 없다. 실패하면 배포가 중단되고 V19까지의 상태(NOT VALID 제약이 새 행을 이미 검사)로 남아 안전하다.
-- lock_timeout으로 락 대기가 길어지면 배포를 실패시켜 주문이 오래 막히지 않게 한다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE order_items VALIDATE CONSTRAINT fk_order_items_option;
