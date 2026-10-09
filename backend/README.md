# 백엔드

쇼핑몰용 Spring Boot(Java 17) API. 4계층 DDD 구조(`domain` / `application` / `infrastructure` / `presentation`)를 따르고, 각 계층 안도 종류별 하위 폴더(entity/error, service, repository/security, controller/dto/exception)로 나뉘어 있습니다.

## 실행

```bash
# Windows
gradlew.bat bootRun

# Git Bash / Unix
./gradlew bootRun
```

`8080` 포트로 뜹니다(`PORT` 환경변수로 변경 가능). Flyway 마이그레이션(`src/main/resources/db/migration`)이 기동 시 자동 적용되고, 첫 마이그레이션은 `products` 테이블이 비어 있으면 샘플 상품 6개를 시드합니다.

## 데이터소스

기본값(전부 환경변수로 덮어쓰기 가능)은 `npx prisma dev --db-port 51214`로 띄운 로컬 Postgres를 가리킵니다:

| 속성 | 환경변수 | 기본값 |
|---|---|---|
| URL | `DB_URL` | `jdbc:postgresql://localhost:51214/template1?sslmode=disable` |
| 사용자명 | `DB_USERNAME` | `postgres` |
| 비밀번호 | `DB_PASSWORD` | `postgres` |

CORS는 모든 `/api/**` 경로에 대해 `http://localhost:3000`(Next.js 프론트엔드)에 열려 있고, `CORS_ALLOWED_ORIGINS` 환경변수(쉼표로 구분한 origin 목록)로 덮어쓸 수 있습니다. 별도 `WebMvcConfigurer`가 아니라 `SecurityConfig` 안에서 설정되는데, JWT 시큐리티 필터 체인과 충돌하지 않게 하기 위해서입니다.

## API

### 상품

- `GET /api/products` — 목록 (`?category=` 쿼리 파라미터로 카테고리 필터 가능)
- `GET /api/products/{id}` — 상세 (없으면 404)
- `GET /api/admin/products`, `POST /api/admin/products` — 관리자용 목록 조회/등록

### 주문

- `POST /api/orders` — 주문 생성
- `GET /api/orders/{id}` — 주문 상세 (없으면 404)

### 리뷰

- `GET /api/products/{id}/reviews` — `{ averageRating, reviewCount, reviews[] }` 반환, 최신 리뷰 순, 리뷰가 없으면 `averageRating`은 `null`. 상품이 없으면 404.
- `POST /api/products/{id}/reviews` — 바디 `{ reviewerName, rating, comment }` (`rating`은 1~5 정수). 성공 시 `{ id }` 반환, 검증 실패 시 400, 상품이 없으면 404.

### 인증

- `POST /api/auth/register` — 바디 `{ username, email, password }`. `username`(로그인 아이디)은 영문 소문자·숫자·밑줄 4~20자, 비밀번호는 8~100자이며 영문·숫자·특수문자(ASCII)를 각각 1자 이상 포함(대소문자 구분 없음). `USER` 역할 계정을 등록하고, 이메일은 연락용으로 저장만 합니다(이메일 인증은 아직 없음). 성공 시 `{ id }` 반환, 검증 실패나 이미 사용 중인 아이디/이메일이면 400.
- `POST /api/auth/login` — 바디 `{ username, password }`. 성공 시 `{ token, role, username }` 반환, 아이디/비밀번호가 틀리면 401(어느 쪽이 틀렸는지 노출하지 않도록 메시지는 동일).

무차별 대입과 대량 가입을 막기 위해 **클라이언트 IP 기준**으로 요청 횟수를 제한합니다(아이디는 보지 않음). 카운터와 잠금은 **Redis**에 저장합니다. 로그인은 IP당 5분 안에 **실패**가 5번째로 쌓이면, 가입은 IP당 5분 안에 요청(성공·실패 무관)이 5번째로 쌓이면 **그 5번째 시점부터 5분간** 잠기고, 잠긴 동안의 요청은 자격 검증 전에 429(`Retry-After` 헤더 포함)로 거절됩니다(잠긴 동안의 요청은 세지 않고 잠금도 연장하지 않으며, 풀리면 0부터 다시 셉니다). 같은 IP·같은 종류(로그인/가입)의 요청은 동시에 하나씩만 처리합니다. 요청이 시작될 때 잠금 확인과 짧은 "처리 중" 표시(10초 만료)를 Redis에서 한 번에 잡고, 끝나면 반드시 풀기 때문에 병렬로 쏟아지는 요청이 한도를 한꺼번에 통과할 수 없으며(처리 중이면 429), 서버가 죽어도 표시는 10초 뒤 저절로 사라집니다. 한도는 `rate-limit.*` 설정으로 바꿀 수 있습니다. 클라이언트 IP는 `X-Forwarded-For`(여러 줄이면 하나의 목록으로 보고 오른쪽에서 `trusted-proxy-hops`번째)에서 IP 리터럴만 받아 쓰고, IPv4 매핑 IPv6는 IPv4로, 그 외 IPv6는 /64 접두사 단위로 묶어서 셉니다(한 가정이 /64 안에서 주소를 바꿔 한도를 피할 수 없음). Redis 주소는 환경변수(시크릿) `REDIS_URL`로 받고(TLS는 `rediss://...`), 로컬 기본값은 `redis://localhost:6379`입니다. Redis 무료 한도를 아끼려고, 잠긴 IP는 남은 시간 동안 인스턴스 메모리에 기억해 Redis를 다시 부르지 않고 바로 429로 답합니다. Redis에 연결할 수 없거나 오류가 나거나 무료 한도를 다 쓰면 제한 없이 통과시키지 않고 **인스턴스 메모리 제한기**(같은 규칙, 항목 수 제한)로 넘어가며 경고 로그는 장애당 한 번만 남깁니다. 연결 끊김 중에는 명령을 큐에 쌓지 않고 바로 거절하고 타임아웃이 500ms라 요청당 1초 미만의 지연만 생길 뿐 로그인/가입은 막히지 않습니다. 남는 한계: 이 상태에서는 한도가 인스턴스마다 따로 계산되고 인스턴스가 재시작되면 초기화됩니다.

