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
| 1 | CLIP visual pipeline | Embedder, vector store, PL→EN prompts, nightly Pi job, bulk embed, add-label backfill, systemd timer | ✅ |
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

- [x] Pick one embedder, same model on both machines:
  - **MobileCLIP-S2** via `open_clip_torch`, pretrained weights `datacompdr`, **fp32**.
  - ONNX export deferred as a future optimisation; Pi is fast enough for the delta.
- [x] Pin preprocessing: inference transform bundled with `open_clip` (center-crop 224 + normalise,
  no augmentation, fp32). Values logged at INFO level on startup.

### Vector store

- [x] File-based `.npz` store keyed by `photo.id` (`photo-<uuid>` strings). No pgvector/Qdrant.
- [x] Store lives on a shared volume. Nightly job self-heals (re-embeds missing vectors) and prunes
  orphans (vectors for deleted photos). Implemented in `categorize.py`.
- [x] Store is **separate per model version** — filename carries `MODEL_ID`
  (`mobileclip-s2-datacompdr.npz`). Bumping the model = full re-embed pass via `bulk_embed.py`.

### PL→EN prompt mapping

- [x] Static mapping file `prompts.yaml` — `"#morze": ["sea", "ocean", "seashore"]`. Fast,
  explicit, no runtime cost. Missing entries are logged as WARNING.
- [x] Prompt template `"a photo of {term}"` (multi-term → averaged prototype).

### RTX on-demand scripts

- [x] `bulk_embed.py` — queries `Photos WHERE embedded_at IS NULL OR embedding_model != MODEL_ID`,
  downloads `medium.jpg` via storage root, encodes in batches of 256, writes to store,
  updates `photos.embedded_at` + `photos.embedding_model`. Resumable (checkpointed per chunk).
- [x] `add_label.py <category-or-tag-id>` — sets `rolled_out = false`, scores all cached vectors
  against the label's text prototype, writes `source = auto` rows (respects `denied` tombstones),
  sets `rolled_out = true`. Guard: requires `auto_enabled = true` and a `prompts.yaml` entry.

### Nightly Pi oneshot

Two work queues per run:

1. **Delta queue** — `photos WHERE processing_status = 'pending_categorization'`
2. **Self-heal queue** — `photos WHERE processing_status = 'ready'` whose vector is absent from
   the store (lost due to store reset or photo deletion + re-upload).

- [x] Container entry point: `python -m photovault_categorizer.cli.categorize`, exits 0 when done.
- [x] **Lock file** (`/tmp/photovault-categorize.lock`) — shared by all CLI entry points via
  `lock.py`; exit 0 immediately if held (does not error the timer).
- [x] Writes `source = auto` rows respecting `manual`/`denied` precedence; flips
  `processing_status` to `ready` and updates `embedded_at`/`embedding_model` in one transaction.
- [x] Summary log: `photos processed: N, tags inserted: M, categories inserted: K, denied skipped: D`.

### Trigger

- [x] `deploy/photovault-categorizer.service` + `deploy/photovault-categorizer.timer` —
  systemd unit + timer (daily at 03:00, `Persistent=true`). See `categorizer/deploy/README.md`.
- [x] Env vars at runtime: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `PHOTO_STORAGE_ROOT`,
  `VECTOR_STORE_DIR`. n8n alternative documented in `deploy/README.md`.

---

## Phase 2 — People

**Signal type:** identity — family members, `#babcia`, friends.
**Technique:** InsightFace **buffalo_l** (SCRFD detector + ArcFace R50, ONNX) + **separate** face vector store (face embeddings live in a different space than CLIP scene embeddings).

**Decisions:**
- [x] Face model: InsightFace **buffalo_l** (SCRFD-10G detector + ArcFace R50 → 512-d L2-norm vector).
- [x] Face store: `faces-buffalo_l.npz` keyed by `face-<uuid>`, separate from CLIP store.
- [x] Face metadata: `faces` table in PostgreSQL (bbox in medium.jpg pixels, det_score, cluster_id, model).
- [x] Labelling flow: **admin API** (`/v1/admin/face-clusters`) — separate web/desktop tool, NOT the Android app.
- [x] Do not mix face vectors into the CLIP store.

### Iteration 1 — Detection + face store

