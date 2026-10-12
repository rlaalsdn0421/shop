-- 가입 시 이용약관·개인정보 수집이용 동의 증빙(동의 시각, 동의한 약관 버전). 기존 가입자와 시드된 ADMIN/SELLER는 동의 기록이 없으므로 NULL로 두고 채우지 않는다(없던 동의를 만들어내지 않음).
-- NULL 허용 컬럼 추가는 테이블을 다시 쓰지 않는 메타데이터 변경이라 빠르다. lock_timeout으로 users 락 대기가 길어지면 배포를 실패시켜 로그인·가입이 오래 막히지 않게 한다.
SET LOCAL lock_timeout = '5s';
ALTER TABLE users
    ADD COLUMN terms_agreed_at TIMESTAMP,
    ADD COLUMN privacy_agreed_at TIMESTAMP,
    ADD COLUMN policy_version VARCHAR(20);
