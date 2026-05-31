# PhotoVault Server

Self-hosted Kotlin + Ktor backend for the PhotoVault Android client — a personal,
self-hosted photo library. Implements the REST API defined in
[`photovault-api-contract`](https://github.com/Jarkendar/photovault-api-contract).

## Tech stack

- **Kotlin 2.3.20** · **Ktor 3.5.0** (Netty engine) · **kotlinx.serialization**
- **PostgreSQL** · **Exposed** · **HikariCP**
- **JWT** (`com.auth0:java-jwt`) · **BCrypt** (`at.favre.lib:bcrypt`)
- **Docker** — runs on Raspberry Pi 4/5 (arm64)

## Architecture

The server is a single-module Ktor application using `EngineMain` as the entry point
(reads `src/main/resources/application.yaml` at startup).

| Layer | Location | Purpose |
|---|---|---|
| Plugins | `plugins/` | `ContentNegotiation`, `CallLogging`, `StatusPages`, `Authentication` (JWT), `Routing` |
| Auth | `auth/` | `JwtConfig`, `JwtService` (token signing/verification), `AuthService` (login/refresh/logout/me) |
| Photos | `photos/` | `PhotoService` (list + single-photo queries), `Cursor` (opaque pagination cursor encode/decode) |
| Metadata | `metadata/` | `TagService`, `CategoryService`, `LabelService` (CRUD for metadata resources) |
| Routes | `routes/` | One file per resource (`healthRoutes`, `authRoutes`, `photoRoutes`, `tagRoutes`, …) |
| DTOs | `dto/` | `@Serializable` classes mirroring the contract |
| Errors | `errors/` | `ApiException`, `ProblemDetails`, `respondProblem` |
| DB tables | `db/tables/` | Exposed `Table` objects; schema created on startup via `initDatabase()` |

All API routes live under `/v1/`. Errors follow **RFC 7807** (`application/problem+json`)
with type URIs of the form `https://photovault.local/errors/<slug>`. The full error catalog
(~20 slugs) is documented in [`contract/api.md`](contract/api.md).

The `contract/` directory is a git submodule pointing to
[`photovault-api-contract`](https://github.com/Jarkendar/photovault-api-contract) —
it is the single source of truth for the API shape.

### Authentication

All endpoints except `GET /v1/health`, `POST /v1/auth/login`, and `POST /v1/auth/refresh`
require a JWT bearer token:

```
Authorization: Bearer <accessToken>
```

| Endpoint | Auth | Description |
|---|---|---|
| `POST /v1/auth/login` | public | Validate credentials, receive token pair + user |
| `POST /v1/auth/refresh` | public | Exchange refresh token for a new pair (rotates) |
| `GET /v1/auth/me` | required | Return current user's profile |
| `POST /v1/auth/logout` | required | Revoke the session's refresh token |

Tokens are signed with **HMAC256**. The access token carries a `rti` claim pointing to the
paired refresh token's `jti`, so logout invalidates exactly that one session. Refresh tokens
are stored in the `refresh_tokens` table and can be revoked individually.

### Photos

| Endpoint | Auth | Description |
|---|---|---|
| `GET /v1/photos` | required | Paginated photo list (cursor-based, newest first) |
| `GET /v1/photos/{id}` | required | Single photo by id |
| `GET /v1/photos/{id}/thumbnail` | required | Thumbnail binary (`image/jpeg`) |
| `GET /v1/photos/{id}/medium` | required | Medium binary (`image/jpeg`) |
| `GET /v1/photos/{id}/original` | required | Original binary (photo's own MIME type) |

**Binary asset rules:**

- `thumbnail` and `medium` return **`423 Locked`** (`processing-not-ready`) while
  `processingStatus == "processing"`. Once processing is done they are immutable.
- `original` is **always available** regardless of processing status.
- All three binary endpoints return `404 photo-not-found` when the photo doesn't exist or its
  asset path is not yet set in the database.

**Caching:** binary assets are immutable so they are served with
`Cache-Control: private, max-age=31536000, immutable`. An `ETag` is set on every response;
clients can re-request with `If-None-Match` to receive `304 Not Modified`.

**Storage model:** asset files live on the server's filesystem under a configurable root
(`PHOTO_STORAGE_ROOT`). The database stores only relative paths (e.g. `photo-abc/thumbnail.jpg`);
the server joins them with the root at serve time. In Docker the root is mounted as a named
volume (`photo_storage:/data/photos`); for local dev it defaults to `./data/photos`.

**Query parameters for `GET /v1/photos`:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `cursor` | string | — | Opaque cursor from the previous page response |
| `limit` | int | 30 | Page size (max 100) |
| `q` | string | — | Free-text search across photo name, tag names, category names |
| `tagIds` | csv | — | AND filter — photo must have **all** listed tag ids |
| `categoryIds` | csv | — | AND filter — photo must be in **all** listed category ids |
| `labelIds` | csv | — | AND filter — photo must carry **all** listed label ids |
| `favoritesOnly` | bool | false | Return only favourited photos |
| `uploadedBy` | string | — | Filter by uploader user id; `me` resolves to the authenticated user |

Pagination is cursor-based (`uploadedAt DESC, id DESC`) — stable under concurrent inserts. There is
no `totalCount` by design (see [`contract/api.md`](contract/api.md) for rationale). Tags, categories,
and labels are batch-fetched per page (no N+1). The cursor is base64-url encoded JSON and is opaque to the client.

### Tags

| Endpoint | Auth | Description |
|---|---|---|
| `GET /v1/tags` | required | List all tags with `photoCount`; `?usedOnly=true` filters to assigned only |
| `POST /v1/tags` | required | Create a tag (`name` must start with `#`, unique case-insensitive); 201 + `Location` |
| `PATCH /v1/tags/{id}` | required | Rename a tag; photo links are preserved |
| `DELETE /v1/tags/{id}` | required | Delete a tag; cascades to `photo_tags` join table |

### Categories

| Endpoint | Auth | Description |
|---|---|---|
| `GET /v1/categories` | required | List all categories with `photoCount`; `?usedOnly=true` filters to assigned only |
| `POST /v1/categories` | required | Create a category (`name` + `colorHex` as `#RRGGBB`); 201 + `Location` |
| `PATCH /v1/categories/{id}` | required | Update name and/or colorHex (both optional); photo links preserved |
| `DELETE /v1/categories/{id}` | required | Delete a category; cascades to `photo_categories` join table |

### Labels

| Endpoint | Auth | Description |
|---|---|---|
| `GET /v1/labels` | required | List the six fixed system labels (red/orange/yellow/green/blue/purple) with `photoCount` |

Labels are read-only (no POST/PATCH/DELETE). The fixed set is seeded at startup via `seedLabels()`.

## Running

**With Docker (recommended):**

```bash
docker compose up --build
curl http://localhost:8080/v1/health
# {"status":"ok","version":"1.0.0"}
curl -s -X POST localhost:8080/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"password123"}'
# {"accessToken":"…","refreshToken":"…","user":{…}}
```

**Locally (JVM 21 required):**

```bash
./gradlew run
# or: build first, then run the fat JAR
./gradlew shadowJar
java -jar build/libs/photovault-server.jar
```

Copy `.env.example` to `.env` and customise the values — especially `JWT_SECRET` in
production (generate with `openssl rand -base64 48`).

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/photovault` | JDBC connection URL |
| `DB_USER` | `photovault` | Database user |
| `DB_PASSWORD` | `change-me` | Database password |
| `JWT_SECRET` | `dev-secret-change-me-in-production` | HMAC256 signing secret |
| `JWT_ISSUER` | `photovault` | JWT `iss` claim |
| `JWT_AUDIENCE` | `photovault-client` | JWT `aud` claim |
| `JWT_ACCESS_TTL_MIN` | `60` | Access token lifetime (minutes) |
| `JWT_REFRESH_TTL_DAYS` | `30` | Refresh token lifetime (days) |
| `PHOTO_STORAGE_ROOT` | `./data/photos` | Path to the photo asset directory (set to `/data/photos` in Docker) |

## Development roadmap

| # | Milestone | Status |
|---|---|---|
| 1 | Scaffold + Health + RFC 7807 + Docker | ✅ done |
| 2 | DB layer: Exposed + PostgreSQL + Hikari + migrations | ✅ done |
| 3 | Auth: JWT login/refresh/logout, BCrypt, refresh-token store | ✅ done |
| 4 | Read photos: `GET /v1/photos` (cursor pagination, filters) + `GET /v1/photos/{id}` | ✅ done |
| 5 | Binary assets: thumbnail / medium / original (+ 423 while processing) | ✅ done |
| 6 | Tags / Categories / Labels (full CRUD; labels read-only) | ✅ done |
| 7 | `PATCH /v1/photos/{id}` — favourites + set-semantics for tag/category/label lists | ⬜ |
| 8 | Uploads: multipart 202 + async processing pipeline (Thumbnailator) | ⬜ |
| 9 | Hardening: full error-slug coverage, size/MIME limits, `validation-failed` map | ⬜ |

## License

See [LICENSE](./LICENSE).
