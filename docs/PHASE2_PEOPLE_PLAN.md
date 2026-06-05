# Plan — Categorizer Phase 2: People (face recognition)

## Context

Milestone 2 of the categorizer introduces an **identity signal** — recognising specific people
(family, `#grandma`, friends) in photos already stored in PhotoVault. Phases 0 and 1 are
complete: we have a `source`-aware assignment model (`manual > denied > auto`), a nightly Pi job
(`categorize.py`) that computes CLIP vectors and assigns visual tags/categories, and on-demand
PC scripts (`bulk_embed.py`, `add_label.py`).

Faces are structurally different from CLIP: **one photo has many faces**, each gets its own
ArcFace vector, and similar vectors are grouped into **clusters** = person candidates. The
human labels a cluster once ("this is grandma"), and subsequent nightly passes automatically
attach new photos of that person. Faces are sensitive data — labelling goes through a
**separate admin API** consumed by a standalone web/desktop tool, **not** the main Android app.

Outcome: after Phase 2 is deployed, new photos of a previously labelled person automatically
receive their tag/category without any network retraining — just like in Phase 1 where adding
a label triggers a re-score of cached vectors.

## Decisions (agreed with user)

| Choice | Decision |
|---|---|
| Face storage | `faces` table in PostgreSQL (metadata) + `faces-<model>.npz` file (vectors). Consistent with Phase 0 (additive schema) and Phase 1 (npz for embeddings). |
| Labelling | **Admin API** in the Ktor server (contract + routes + DTOs). **No** changes to the Android app — consumer is a separate web/desktop tool. Endpoints isolated via JWT admin role. |
| Model | InsightFace **buffalo_l** (SCRFD-10G detector + ArcFace R50 recogniser → 512-d vector), ONNX, local inference. |
| Scope | Full Phase 2 in **3 iterations** (same pattern as Phase 1). |

## Architecture overview

```
PC/RTX (on-demand):  detect_faces.py  →  cluster_faces.py  →  [admin labels cluster via API]
Pi 5 (nightly):      categorize.py  = CLIP embed+score (done)  + face detection (Iter 1)  + identity matching (Iter 3)

Storage:   PostgreSQL: faces, face_clusters  (metadata)
           file:       faces-buffalo_l.npz   (face vectors, keyed by face-<uuid>)
Person prototypes:     computed on-the-fly from vectors of labelled clusters (like CLIP prototypes) — no separate store.
```

Bounding boxes are computed and stored in `medium.jpg` pixel coordinates (the same asset
already used by Phase 1). The web admin tool fetches `medium` and crops to the bbox.

---

## Iteration 1 — Face detection + store

Goal: every photo (nightly delta + bulk backfill on PC) has its faces detected and stored in
DB + npz. No clustering or identity assignment yet.

### Server (Kotlin, additive — `SchemaUtils.createMissingTablesAndColumns` picks it up)
- **`db/tables/Faces.kt`** — `object Faces : Table("faces")`:
  `id varchar(64) PK` (`face-<uuid>`), `photoId` → `Photos.id` `onDelete CASCADE`,
  `bboxX/bboxY/bboxW/bboxH integer` (medium pixels), `detScore double`,
  `clusterId varchar(64)?` (FK to `face_clusters` added in Iter 2; nullable for now),
  `embeddingRun varchar(128)?`, `detectedAt timestamp`, `faceModel varchar(128)`.
- **`db/tables/Photos.kt`** — two new columns analogous to `embeddedAt`/`embeddingModel`:
  `facesDetectedAt timestamp?`, `faceDetectionModel varchar(128)?`.
- **`db/DatabaseInit.kt`** — add `Faces` to `allTables`; index `idx_faces_photo` on `photoId`,
  `idx_faces_cluster` on `clusterId`.

### Categorizer (Python, following Phase 1 patterns)
- **`requirements.txt` / `pyproject.toml`** — add `insightface`, `onnxruntime` (Pi/CPU);
  `onnxruntime-gpu` optional on PC. **Download buffalo_l model pack at image build time**
  (set `INSIGHTFACE_HOME`, download in `Dockerfile`) — runtime must work without network.
