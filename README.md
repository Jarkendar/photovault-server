# PhotoVault Server

Self-hosted Kotlin + Ktor backend for the PhotoVault Android client — a personal,
self-hosted photo library. Implements the REST API defined in
[`photovault-api-contract`](https://github.com/Jarkendar/photovault-api-contract).

## Tech stack

- **Kotlin 2.3.20** · **Ktor 3.5.0** (Netty engine) · **kotlinx.serialization**
- **PostgreSQL** · **Exposed** (planned — Milestone 2)
- **Docker** — runs on Raspberry Pi 4/5 (arm64)

## Architecture

The server is a single-module Ktor application using `EngineMain` as the entry point
(reads `src/main/resources/application.yaml` at startup).

| Layer | Location | Purpose |
|---|---|---|
| Plugins | `plugins/` | `ContentNegotiation`, `CallLogging`, `StatusPages`, `Routing` |
| Routes | `routes/` | One file per resource (`healthRoutes`, …) |
| DTOs | `dto/` | `@Serializable` classes mirroring the contract |
| Errors | `errors/` | `ApiException`, `ProblemDetails`, `respondProblem` |

All API routes live under `/v1/`. Errors follow **RFC 7807** (`application/problem+json`)
with type URIs of the form `https://photovault.local/errors/<slug>`. The full error catalog
(~20 slugs) is documented in [`contract/api.md`](contract/api.md).

The `contract/` directory is a git submodule pointing to
[`photovault-api-contract`](https://github.com/Jarkendar/photovault-api-contract) —
it is the single source of truth for the API shape.

## Running

**With Docker (recommended):**

```bash
docker compose up --build
curl http://localhost:8080/v1/health
# {"status":"ok","version":"1.0.0"}
```

**Locally (JVM 21 required):**

```bash
./gradlew run
# or: build first, then run the fat JAR
./gradlew shadowJar
java -jar build/libs/photovault-server.jar
```

Copy `.env.example` to `.env` if you need to customise `SERVER_PORT`.

## Development roadmap

| # | Milestone | Status |
|---|---|---|
| 1 | Scaffold + Health + RFC 7807 + Docker | ✅ done |
| 2 | DB layer: Exposed + PostgreSQL + Hikari + migrations | ⬜ |
| 3 | Auth: JWT login/refresh/logout, BCrypt, refresh-token store | ⬜ |
| 4 | Read photos: `GET /v1/photos` (cursor pagination, filters) + `GET /v1/photos/{id}` | ⬜ |
| 5 | Binary assets: thumbnail / medium / original (+ 423 while processing) | ⬜ |
| 6 | Tags / Categories / Labels (full CRUD; labels read-only) | ⬜ |
| 7 | `PATCH /v1/photos/{id}` — favourites + set-semantics for tag/category/label lists | ⬜ |
| 8 | Uploads: multipart 202 + async processing pipeline (Thumbnailator) | ⬜ |
| 9 | Hardening: full error-slug coverage, size/MIME limits, `validation-failed` map | ⬜ |

## License

See [LICENSE](./LICENSE).
