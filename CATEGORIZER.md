# PhotoVault — Auto-Categorizer

Local, ephemeral, 3-signal ML job that automatically tags and categorizes photos already
stored in PhotoVault. Uses a **frozen embedder + lightweight classifier** so adding a new
label means re-scoring cached embeddings — not retraining a network.

**Machine split:**

| Machine | Role | When |
|---|---|---|
| PC (RTX 2070 Super, 8 GB VRAM) | Bulk CLIP embedding; new-label backfill | On-demand (interactive) |
| Pi 5 (8 GB RAM) | Nightly oneshot — embed delta → score → write → exit, RAM freed | Scheduled (systemd timer / n8n) |

Both machines connect to the same Postgres via **Tailscale**. A **lock file** prevents a
nightly Pi run from colliding with an on-demand PC session. Trigger: `docker run --rm`
(ephemeral container) — nothing stays running between runs.

---

## Milestones

| # | Phase | Scope | Status |
|---|---|---|---|
| 0 | Schema + state model | Additive Exposed columns; `source`-aware read/write paths in the Ktor server | ✅ |
| 1 | CLIP visual pipeline | Embedder export, vector store, PL→EN prompts, nightly Pi job, add-label backfill | ☐ |
| 2 | People | Face model + separate face store, identity clustering, family tags | ☐ |
| 3 | Events | Vacation / trip heuristic from `captured_at` + `lat`/`lng` (no network) | ☐ |

---

## State model

Assignment rows in `photo_tags` / `photo_categories` carry a `source` field.

```
source ∈ { manual, auto, denied }

Precedence: manual > denied > auto
```

| Event | Result |
|---|---|
| User links tag/category in the app | `source = manual` (overrides `denied` if tombstone exists) |
| ML assigns a tag/category | `source = auto`, only if **no row** exists for this `(photo, tag/category)` pair |
| User unlinks any assignment (manual or auto) | Row stays / becomes `source = denied` (tombstone — ML will never re-insert this pair) |
| User manually re-adds after denying | `source = manual` — overrides `denied` |
| Re-scoring run | Updates `score` on **`auto` rows only** — never touches `manual` or `denied` |

`denied` tombstones are **hidden from all API responses** (not visible in `GET /v1/photos/{id}`,
listings, or `photoCount`). They are free hard negatives for future classifier heads.

### Per-label control flags (on `categories` / `tags`)

| Column | Default | Meaning |
|---|---|---|
| `auto_enabled` | `false` | Human switch — may the bot assign this label on its own? Nightly job works `WHERE auto_enabled = true`. |
| `rolled_out` | `true` | `false` = new label awaiting library-wide backfill (RTX job). Flipped to `true` after full pass. |

---

## Phase 0 — Schema & server changes

> All schema changes are **additive** (nullable or defaulted) → picked up automatically by
> `SchemaUtils.createMissingTablesAndColumns` in `db/DatabaseInit.kt` on next boot.
> No Flyway / SQL migration files needed.

### Schema additions

- [x] **`PhotoTags`** (`db/tables/PhotoTags.kt`) — add three columns:
  - `score` `double?` nullable — classifier confidence (null = manually assigned or not yet scored)
  - `source` `varchar(16)` default `"manual"` — `manual | auto | denied` (Kotlin property: `assignmentSource`)
  - `embedding_run` `varchar(128)?` nullable — identifier of the embedding run that produced this assignment (for auditability / re-scoring)
- [x] **`PhotoCategories`** (`db/tables/PhotoCategories.kt`) — same three columns as above
  - (Labels are system-defined and read-only — out of scope for ML assignment for now)
- [x] **`Photos`** (`db/tables/Photos.kt`) — add:
  - `embedded_at` `timestamp?` nullable — when this photo was last embedded
  - `embedding_model` `varchar(128)?` nullable — model id used (e.g. `"mobileclip-s2-onnx"`)
- [x] **`Categories`** (`db/tables/Categories.kt`) — add:
  - `auto_enabled` `bool` default `false`
  - `rolled_out` `bool` default `true`
- [x] **`Tags`** (`db/tables/Tags.kt`) — add:
  - `auto_enabled` `bool` default `false`
  - `rolled_out` `bool` default `true`

### Server read/write path changes

The current `PhotoService.replaceRelation` (`photos/PhotoService.kt:406`) does a blind
`deleteWhere + batchInsert` — a PATCH from the app would wipe ML-written `source`/`score`/`denied` rows.
It must become `source`-aware **before** Phase 1 writes anything.