- **`config.py`** — `FACE_MODEL_ID = "buffalo_l"`, `face_store_dir`, and thresholds:
  `FACE_DET_THRESH` (detector confidence), `FACE_MIN_PX` (reject faces < ~40 px), `FACE_DET_SIZE`.
- **`face_model.py`** (analogue of `model.py`) — `load_face_app(device)` → `insightface.app.FaceAnalysis`
  with CUDA/CPU providers; `detect_and_embed(app, image_path) -> list[FaceDetection]`
  (`bbox`, `det_score`, `normed_embedding` 512-d L2-norm). Filters small/low-confidence faces.
- **`face_store.py`** (analogue of `store.py`) — `FaceStore` npz keyed by `face_id`
  (`upsert`, `prune(valid_face_ids)`, `get_vec`, `matrix`).
- **`db.py`** extensions — `fetch_face_detection_backlog` (`faces_detected_at IS NULL OR face_model != FACE_MODEL_ID`,
  `medium_path NOT NULL`), `insert_faces(conn, photo_id, detections) -> face_ids` (generates `face-uuid`),
  `mark_faces_detected(photo_ids, model, now)`, `fetch_all_face_ids` (for npz prune).
- **`cli/detect_faces.py`** (analogue of `bulk_embed.py`) — bulk backfill on PC: backlog → detect
  → insert `faces` rows + vectors to npz, set `photos.faces_detected_at`/`face_detection_model`.
  Chunked, resumable, uses `with_lock()`.
- **`cli/categorize.py`** — add face detection pass to the nightly run (after CLIP),
  inside the same per-photo transaction that flips `processing_status='ready'`. Identity matching = Iter 3.

### Tests
- Categorizer unit (no DB/model): `test_face_store.py` (upsert/prune/matrix).
- Server: `FacesSchemaTest` or extend existing — table is created, cascade on photo delete clears `faces`.

---

## Iteration 2 — Clustering + admin labelling API

Goal: group unlabelled faces into clusters and allow labelling a cluster as a person via API.

### Categorizer
- **`requirements`** — `scikit-learn` (DBSCAN, cosine metric; L2-normed ArcFace → distance threshold ~0.4, tune on real data).
- **`cli/cluster_faces.py`** (on-demand PC) — load face vectors with `cluster_id IS NULL`
  (labelled clusters are frozen), run DBSCAN → for each cluster create a `face_clusters` row
  (`fcluster-uuid`, `face_count`, `representative_face_id` = face with highest `det_score`),
  set `faces.cluster_id`. Noise points (label −1) remain unassigned.

### Server (Ktor) — labelling via API
- **`db/tables/FaceClusters.kt`** — `id varchar(64) PK` (`fcluster-<uuid>`),
  `tagId varchar(64)?` → `Tags.id`, `categoryId varchar(64)?` → `Categories.id` (null = proposal),
  `representativeFaceId varchar(64)?`, `faceCount int`, `createdAt timestamp`.
  Add to `allTables`; add FK from `Faces.clusterId` to `FaceClusters`.
- **`faces/FaceService.kt`** + **`routes/FaceRoutes.kt`**
  mounted in **`plugins/Routing.kt`** under the **admin** path:
  - `GET  /v1/admin/face-clusters?labeled=false` — list cluster proposals (`id`, `faceCount`, representative `photoId`+bbox).
  - `GET  /v1/admin/face-clusters/{id}/faces` — faces in cluster (`faceId`, `photoId`, bbox) for preview/crop.
  - `POST /v1/admin/face-clusters/{id}/label` `{tagId?|categoryId?}` — maps cluster to a person:
    sets `auto_enabled=true` on the tag/category **and writes `photo_tags`/`photo_categories`
    `source='auto'` rows** for all photos with a face in the cluster, **with the same precedence
    semantics** as `write.py` (raw `INSERT ... ON CONFLICT DO UPDATE ... WHERE source='auto'` via Exposed `exec`).
  - `DELETE /v1/admin/face-clusters/{id}` — mark cluster as "not a person" (skipped on re-clustering).