- [x] **`db/tables/Faces.kt`** — `faces` table (face_id PK, photo_id FK→CASCADE, bbox x/y/w/h, det_score, cluster_id nullable, face_model, embedding_run, detected_at).
- [x] **`db/tables/Photos.kt`** — add `faces_detected_at timestamp?` + `face_detection_model varchar(128)?`.
- [x] **`db/DatabaseInit.kt`** — add `Faces` to `allTables`; indexes `idx_faces_photo`, `idx_faces_cluster`.
- [x] **`categorizer/config.py`** — `FACE_MODEL_ID`, `FACE_DET_THRESH`, `FACE_MIN_PX`, `FACE_DET_SIZE`, `FACE_MATCH_THRESHOLD`; extended `Config` with face fields.
- [x] **`categorizer/face_model.py`** — `load_face_app()`, `detect_and_embed()` → `list[FaceDetection]`.
- [x] **`categorizer/face_store.py`** — `FaceStore` npz keyed by face_id (upsert, prune, matrix, get_vec).
- [x] **`categorizer/db.py`** — `fetch_face_detection_backlog`, `insert_faces`, `mark_faces_detected`, `fetch_all_face_ids`.
- [x] **`categorizer/cli/detect_faces.py`** — bulk face detection backfill (PC/RTX): backlog → detect → insert DB rows + npz, chunked, resumable.
- [x] **`categorizer/cli/categorize.py`** — face detection pass added to nightly run (after CLIP); face store prune added.
- [x] **`requirements.txt` / `pyproject.toml`** — `insightface`, `onnxruntime`, `opencv-python-headless`.
- [x] **`Dockerfile`** — system deps for OpenCV; buffalo_l pre-downloaded at build time.
- [x] **`.env.example`** — face pipeline variables documented.

**Open knobs to tune after first real run (document values in README and config.py):**
- [ ] `FACE_DET_THRESH` — calibrate on real photos (default: 0.5)
- [ ] `FACE_MIN_PX` — calibrate minimum face size (default: 40 px)
- [ ] `FACE_MATCH_THRESHOLD` — cosine floor for identity matching (default: 0.5); verify on photos of the same person at different ages/lighting before enabling nightly matching
- [ ] **Tag vs category convention for persons** — decide whether people are modelled as `#`-tags or as category albums; this affects which branch of `assign_auto` the label handler picks; record decision in this file once settled

### Iteration 2 — Clustering + admin API labelling ✅

- [x] **`cli/cluster_faces.py`** — DBSCAN (cosine metric, `eps=0.4`, `min_samples=2`) on unassigned face vectors → `face_clusters` rows + `faces.cluster_id`.
- [x] **`db/tables/FaceClusters.kt`** — server-side table (`fcluster-uuid`, tag_id?, category_id?, representative_face_id, face_count, created_at).
- [x] **`faces/FaceService.kt`** + **`routes/FaceRoutes.kt`** — admin API under `/v1/admin/face-clusters`.
- [x] **`dto/FaceDtos.kt`** — `FaceClusterDto`, `FaceDto`, `LabelClusterRequest`, `FaceClusterListResponse`, `FaceListResponse`.
- [x] **`auth/AdminGuard.kt`** + **`auth/JwtService.kt`** — `role=admin` claim + `requireAdmin` guard in FaceRoutes (regular tokens get 403).
- [x] **`requirements.txt` / `pyproject.toml`** — added `scikit-learn>=1.4.0`.
- [x] **`db.py`** — `fetch_unclustered_faces`, `insert_face_cluster`, `update_faces_cluster_id`.
- [x] **`tests/test_clustering.py`** — 6 unit tests for DBSCAN clustering logic (no DB / model needed).
- [x] **`FaceRoutesTest.kt`** — 12 server integration tests (auth guard, list, faces-in-cluster, label writes source=auto, denied not overwritten, delete).
- [x] **`contract/openapi.yaml` + `contract/api.md`** — PR with admin face endpoints (separate from mobile client scope).

### Iteration 3 — Nightly identity matching ✅

- [x] **`face_match.py`** — `build_identity_prototypes()` (mean + L2 norm per person, multi-cluster aware) and `match_faces()` (cosine dot product against prototype matrix, best-match above threshold).
- [x] **`db.py`** — `fetch_labelled_cluster_faces()` returning `(face_id, label_id, kind)` for all faces in labelled clusters.
- [x] **`cli/categorize.py`** — loads identity prototypes before the work loop; matches each newly-detected face; writes `source='auto'` identity assignments via `assign_auto()` (denied/manual precedence preserved). `_process_photo` now returns 6-tuple including `faces_matched`.
- [x] Extended summary log: `faces detected: N, faces matched: M, new unlabeled faces: K`.
- [x] **`tests/test_face_matching.py`** — 14 pure unit tests for `build_identity_prototypes` and `match_faces` (no DB / model needed).

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
