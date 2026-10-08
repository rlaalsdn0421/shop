-- 가입 시 생년월일을 받는다. 기존 회원과 시드된 ADMIN/SELLER 계정에는 값이 없으므로 NULL 허용(기본값 없음).
ALTER TABLE users ADD COLUMN birth_date DATE;