- **`dto/FaceDtos.kt`** — `@Serializable` `FaceClusterDto`, `FaceDto`, `LabelClusterRequest`.
- **Admin role in JWT** — extend `auth/JwtService.kt` with role claim and add guard
  (`requireAdmin`) in `FaceRoutes`, so regular app tokens cannot access `/v1/admin/`.
- **`contract/` (submodule)** — PR with new endpoints in `openapi.yaml` + description in `api.md`
  (section "admin / faces", note that it is outside the scope of the mobile client).

### Tests
- Categorizer: `test_clustering.py` (DBSCAN on synthetic vectors → correct groups/noise).
- Server: `FaceRoutesTest` (testApplication, real DB) — list clusters, `label` writes junction rows
  with `source='auto'` and does not overwrite `manual`/`denied`, admin guard rejects regular token (403).

---

## Iteration 3 — Nightly identity matching

Goal: new photos of an already-labelled person automatically receive their tag/category without human input.

### Categorizer (`cli/categorize.py`)
- **Build identity prototypes** (analogue of `prompts.build_prototypes`): for each labelled
  `face_clusters` (has `tag_id`/`category_id`) gather its face vectors from npz → mean + L2 norm
  → `{person_label_id: prototype}`. Computed on-the-fly each nightly run (like CLIP prototypes) — no separate store.
- **Matching pass** for newly detected faces from the delta (detection from Iter 1 already saved them):
  cosine similarity face vs prototypes; when max ≥ `FACE_MATCH_THRESHOLD` (~0.5, tune on real data) →
  `write.assign_auto(...)` with the person's tag/category (score = similarity, `manual`/`denied` precedence preserved).
- Unmatched faces remain `cluster_id IS NULL` → picked up by the next `cluster_faces.py` run for human review.
- **Extended summary log**: `faces detected: N, faces matched: M, new unlabeled faces: K`.
- **`config.py`** — `FACE_MATCH_THRESHOLD`.

### Tests
- Categorizer: `test_face_matching.py` — face near prototype → match; far away → no match; denied respected.

---

## Key files (summary)

New (categorizer): `face_model.py`, `face_store.py`, `face_match.py`, `cli/detect_faces.py`, `cli/cluster_faces.py`;
extensions: `config.py`, `db.py`, `cli/categorize.py`, `requirements.txt`, `pyproject.toml`, `Dockerfile`.
Reused unchanged: `lock.py`, `write.assign_auto` (precedence), `embed.resolve_path` (path-traversal guard).

New (server): `db/tables/Faces.kt`, `db/tables/FaceClusters.kt`, `faces/FaceService.kt`,
`routes/FaceRoutes.kt`, `dto/FaceDtos.kt`, `auth/AdminGuard.kt`; changes: `db/tables/Photos.kt`,
`db/DatabaseInit.kt`, `plugins/Routing.kt`, `auth/JwtService.kt`, `contract/openapi.yaml`, `contract/api.md`.

Documentation updates: `CATEGORIZER.md` (tick off Phase 2 checklist), `categorizer/README.md`.

## Milestone — CLI smoke test (no application required)

Before the admin API UI is ready, the entire Phase 2 pipeline can be verified and used via
the command line and SQL alone. The scenario below is complete and requires neither the Android
app nor any web tool.

### Prerequisites
- Ktor server running (DB with `faces` table, `photos.faces_detected_at` columns)
- `.env` with `DB_URL`, `PHOTO_STORAGE_ROOT`, `VECTOR_STORE_DIR`, `FACE_STORE_DIR`, `INSIGHTFACE_HOME`
- `buffalo_l` downloaded: `python -c "from insightface.app import FaceAnalysis; FaceAnalysis(name='buffalo_l', providers=['CPUExecutionProvider']).prepare(ctx_id=0)"`
- Photos uploaded via the upload API (status `ready`)

### Steps