- [x] **Rework `updatePhoto` / `replaceRelation`** (`photos/PhotoService.kt`):
  - An unlink (id present in current junction but absent from the new list) → **upsert `source = denied`**, do NOT delete the row.
  - A (re-)link (id in new list) → upsert `source = manual`, `score = null`, `embedding_run = null` (overrides existing `denied` or `auto`).
  - IDs not mentioned in the request (`tagIds = null`) → unchanged (existing set-semantics: `null` means "do not touch").
- [x] **Filter `source = 'denied'` from all read paths:**
  - `fetchTagsForPhotos` / `fetchCategoriesForPhotos` — deny filter on both per-photo query and global `photoCount` subquery
  - `getPhoto` single-photo fetch — inherits via the batch helpers
  - `photoCount` sub-queries in `metadata/CategoryService.kt` and `metadata/TagService.kt` — exclude `denied` rows
  - `relationSubquery` (tag/category id filters in `buildFilterPredicate`) — denied rows cannot satisfy a filter
- [x] **`processing_status` transition** — `STATUS_PHOTO_READY = "ready"` constant added in `uploads/UploadService.kt`.
- [x] **Expose `auto_enabled` / `rolled_out` via API** (stretch — done):
  - Surfaced in `CategoryDto` / `TagDto` (required fields).
  - Writable via `PATCH /v1/categories/{id}` / `PATCH /v1/tags/{id}` (all PATCH fields now optional for tags).
  - Contract submodule updated (`contract/openapi.yaml`, `contract/api.md`).
  - `source` and `score` stay server-internal.

---

## Phase 1 — CLIP visual pipeline

**Signal type:** visual appearance — `#sea`, `#bike`, `birds`, food, documents, landscapes.
**Technique:** zero-shot classification from label name (no examples needed) + k-NN once ≥ 5 labeled examples exist.

### Embedder

- [ ] Pick one embedder, same model on both machines:
  - **MobileCLIP-S2** (Apple, ~20 MB ONNX) — Pi-friendly, good accuracy/speed tradeoff
  - **OpenCLIP / SigLIP ViT-B/32** — slightly heavier but wider community support; SigLIP handles multilingual prompts better
  - Export to **ONNX** (identical graph, preprocessing, and floating-point precision on PC and Pi — critical for a shared vector space)
- [ ] Pin preprocessing: `center_crop(224)`, `normalize([0.48..],[0.26..])`, `float32`.
  Document the exact values in the job's `README` / config file.

### Vector store

- [ ] File-based store (`.npy` or `hnswlib` index), keyed by `photo.id` (`photo-<uuid>` strings).
  **No pgvector / Qdrant** — both would require a 24/7 service and break the ephemeral model.
- [ ] Store file lives on a shared volume (or is `rsync`-ed PC↔Pi over Tailscale before the nightly run).
  Nightly job self-heals: re-embeds any photo missing from the store (safe overwrite); prunes vectors
  for photo ids that no longer exist in Postgres (orphan cleanup).
- [ ] Store is **separate per model version** (filename carries model id). Bumping the model = full re-embed pass.

### PL→EN prompt mapping

- [ ] Polish tag/category names → English prompt(s) for the CLIP text encoder.
  Options (pick one or combine):
  - **Static mapping file** (`prompts.yaml`) — `"#morze": ["sea", "ocean", "seashore"]`. Fast, explicit, no runtime cost.
  - **Multilingual SigLIP** — text encoder accepts Polish directly; eliminates the mapping table.
- [ ] Decide and document prompt template (e.g. `"a photo of {label}"`).

### RTX on-demand scripts

- [ ] `bulk_embed.py` — connect to Postgres over Tailscale, load all photos missing from the vector
  store (`Photos.embedded_at IS NULL OR Photos.embedded_at < model_updated_at`), download
  `medium.jpg` via storage root, compute CLIP embedding, write to store, update `photos.embedded_at`
  + `photos.embedding_model`. Batch size tuned to 8 GB VRAM.
- [ ] `add_label.py <category-or-tag-id>` — sets `rolled_out = false`, triggers library-wide
  k-NN / zero-shot scoring pass for that single label, then sets `rolled_out = true`.
  Inserts `source = auto` rows only where no row exists; respects `denied` tombstones.

### Nightly Pi oneshot

Two work queues per run:

1. **Delta queue** — `SELECT id, medium_path FROM photos WHERE processing_status = 'pending_categorization'`
2. **Backfill queue** — `SELECT id FROM categories WHERE rolled_out = false` (already covered above, but Pi can also pick up small backfills)

