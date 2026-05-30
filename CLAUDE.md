# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Self-hosted Kotlin + Ktor backend for a personal photo library. Implements the REST API defined in the `contract/` git submodule. Intended to run in Docker on a Raspberry Pi; the planned data layer is PostgreSQL + Exposed (not yet added to `build.gradle.kts`).

## Commands

```bash
# Build a fat JAR
./gradlew shadowJar

# Run the server (after build)
java -jar build/libs/photovault-server.jar

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "dev.jskrzypczak.photovault.SomeTest"

# Run a single test method
./gradlew test --tests "dev.jskrzypczak.photovault.SomeTest.methodName"
```

## Architecture

### Tech stack

- **Kotlin 2.3.20** / **JVM 21**
- **Ktor 3.5.0** (Netty engine, `EngineMain` entry point reading `application.yaml`)
- **kotlinx.serialization** for all JSON — `camelCase` field names, no custom adapters needed
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
