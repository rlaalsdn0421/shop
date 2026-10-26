-- 찜하기: 사용자별 찜한 상품을 저장하는 wishlist_items 테이블을 새로 만든다. 기본 키가 (user_id, product_id)라 같은 찜이 두 번 생기지 않는다.
-- 새 테이블 생성이라 기존 행·기존 쿼리에 영향이 없고 backfill도 없다. 외래 키 추가가 users·products에 잠깐 SHARE ROW EXCLUSIVE 락을 잡으므로 lock_timeout으로 대기가 길어지면 배포를 실패시킨다.
-- 롤아웃: 이전 버전 앱은 이 테이블을 모르고 쓰지 않으므로 그대로 동작한다. 이 마이그레이션이 실패하면 새 앱이 기동하지 않아 배포가 중단된다(트래픽은 이전 리비전에 남는다).
-- 기본 키 (user_id, product_id)가 모든 조회를 처리한다: 사용자당 행이 최대 200개라 created_at 정렬은 메모리에서 끝나고, 보조 인덱스는 찜 토글의 쓰기 비용만 두 배로 만든다.
-- 상품 삭제 기능이 생기면 이 FK가 삭제를 막으므로 찜 행을 먼저 지우거나 ON DELETE CASCADE를 검토하고, product_id 인덱스도 함께 추가한다(지금은 상품 삭제가 없다).
SET LOCAL lock_timeout = '5s';
CREATE TABLE wishlist_items (
    user_id    VARCHAR(36) NOT NULL REFERENCES users(id),
    product_id VARCHAR(36) NOT NULL REFERENCES products(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, product_id)
);
