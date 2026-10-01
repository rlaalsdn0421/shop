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

- `POST /api/auth/register` — 바디 `{ email, password }` (비밀번호 8자 이상). `USER` 역할 계정을 등록. 성공 시 `{ id }` 반환, 검증 실패나 이미 등록된 이메일이면 400.
- `POST /api/auth/login` — 바디 `{ email, password }`. 성공 시 `{ token, role, email }` 반환, 이메일/비밀번호가 틀리면 401(어느 쪽이 틀렸는지 노출하지 않도록 메시지는 동일).

역할은 3가지 — `ADMIN`, `SELLER`, `USER`. `USER`만 셀프 가입이 가능하고, `ADMIN`/`SELLER`는 기동 시 한 번 시드되는 고정 계정입니다(이미 있으면 건너뜀):

| 역할 | 이메일 | 비밀번호 |
|---|---|---|
| ADMIN | `admin@shop.local` | `admin1234!` |
| SELLER | `seller@shop.local` | `seller1234!` |

데모용 기본값이니 실제 배포 전에는 반드시 변경하세요.

토큰은 JWT(HS256), 유효기간 2시간, `JWT_SECRET` 환경변수로 서명합니다. `application.yml`의 기본값은 로컬 개발 전용 폴백이고, 실제 배포 시에는 **반드시** `JWT_SECRET`으로 덮어써야 합니다.

보호된 엔드포인트(역할이 `ADMIN` 또는 `SELLER`인 Bearer 토큰 필요, 즉 `Authorization: Bearer <token>`):

- `GET /api/admin/products`
- `POST /api/admin/products`
- `POST /api/orders` (로그인만 되어 있으면 역할 무관 — 장바구니 이용에 로그인을 요구하는 프론트 정책과 짝을 이룸)

토큰이 없거나 무효/만료면 401. 토큰은 유효하지만 역할이 안 맞으면 403.

그 외(`/api/products/**`, `GET /api/orders/{id}`, 리뷰 조회/작성 엔드포인트)는 이전과 동일하게 인증 없이 열려 있습니다.

## 테스트

`backend/src/test`에 컨트롤러별 성공/실패 테스트가 있습니다(`@WebMvcTest` + 서비스 계층 mock, DB 불필요):

```bash
gradlew.bat test   # Windows
./gradlew test     # Git Bash / Unix
```
