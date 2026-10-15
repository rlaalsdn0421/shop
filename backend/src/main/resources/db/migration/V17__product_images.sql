-- 상품 이미지를 여러 장 둘 수 있게 product_images 테이블을 새로 만든다. products.image_url은 대표 이미지로 그대로 둔다(목록·장바구니·주문이 계속 쓴다).
-- 기존 상품은 product_images 행이 없고, 상세 응답이 행이 없으면 [image_url]로 대신하므로 데이터 이전(backfill)은 하지 않는다.
-- 새 테이블 생성이라 기존 행·락 부담이 없다. 외래 키 추가가 products에 잠깐 SHARE ROW EXCLUSIVE 락을 잡으므로 lock_timeout으로 대기가 길어지면 배포를 실패시킨다.
-- 롤아웃: 이전 버전 앱은 이 테이블을 모르고 쓰지 않으므로 그대로 동작한다. 새 앱이 먼저 뜨고 이 마이그레이션이 실패하면 ddl-auto=validate로 기동이 실패해 배포가 중단된다(트래픽은 이전 리비전에 남는다).
-- 상품 삭제 기능이 생기면 이 FK가 삭제를 막으므로 이미지 행을 먼저 지우거나 ON DELETE CASCADE를 검토한다(지금은 상품 삭제가 없다).
SET LOCAL lock_timeout = '5s';
CREATE TABLE product_images (
    id         VARCHAR(36)   PRIMARY KEY,
    product_id VARCHAR(36)   NOT NULL REFERENCES products(id),
    url        VARCHAR(2000) NOT NULL,
    sort_order INTEGER       NOT NULL CHECK (sort_order >= 0),
    UNIQUE (product_id, sort_order)
);
CREATE INDEX idx_product_images_product_id ON product_images (product_id);
