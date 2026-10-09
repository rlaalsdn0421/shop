-- 리뷰 작성자(로그인 사용자)를 기록한다. 기존 익명 리뷰는 user_id가 NULL인 채로 남고 집계에도 그대로 포함된다.
ALTER TABLE reviews ADD COLUMN user_id VARCHAR(36) REFERENCES users(id);

-- 상품당 사용자 1개 리뷰. NULL(기존 익명 리뷰)은 제약 대상이 아니다.
CREATE UNIQUE INDEX uq_reviews_product_user ON reviews(product_id, user_id) WHERE user_id IS NOT NULL;
