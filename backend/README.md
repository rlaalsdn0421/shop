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
override with the `CORS_ALLOWED_ORIGINS` env var (comma-separated list of origins).

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

No authentication on any endpoint (matches the rest of this API).