로그인은 **아이디**로 하고, 이메일은 별개의 값입니다(`users.username`과 `users.email`이 각각 유니크). 역할은 3가지 — `ADMIN`, `SELLER`, `USER`. `USER`만 셀프 가입이 가능하고, `ADMIN`/`SELLER`는 이메일 없이 아이디만 가지는 고정 계정으로 기동 시 한 번 시드됩니다(이미 있으면 건너뜀). 저장소에는 계정 정보를 두지 않고, 아이디와 비밀번호를 **모두 환경변수로** 받습니다. 둘 중 하나라도 비어 있으면 그 계정은 만들지 않습니다.

| 역할 | 환경변수 |
|---|---|
| ADMIN | `ADMIN_USERNAME`, `ADMIN_PASSWORD` |
| SELLER | `SELLER_USERNAME`, `SELLER_PASSWORD` |

로컬에서 관리자 화면을 쓰려면 직접 값을 정해서 실행하세요(예시 값이며 아무 값이나 됩니다).

```bash
ADMIN_USERNAME='원하는-아이디' ADMIN_PASSWORD='원하는-비밀번호' ./gradlew bootRun
```

운영에서는 Secret Manager 값이 환경변수로 주입됩니다.

토큰은 JWT(HS256), 유효기간 2시간, `JWT_SECRET` 환경변수로 서명합니다. `application.yml`의 기본값은 로컬 개발 전용 폴백이고, 실제 배포 시에는 **반드시** `JWT_SECRET`으로 덮어써야 합니다.

보호된 엔드포인트(역할이 `ADMIN` 또는 `SELLER`인 Bearer 토큰 필요, 즉 `Authorization: Bearer <token>`):

- `GET /api/admin/products`
- `POST /api/admin/products`
- `POST /api/orders` (로그인만 되어 있으면 역할 무관 — 장바구니 이용에 로그인을 요구하는 프론트 정책과 짝을 이룸)

토큰이 없거나 무효/만료면 401. 토큰은 유효하지만 역할이 안 맞으면 403.

그 외(`/api/products/**`, `GET /api/orders/{id}`, 리뷰 조회/작성 엔드포인트)는 이전과 동일하게 인증 없이 열려 있습니다.

## 테스트

`backend/src/test`에 컨트롤러별 성공/실패 테스트가 있습니다(`@WebMvcTest` + 서비스 계층 mock, 이 테스트들은 DB 불필요):

```bash
gradlew.bat test   # Windows
./gradlew test     # Git Bash / Unix
```

### 실제 DB 통합 테스트 (Docker 필요)

`src/test/java/com/shop/backend/integration`의 테스트는 [Testcontainers](https://testcontainers.com)로 **진짜 Postgres 16**(`postgres:16-alpine`)을 띄워 Flyway 마이그레이션 V1~최신을 그대로 적용하고 `ddl-auto: validate`로 엔티티와 스키마가 일치하는지까지 확인합니다. 그래서 **`gradlew test`를 돌리려면 Docker(Docker Desktop 등)가 실행 중이어야 합니다.** Docker가 없으면 이 테스트들은 건너뛰지 않고 **실패**합니다(CI가 실행 없이 통과하는 일을 막기 위해서입니다).

이유: mock 테스트는 SQL을 한 줄도 실행하지 않아서, 아래 같은 문제는 실제 DB로만 잡힙니다.

- 정렬 JPQL(할인율/평점 `NULLS LAST`/판매순 `PAID`만 집계/페이지 넘김 시 중복·누락)과 `Product.getDiscountRate()`가 같은 결과를 내는지(과거 `integer out of range` 500 회귀 포함)
- 부분 유니크 인덱스(`uq_reviews_product_user`), CHECK 제약, 유니크 제약 이름으로 중복을 판별하는 코드가 실제 드라이버 메시지와 맞는지, 동시 리뷰 작성 경합
- `TIMESTAMP`(시간대 없음) 컬럼과 베스트 상품 24시간 윈도우가 JVM 시간대(UTC/Asia/Seoul)와 무관하게 맞는지

컨테이너는 JVM당 한 번만 뜨고(첫 실행은 이미지 pull로 더 걸림) 테스트 종료 시 Testcontainers(Ryuk)가 정리합니다. 각 테스트는 시작 전에 테이블을 비우므로 순서와 무관합니다. Docker Engine 29 이상에서는 Testcontainers 1.19(Spring Boot 3.3.4 기본)가 연결되지 않아 `build.gradle`에서 `testcontainers.version`을 1.21.4로 올려 두었습니다.