```bash
# 0. Optional: bulk CLIP embedding (for new photos)
python -m photovault_categorizer.cli.bulk_embed

# 1. Bulk face detection across the entire library (PC/RTX or Pi)
python -m photovault_categorizer.cli.detect_faces
# Verify: SELECT count(*) FROM faces;
# Verify: SELECT faces_detected_at, face_detection_model FROM photos LIMIT 5;

# 2. Group faces into clusters (Iter 2 — run after deploying cluster_faces.py)
python -m photovault_categorizer.cli.cluster_faces
# Verify: SELECT id, face_count, representative_face_id FROM face_clusters;
# Verify: SELECT cluster_id, count(*) FROM faces GROUP BY cluster_id ORDER BY count DESC;

# 3. Inspect faces in a chosen cluster (fetch bbox + photoId from SQL)
#    and decide: "which cluster is grandma?"
psql $DB_URL -c "
  SELECT f.id, f.photo_id, f.bbox_x, f.bbox_y, f.bbox_w, f.bbox_h, f.det_score
  FROM faces f
  WHERE f.cluster_id = 'fcluster-<uuid>'
  ORDER BY f.det_score DESC LIMIT 10;"

# 4. Label cluster — directly via SQL (bridge until admin API is deployed)
#    Map cluster to tag or category and insert source='auto' junction rows:
psql $DB_URL <<'SQL'
-- a) Enable auto-assignment on the person's tag
UPDATE tags SET auto_enabled = true WHERE id = 'tag-grandma';

-- b) Assign the tag to all photos containing a face from this cluster
INSERT INTO photo_tags (photo_id, tag_id, score, source, embedding_run)
SELECT DISTINCT f.photo_id, 'tag-grandma', f.det_score, 'auto', 'manual-label-cli'
FROM faces f
WHERE f.cluster_id = 'fcluster-<uuid>'
ON CONFLICT (photo_id, tag_id) DO UPDATE
    SET score = EXCLUDED.score,
        embedding_run = EXCLUDED.embedding_run
WHERE photo_tags.source = 'auto';
SQL

# 5. Nightly run (or manual test) — upload a new photo of the same person,
#    wait for status 'pending_categorization', then:
python -m photovault_categorizer.cli.categorize
# Check log: "faces detected: N, faces matched: M, ..."
# Check DB:  SELECT source, score FROM photo_tags WHERE photo_id = '<new-id>';
```

### Notes
- Steps 2–3 (`cluster_faces.py`) and step 5 (nightly identity matching) are available after Iter 2/3.
- Step 4 (raw SQL) is a bridge before the admin API is deployed — same `ON CONFLICT` semantics as `write.py`.
- Once the admin API (Iter 2) is deployed, replace step 4 with: `curl -X POST /v1/admin/face-clusters/{id}/label ...`

---

## End-to-end verification (on dev)

1. **Schema** — start server; verify that `faces`/`face_clusters` tables and `photos.faces_detected_at`
   columns were created (`\d faces` in psql). Delete a photo → `faces` rows disappear (cascade).
2. **Detection (Iter 1)** — `python -m photovault_categorizer.cli.detect_faces` on a test set
   → rows in `faces` + vectors in `faces-buffalo_l.npz`; `photos.faces_detected_at` set.
3. **Clustering (Iter 2)** — `python -m ...cli.cluster_faces` → `face_clusters` rows, `faces.cluster_id` populated.
4. **Labelling (Iter 2)** — `curl -H "Bearer <admin-token>" POST /v1/admin/face-clusters/{id}/label -d '{"tagId":"tag-grandma"}'`
   → photos in that cluster have `photo_tags source='auto'`; regular token gets 403; manually removed (`denied`) tag does not come back.
5. **Nightly matching (Iter 3)** — upload a new photo of the same person → run `categorize.py`
   → photo receives the person's tag `source='auto'`; log shows `faces matched: 1`.
6. **Tests**: `./gradlew test` (server) and `pytest` (categorizer, unit tests without DB; integration skipped without Postgres).

## Open knobs → concrete tasks in the categorizer

