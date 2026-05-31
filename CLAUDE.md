# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Self-hosted Kotlin + Ktor backend for a personal photo library. Implements the REST API defined in the `contract/` git submodule. Intended to run in Docker on a Raspberry Pi. Data layer: PostgreSQL via HikariCP + Exposed ORM — fully wired. Schema is created and seeded at startup by `db/DatabaseInit.kt` (6 color labels + a default `admin` user on first run).

## Commands

```bash
# Build a fat JAR
./gradlew shadowJar

# Run the server (after build)
java -jar build/libs/photovault-server.jar

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "dev.jskrzypczak.photovault.server.SomeTest"

# Run a single test method
./gradlew test --tests "dev.jskrzypczak.photovault.server.SomeTest.methodName"
```

## Architecture

### Tech stack

- **Kotlin 2.3.20** / **JVM 21**
- **Ktor 3.5.0** (Netty engine, `EngineMain` entry point reading `application.yaml`)
- **kotlinx.serialization** for all JSON — `camelCase` field names, no custom adapters needed
- **Exposed ORM** + **HikariCP** (pool size 10, `TRANSACTION_REPEATABLE_READ`) over PostgreSQL
- **Logback** for logging
- **JUnit 5** + `ktor-server-test-host` for tests

### API contract

The `contract/` directory is a git submodule pointing to [photovault-api-contract](https://github.com/Jarkendar/photovault-api-contract). It is the single source of truth for the API shape. Key files:

- `contract/openapi.yaml` — machine-readable spec
- `contract/api.md` — narrative documentation including design rationale

The API is versioned at `/v1/`. All resources use opaque string IDs with type prefixes (`photo-*`, `tag-*`, `cat-*`, `upload-*`, `user-*`, `label-*`).

### API domain summary

| Resource | Notes |
|---|---|
| `POST /v1/auth/login` | Returns `{accessToken, refreshToken}` JWT pair. Public (no auth). |
| `GET /v1/health` | Public health check. |
| `GET/PATCH/DELETE /v1/photos/{id}` | Photo metadata. PATCH uses set-replace semantics for tagIds/categoryIds/labelIds. |
| `GET /v1/photos/{id}/thumbnail\|medium\|original` | Binary image responses. thumbnail/medium return 423 while `processingStatus == processing`. |
| `GET/POST/PATCH/DELETE /v1/tags` | User-defined `#`-prefixed tags. |
| `GET/POST/PATCH/DELETE /v1/categories` | User-defined categories with `colorHex`. |
| `GET /v1/labels` | System-defined labels (read-only set: red/orange/yellow/green/blue/purple). |
| `POST /v1/uploads` | Multipart upload; server responds 202 immediately, processes async. Client polls `GET /v1/uploads/{id}` until `done` or `failed`. |
| `GET /v1/users` | Read-only user list. Accounts created administratively only. |

### Error model

All errors use RFC 7807 (`application/problem+json`) with a `type` URI using slug `https://photovault.local/errors/<slug>`. Validation errors (400) include an `errors` map of field → string[].

### Pagination

Only `GET /v1/photos` is paginated. Uses opaque cursor encoding `{uploadedAt, id}` — stable under concurrent inserts. No `totalCount` by design.

### Upload lifecycle

```
created → uploading → processing → done
                   ↘   ↘
                     failed / cancelled
```

`photoId` is set on the `UploadDto` only when `status == done`.

## Project structure

Base package: `dev.jskrzypczak.photovault.server` → `src/main/kotlin/dev/jskrzypczak/photovault/server/`

```
Application.kt          entry point — Application.module(), manual service wiring + plugin install order
plugins/
  Routing.kt            central mount: routing { route("/v1") { …all xxxRoutes() } }
  Security.kt           JWT "auth-jwt" plugin + problem+json challenge
  Database.kt           HikariCP + Database.connect + initDatabase()
  StatusPages.kt        exception → RFC 7807 mapping (all errors go here)
  Serialization.kt      JSON ContentNegotiation
  Monitoring.kt         CallLogging
routes/                 HTTP-only Route.xxxRoutes(service) — parse params, call.receive/respond, no logic
  AuthRoutes / PhotoRoutes / TagRoutes / CategoryRoutes / LabelRoutes / HealthRoutes
  (UploadRoutes lives in uploads/ slice, not here)
auth/                   AuthService (DB-backed), JwtService, JwtConfig
metadata/               TagService, CategoryService, LabelService (CRUD + validation)
photos/                 PhotoService (cursor pagination, batch metadata fetch), Cursor.kt
uploads/                vertical slice: UploadRoutes + UploadService (async pipeline) + dto/
storage/                PhotoAssetStorage (filesystem, path-traversal guard, AssetVariant enum), StorageConfig
dto/                    @Serializable request/response classes (AuthDtos, PhotoDtos, MetadataDtos, …)
errors/                 ApiException + respondProblem() helper
db/
  DatabaseInit.kt       schema create, idx_photos_cursor index, seed labels + admin user
  tables/               Exposed Table objects incl. junctions PhotoTags / PhotoCategories / PhotoLabels
src/main/resources/     application.yaml (port, db.*, jwt.*, photovault.storage.*), logback.xml
```

## Conventions

- **Routes:** `XxxRoutes.kt` → `fun Route.xxxRoutes(service)`. HTTP layer only — no business logic.
- **Services:** `XxxService.kt` → `class XxxService(deps)`. All DB access inside Exposed `transaction { }`. **Validation lives in services**, not routes; throws `ApiException("validation-failed", 400)` with a `field → messages` map.
- **DTOs:** `@Serializable`. Grouped by area (`PhotoDtos.kt`, `MetadataDtos.kt`, `AuthDtos.kt`). Type names end in `Dto`, request bodies end in `Request`.
- **DB tables:** `db/tables/Xxx.kt`, `object Xxx : Table("snake_case_name")`.
- **Config:** `XxxConfig.kt` with `companion object { fun from(config: ApplicationConfig) }`.
- **Errors:** throw `ApiException` anywhere; `StatusPages.kt` centralises mapping to `application/problem+json`.
- **No DI framework** — services wired by hand in `Application.module()`.
- **Test seam:** only `PhotoAssetStorage` is injected (tests use temp dir). No in-memory fakes elsewhere; tests hit a real DB via `testApplication`.

## Tests

All tests in `src/test/kotlin/dev/jskrzypczak/photovault/server/` — flat package, no sub-dirs. One file per concern.

Photo tests are split across three files: `PhotoRoutesTest` (list/get), `PhotoPatchDeleteRoutesTest`, `PhotoAssetRoutesTest` — check all three when touching photo-related code.
