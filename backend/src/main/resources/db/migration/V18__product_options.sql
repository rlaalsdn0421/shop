-- 상품에 색상·사이즈 옵션(옵션마다 가격·재고)을 두기 위해 product_options 테이블을 새로 만들고 products에 price_varies를 추가한다. 이 파일은 products만 건드린다.
-- 주문 줄(order_items)의 옵션 참조는 락이 겹치지 않게 V19(컬럼과 NOT VALID 외래 키)와 V20(외래 키 검증)으로 나눴다. Flyway는 파일마다 트랜잭션 하나로 실행하므로, 한 파일이 products 락과 order_items 락을 함께 쥐지 않게 한 것이다.
-- 옵션이 없는 기존 상품은 product_options 행이 없고 지금처럼 products.price/stock으로 판매하므로 데이터 이전(backfill)은 하지 않는다. 같은 조합 중복 방지(uq_product_options_combo)는 색상/사이즈가 NULL일 때도 막히도록 COALESCE로 만든다.
-- 새 테이블 생성이라 기존 행 부담이 없다. 다만 product_options.product_id의 외래 키가 products에 SHARE ROW EXCLUSIVE 락을 잡고(쓰기를 잠깐 막는다), ALTER TABLE products ADD COLUMN은 ACCESS EXCLUSIVE 락을 잡는다(읽기도 잠깐 막는다).
-- products.price_varies는 상수 DEFAULT FALSE라 PostgreSQL 11 이상에서 테이블을 다시 쓰지 않는 메타데이터 변경이므로 락은 순식간에 풀린다. 기존 행은 모두 FALSE(가격이 하나뿐)가 된다.
-- 롤아웃: 이전 버전 앱은 새 컬럼·테이블을 모르고 쓰지 않는다. 이전 앱의 products INSERT는 price_varies의 DEFAULT가 채우므로 그대로 동작한다. 새 앱이 먼저 뜨고 마이그레이션이 실패하면 ddl-auto=validate로 기동이 실패해 배포가 중단된다(트래픽은 이전 리비전에 남는다).
-- 옵션 상품은 이 마이그레이션 뒤에 새 앱으로 등록할 때만 생기므로(관리자 등록 화면은 다음 PR) 지금 운영 데이터에는 영향이 없다.
-- 색상/사이즈 조합이 대소문자만 다른 경우와 같은 상품의 옵션 차원 일관성(모두 색상이 있거나 모두 없음)은 DB가 아니라 서비스 검증이 막는다.
-- lock_timeout으로 products 락 대기가 길어지면 배포를 실패시켜 주문이 오래 막히지 않게 한다.
SET LOCAL lock_timeout = '5s';
CREATE TABLE product_options (
    id             VARCHAR(36)  PRIMARY KEY,
    product_id     VARCHAR(36)  NOT NULL REFERENCES products(id),
    color_name     VARCHAR(30),
    color_hex      VARCHAR(7),
    size_label     VARCHAR(30),
    price          INTEGER      NOT NULL CHECK (price >= 0),
    original_price INTEGER      CHECK (original_price IS NULL OR original_price > price),
    stock          INTEGER      NOT NULL CHECK (stock >= 0),
    sort_order     INTEGER      NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CHECK (color_name IS NOT NULL OR size_label IS NOT NULL),
    CHECK (color_hex IS NULL OR (color_name IS NOT NULL AND color_hex ~ '^#[0-9A-Fa-f]{6}$'))
);
CREATE UNIQUE INDEX uq_product_options_combo ON product_options (product_id, COALESCE(color_name, ''), COALESCE(size_label, ''));
CREATE INDEX idx_product_options_product ON product_options (product_id, sort_order);
ALTER TABLE products ADD COLUMN price_varies BOOLEAN NOT NULL DEFAULT FALSE;