Knobs that must be closed during implementation (not left as "TBD"). Each has a landing place
and a way to verify the decision:

- [ ] **Face detection threshold** (`FACE_DET_THRESH`) and **minimum face size** (`FACE_MIN_PX`) —
  calibrate on real photos (too low = false positives; too high = missed profiles/small faces).
  Document chosen values in `config.py` (comment) + `categorizer/README.md`.
- [ ] **DBSCAN `eps` / `min_samples`** (cosine metric) — tune in Iter 2; record in
  `cli/cluster_faces.py` (constants) and describe in README, since this drives cluster quality.
- [ ] **`FACE_MATCH_THRESHOLD`** (Iter 3) — cosine threshold to accept "same person". Start at ~0.5,
  verify on photos of the same person at different ages/lighting; document in `config.py`.
- [ ] **Multi-cluster per person** — one person may end up in multiple clusters (different age, glasses).
  Person prototype = mean of vectors from **all** clusters mapped to the same `tag/cat id`
  (handle in build-prototypes in Iter 3, not per-cluster).
- [ ] **Re-detection after model bump** — backlog includes `face_model != FACE_MODEL_ID`
  (analogue of `embedding_model` from Phase 1); model bump triggers full `detect_faces.py` rerun. Verify in `db.py`.
- [ ] **Face npz prune** — after photo deletion (DB cascade) `categorize.py` must trim
  `faces-buffalo_l.npz` using `fetch_all_face_ids()` (analogue of CLIP vector prune). Add to nightly run.
- [ ] **Tag vs category convention for persons** — decide (e.g. persons as `#`-tags, or category albums);
  affects which branch of `assign_auto` the label handler picks. Record decision in `CATEGORIZER.md`.
- [ ] **Denied for faces** — when a user removes an auto-assigned person tag, the `denied` tombstone
  on `(photo, tag/cat)` already works (Phase 0). Verify nightly matching (Iter 3) does not bypass it.
- [ ] **`bulk_embed.py` + `detect_faces.py` ordering** — document in README that these are two independent
  passes (CLIP and faces) and both must complete before the first `cluster_faces.py` run.

## API contract and .md file updates — mandatory step

Every externally visible change or pipeline flow change **must** update documentation —
this is part of the definition of done for each iteration, not a separate end-of-milestone task:

- [ ] **API contract** (`contract/openapi.yaml` + `contract/api.md`, submodule, separate PR) — when
  anything visible to a client is added:
  - Iter 2: `/v1/admin/face-clusters` endpoints (GET list, GET faces, POST label, DELETE) + DTO schemas
    + description of admin role and annotation "outside mobile client scope".
  - If `source`/`score`/person membership is ever exposed in the public API — separate entry (internal for now).
  - After merging the contract: bump submodule pointer in `photovault-server` (like "chore: update … submodule pointer" commits).
- [ ] **`CATEGORIZER.md`** — tick off Phase 2 checklist items as they are completed; fill in
  the "Open knobs" section with concrete decisions (thresholds, tag/category convention).
- [ ] **`categorizer/README.md`** — add sections for scripts (`detect_faces.py`, `cluster_faces.py`),
  environment variables (`FACE_*`, `INSIGHTFACE_HOME`), and calibrated threshold values.
- [ ] **`categorizer/.env.example` + `deploy/`** — add new `FACE_*` variables, volume for model pack,
  and confirm the existing timer/systemd covers the nightly face detection pass.

## Risks / decisions

- **Privacy** — `/v1/admin/` must be genuinely isolated (JWT role); the mobile app never receives faces or clusters.
- **Cluster stability** — labelled clusters are frozen; re-running `cluster_faces` only touches `cluster_id IS NULL`.
- **No network at runtime** — buffalo_l pack must be in the image/volume (downloaded at build time), not fetched nightly on the Pi.
- **Who writes junction rows at label** — decision: **server** (Kotlin, raw ON-CONFLICT), so labelling is a single API call;
  precedence is identical to `write.py`. Person prototypes are computed by the categorizer from npz (it has the vectors).
