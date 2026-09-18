# Backend

Spring Boot (Java 17) API for the shop, following a 4-layer DDD structure
(`domain` / `application` / `infrastructure` / `presentation`).

## Run

```bash
# Windows
gradlew.bat bootRun

# Git Bash / Unix
./gradlew bootRun
```

The app starts on port `8080` (override with `PORT`). Flyway migrations run automatically on
startup (`src/main/resources/db/migration`); the first migration seeds 6 sample products if the
`products` table is empty.

## Datasource

Defaults (all overridable via env vars) point at a local Postgres started with `npx prisma dev
--db-port 51214`:

| Property | Env var | Default |
|---|---|---|
| URL | `DB_URL` | `jdbc:postgresql://localhost:51214/template1?sslmode=disable` |
| Username | `DB_USERNAME` | `postgres` |
| Password | `DB_PASSWORD` | `postgres` |

CORS is open to `http://localhost:3000` (the Next.js frontend) for all `/api/**` routes;
override with the `CORS_ALLOWED_ORIGINS` env var (comma-separated list of origins). This is
configured in `SecurityConfig` (not a separate `WebMvcConfigurer`) so it doesn't conflict with
the JWT security filter chain.

## API

### Products

- `GET /api/products` — list
- `GET /api/products/{id}` — detail (404 if missing)
- `GET /api/admin/products`, `POST /api/admin/products` — admin listing/creation

### Orders

- `POST /api/orders` — place an order
- `GET /api/orders/{id}` — order detail (404 if missing)

### Reviews

- `GET /api/products/{id}/reviews` — `{ averageRating, reviewCount, reviews[] }`, newest review
  first, `averageRating` is `null` when there are no reviews yet. 404 if the product doesn't exist.
- `POST /api/products/{id}/reviews` — body `{ reviewerName, rating, comment }` (`rating` is an
  integer 1-5). Returns `{ id }` on success, 400 on validation failure, 404 if the product
  doesn't exist.

### Auth

- `POST /api/auth/register` — body `{ email, password }` (password ≥ 8 chars). Registers a
  `USER`-role account. Returns `{ id }` on success, 400 on validation failure or if the email is
  already taken.
- `POST /api/auth/login` — body `{ email, password }`. Returns `{ token, role, email }` on
  success, 401 on wrong email/password (same message either way, to avoid leaking which emails
  are registered).

Three roles: `ADMIN`, `SELLER`, `USER`. Only `USER` is self-registerable; `ADMIN` and `SELLER`
are fixed accounts seeded once at startup (skipped if they already exist):

| Role | Email | Password |
|---|---|---|
| ADMIN | `admin@shop.local` | `admin1234!` |
| SELLER | `seller@shop.local` | `seller1234!` |

These are demo defaults — change them before any real deployment.

Tokens are JWTs (HS256), valid for 2 hours, signed with the `JWT_SECRET` env var. The
`application.yml` default is a local-dev-only fallback and **must** be overridden via
`JWT_SECRET` for any real deployment.

Gated endpoints (require a Bearer token with role `ADMIN` or `SELLER`, i.e.
`Authorization: Bearer <token>`):

- `GET /api/admin/products`
- `POST /api/admin/products`

No token / invalid / expired token → 401. Valid token but wrong role → 403.

Everything else — `/api/products/**`, `/api/orders/**`, and the review endpoints — remains
open with no authentication, unchanged from before.
