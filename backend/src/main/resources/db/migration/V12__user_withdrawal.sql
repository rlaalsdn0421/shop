-- 회원 탈퇴 시각. 주문 기록(5년 보관)을 위해 users 행은 지우지 않고 익명화한 뒤 이 값을 채운다. 기존 행은 NULL(활성).
-- lock_timeout으로 users 락 대기가 길어지면 배포를 실패시켜 로그인·가입이 오래 막히지 않게 한다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMP;
-- 탈퇴 시 renameReviewer(user_id로 UPDATE)와 향후 users FK 검사가 reviews 전체를 스캔하지 않게 한다. user_id가 NULL인 옛 익명 리뷰는 제외.
CREATE INDEX idx_reviews_user_id ON reviews(user_id) WHERE user_id IS NOT NULL;