- [ ] Container entry point: `python categorize.py` (or equivalent), exits with code 0 when done.
- [ ] **Lock file** (`/tmp/photovault-categorize.lock`) acquired at startup, released on exit.
  If lock exists → another run is active → exit 0 immediately (do not error the timer).
- [ ] After scoring, write `auto` rows to `photo_tags` / `photo_categories` for tags/categories
  where `auto_enabled = true`, respecting the `source` precedence rules (skip pairs with existing
  `manual` or `denied` rows).
- [ ] Flip `photos.processing_status` from `pending_categorization` → `ready` in the same transaction
  as the junction inserts.
- [ ] Log a summary line: `photos processed: N, tags inserted: M, categories inserted: K, denied skipped: D`.

### Trigger

- [ ] `systemd.timer` on the Pi — daily at e.g. 03:00, calls `docker run --rm photovault-categorizer`.
  Or n8n flow → same `docker run --rm`.
- [ ] Env vars passed at runtime (same pattern as server): `DB_URL`, `DB_USER`, `DB_PASSWORD`,
  `PHOTO_STORAGE_ROOT`. Reuse `.env` / Docker secrets already in use by the server.

---

## Phase 2 — People

**Signal type:** identity — family members, `#babcia`, friends.
**Technique:** dedicated face detection + recognition model + **separate** vector store (face embeddings live in a different space than CLIP scene embeddings).

- [ ] Pick face model (e.g. InsightFace / ArcFace ONNX) — lightweight enough for Pi inference on small faces.
- [ ] Separate vector store file for face embeddings (e.g. `faces.hnsw`), keyed by a `face-<uuid>` id
  that maps back to a `photo-<uuid>` + bounding box.
- [ ] Face clustering: DBSCAN or agglomerative on face embeddings → proposed identities.
  User labels a cluster → maps to a `tag-*` or `category-*` id.
- [ ] Once labelled: flip `auto_enabled = true` on those person tags/categories.
  Subsequent nightly runs assign new photos matching a known face cluster as `source = auto`.
- [ ] **Do not mix** face vectors into the CLIP store — the two spaces are incompatible.

---

## Phase 3 — Events

**Signal type:** temporal + geographic — vacations, trips, outings.
**Technique:** pure heuristic clustering over `captured_at` + `lat` / `lng` + `place_name`; no network call, no model.

Vacation heuristic definition (to tune):
- Away from home: lat/lng centroid outside a configurable home radius.
- Multi-day: cluster spans ≥ 2 calendar days.
- Minimum photos: ≥ 10 photos in the cluster.

- [ ] SQL / Python script: group photos into temporal clusters (gap > 6 h = new cluster), filter by
  heuristic above, propose a category name (e.g. `"Trip · {place_name} · {year-month}"`).
- [ ] Write proposed categories to `categories` with `auto_enabled = true`, `rolled_out = false`.
  `add_label.py` (Phase 1) then backfills the assignment.
- [ ] Home coordinates stored in job config (env var or config file) — **not** in the database.

---

## Rejected alternatives

| Alternative | Why rejected |
|---|---|
| Per-label fine-tune of the full network | Slow, high VRAM, catastrophic forgetting; re-embed on model bump is already expensive |
| pgvector / Qdrant running 24/7 | Breaks the ephemeral "nothing runs in the background" goal; requires a persistent service |
| Full CLIP embedding on the Pi | ~18 s/photo on Pi 5 CPU → months for a large library; bulk on RTX is the right tool |
| Separate `ml_categories` table | Redundant — the existing `categories` + `photo_categories` tables plus `source` column cover the need |

---

## Open knobs

These are not yet decided — document the decision here when resolved:

- [ ] **Score threshold vs top-k** — per taxonomy level: tags are multi-label (threshold, e.g. score ≥ 0.25);
  album categories are likely top-1/2 (pick the highest-scoring `auto_enabled` category).
- [ ] **API exposure of `source` / `score`** — currently server-internal. Exposing them requires a
  **contract submodule** PR (`contract/openapi.yaml` + `contract/api.md`), then surfacing in DTOs.
  Useful for the Android client to distinguish "bot says" vs "you said".
- [ ] **Denied semantics for system labels** — `PhotoLabels` has no `source` column today. If ML is
  ever allowed to assign color labels (e.g. "red" for documents), the same `source` pattern would apply.
  Deferred until Phase 1 is stable.
- [ ] **`processing_status = 'ready'` as a filter** — the Android client may want to hide `pending_categorization`
  photos from the default gallery until the nightly run completes. Requires a client-side decision, not a server change.
