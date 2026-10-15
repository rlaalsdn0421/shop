-- 상품에 색상·사이즈 옵션(옵션마다 가격·재고)을 두기 위해 product_options 테이블을 새로 만들고, order_items에 옵션 참조(option_id)와 옵션 이름 스냅샷(option_label)을, products에 price_varies를 추가한다.
-- 옵션이 없는 기존 상품은 product_options 행이 없고 지금처럼 products.price/stock으로 판매하므로 데이터 이전(backfill)은 하지 않는다. 같은 조합 중복 방지(uq_product_options_combo)는 색상/사이즈가 NULL일 때도 막히도록 COALESCE로 만든다.
-- 새 테이블 생성이라 기존 행·락 부담이 없다. order_items.option_id, order_items.option_label은 NULL 허용 컬럼 추가라 테이블을 다시 쓰지 않는 메타데이터 변경이고(기존 주문 줄은 옵션이 없으므로 NULL 그대로),
-- option_id의 외래 키는 order_items에 SHARE ROW EXCLUSIVE 락을 잡지만 모든 행의 값이 NULL이라 검증은 순식간이다. products.price_varies는 상수 DEFAULT FALSE라 테이블을 다시 쓰지 않고 기존 행은 모두 FALSE(가격이 하나뿐)가 된다.
-- 롤아웃: 이전 버전 앱은 새 컬럼·테이블을 모르고 쓰지 않는다. 이전 앱의 order_items INSERT는 새 컬럼이 NULL 허용이고 products INSERT는 price_varies의 DEFAULT가 채우므로 그대로 동작한다. 새 앱이 먼저 뜨고 이 마이그레이션이 실패하면 ddl-auto=validate로 기동이 실패해 배포가 중단된다(트래픽은 이전 리비전에 남는다).
-- 옵션 상품은 이 마이그레이션 뒤에 새 앱으로 등록할 때만 생기므로(관리자 등록 화면은 다음 PR) 지금 운영 데이터에는 영향이 없다.
-- 옵션 행 삭제는 지원하지 않는다(주문 기록이 order_items.option_id로 가리킨다). 삭제 기능이 생기면 order_items.option_id 인덱스를 함께 만든다(FK 검사가 order_items를 훑지 않게).
-- 색상/사이즈 조합이 대소문자만 다른 경우와 같은 상품의 옵션 차원 일관성(모두 색상이 있거나 모두 없음)은 DB가 아니라 서비스 검증이 막는다.
-- lock_timeout으로 products, order_items 락 대기가 길어지면 배포를 실패시켜 주문이 오래 막히지 않게 한다.
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
ALTER TABLE order_items
    ADD COLUMN option_id VARCHAR(36) REFERENCES product_options(id),
    ADD COLUMN option_label VARCHAR(80);
ALTER TABLE products ADD COLUMN price_varies BOOLEAN NOT NULL DEFAULT FALSE;
