-- 로그인 아이디(username)와 연락용 이메일을 분리한다.
ALTER TABLE users ADD COLUMN username VARCHAR(200);

-- 기존 계정은 지금까지 email 칸의 값을 로그인 아이디로 써 왔으므로 그대로 옮긴다.
UPDATE users SET username = email;

ALTER TABLE users ALTER COLUMN username SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_username UNIQUE (username);

-- 이메일은 일반 회원만 가진다(관리자/판매자는 아이디만 사용). NULL은 유니크 제약에서 여러 개 허용된다.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
UPDATE users SET email = NULL WHERE role IN ('ADMIN', 'SELLER');
