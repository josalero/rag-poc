# RAG POC – How to run

Technical docs: see [docs/README.md](./docs/README.md)

## Store: in-memory (default) or PostgreSQL

Set **`RAG_STORE`** to choose where embeddings are stored:

| Value      | Store              | Database     | Persistence        |
|-----------|--------------------|-------------|--------------------|
| `memory`  | In-memory (default)| H2 in-memory| Lost on restart    |
| `postgres`| pgvector           | PostgreSQL  | Persistent         |

- **`RAG_STORE=memory`** (or unset): no Postgres needed. Uses H2 + `InMemoryEmbeddingStore`. Ideal for local try-out; re-run ingest after each restart.
- **`RAG_STORE=postgres`**: uses PostgreSQL with the **pgvector** extension. Set `DB_URL`, `DB_USER`, `DB_PASSWORD` (see below). Flyway creates the `document_embeddings` table on startup.

---

## 1. Resumes (PDFs) on disk

The backend reads PDFs from a folder (default: `./downloaded-resumes`). Put PDF resumes there (or set `RESUMES_PATH`). To generate 20 mock PDFs into that folder:

```bash
./gradlew :be:generateMockResumes
```

---

## 2. Environment variables

**Required for all modes:**

```bash
export OPENROUTER_API_KEY="your-openrouter-api-key"
```

**For in-memory (default):** nothing else. Optional: `RESUMES_PATH`, `OPENROUTER_MODEL`, `OPENROUTER_EMBEDDING_MODEL`.

**For PostgreSQL:** set `RAG_STORE=postgres` and DB connection:

```bash
export RAG_STORE=postgres
export DB_URL="jdbc:postgresql://localhost:5433/rag?currentSchema=public"
export DB_USER=postgres
export DB_PASSWORD=postgres
```

Optional for all:

- `RESUMES_PATH` – folder with PDF resumes (default: `./downloaded-resumes`)
- `OPENROUTER_MODEL` – chat model (default: `openai/gpt-4o-mini`)
- `OPENROUTER_EMBEDDING_MODEL` – embedding model (default: `openai/text-embedding-3-small`)
- `OPENROUTER_EMBEDDING_TIMEOUT_SECONDS` – embedding request timeout in seconds (default: `45`)
- `OPENROUTER_EMBEDDING_MAX_RETRIES` – embedding request retries (default: `0`)
- `OPENROUTER_EMBEDDING_MAX_SEGMENTS_PER_BATCH` – embedding batch size per request (default: `8`)
- `RAG_MAX_RESULTS` – max retrieved chunks used for answer generation (default: `50`)
- `RAG_MAX_ALLOWED_RESULTS` – hard upper bound accepted from per-query `maxResults` (default: `200`)
- `RAG_MIN_SCORE` – minimum similarity score to keep a chunk as context (default: `0.75`)
- `RAG_NO_RESULTS_ANSWER` – fallback answer when no chunks pass filtering (default: `I couldn't find relevant information in the ingested resumes.`)
- `INGEST_CONCURRENT_FILES` – max files ingested in parallel per folder run (default: `4`)
- `INGEST_VIRTUAL_THREADS_ENABLED` – run ingestion workers on virtual threads (default: `true`)
- `INGEST_LLM_ENRICHMENT_ENABLED` – enable hybrid LLM candidate enrichment during ingest (default: `true`)
- `INGEST_LLM_ENRICHMENT_MAX_CHARS` – max normalized resume chars sent to enrichment model (default: `8000`)

---

## 3. PostgreSQL + pgvector (only when `RAG_STORE=postgres`)

You need a Postgres 16 database with the **pgvector** extension.

**Option A – Docker:**

```bash
docker run -d \
  --name postgres-rag \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=rag \
  -p 5433:5432 \
  pgvector/pgvector:pg16
```

Then:

```bash
docker exec -it postgres-rag psql -U postgres -d rag -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

**Option B – Existing Postgres:** install pgvector, then in the `rag` database run:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Flyway will create the `document_embeddings` table on first startup.

---

## 4. Start the backend (serves API + frontend)

From the project root:

```bash
./gradlew :be:bootRun
```

The app listens on **http://localhost:8084**.

**Swagger UI:** http://localhost:8084/swagger-ui.html  
**OpenAPI JSON:** http://localhost:8084/v3/api-docs

---

## 5. Ingest resumes into the vector store

**Web UI (recommended):** Open **http://localhost:8084**, go to the **Ingest** tab, and click **Run ingestion**. The UI streams progress and lists each file as it’s ingested or skipped. **Only `.pdf` files are accepted**; non-PDF files are skipped with a clear reason. The ingest flow also detects duplicate resume content (via hash) and merges duplicates into one candidate profile instead of indexing duplicate embeddings.

During ingest, candidate extraction runs in **hybrid mode**:
- Deterministic parsing for core fields (email, phone, links, skill signals, hashes).
- Optional LLM enrichment for difficult fields (full name, summary, skill ranking, role hints).
- Automatic fallback to deterministic extraction when enrichment fails/timeouts.
- Versioned provenance saved per ingest (`extractionMethod`, `normalizedContentHash`, `fieldConfidence`, `fieldEvidence`, `validationWarnings`).

Folder ingestion also supports **parallel virtual-thread execution** for faster throughput on network-bound embedding calls:
- Worker count is controlled by `INGEST_CONCURRENT_FILES`.
- Virtual threads can be toggled with `INGEST_VIRTUAL_THREADS_ENABLED`.
- Duplicate content hashes are coordinated so only one same-content file is embedded at a time.

**API (one-shot):**

```bash
curl -X POST http://localhost:8084/api/ingest
```

You should get something like `{"documentsProcessed": 5}`. If the folder is empty or missing, you get `0`. Only `.pdf` files are processed.

**API (streaming):** `POST /api/ingest/stream` returns Server-Sent Events so you can see progress per file. The frontend uses this for the Ingest tab.

---

## 6. Use the query

**Option A – Web UI**

Open **http://localhost:8084** in a browser. The app now has tabs for **Query**, **Candidates**, **Compare**, **Ingest**, and **Audit**.

**Option B – API (curl)**

```bash
curl -X POST http://localhost:8084/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "Who has experience with Java?", "maxResults": 100, "minScore": 0.2, "page": 1, "pageSize": 10}'
```

Response shape: `{"answer":"...","sources":[...],"page":1,"pageSize":10,"totalSources":42}`.
Each source includes:
- `score` (similarity)
- `rank` (global rank in the retrieval result set)
- `candidateId` (for candidate details links)
The `answer` text is sectioned (`ANSWER`, `KEY_FINDINGS`, `LIMITATIONS`, `NEXT_STEPS`) so the UI can render it in a structured way.

Notes:
- `maxResults`, `minScore`, `page`, and `pageSize` are optional per-query overrides.
- Returned sources are still similarity-ranked retrieval results (`top-k`), so "all qualifying" depends on the requested `maxResults` and the configured `RAG_MAX_ALLOWED_RESULTS`.

**Option C – Swagger**

Open **http://localhost:8084/swagger-ui.html**, find **POST /api/query**, click “Try it out”, set the body to `{"question": "Your question here"}`, then “Execute”.

---

## 7. Extra API endpoints

- `GET /api/resumes/{filename}` – view resume PDF inline
- `GET /api/resumes/{filename}?download=true` – download resume PDF
- `GET /api/candidates` – candidate directory (`search`, `skill`, `location`, `sort`, `page`, `pageSize`)
- `GET /api/candidates/{id}` – candidate details
- `GET /api/ingest/audit` – ingestion run history
- `POST /api/query/feedback` – submit helpful/not-helpful feedback for an answer
- `GET /api/query/feedback/stats` – feedback aggregate stats

## Build pipeline behavior

Frontend assets are built and copied into backend static resources as part of normal backend builds:

- Root tasks:
  - `npmInstallFe` – installs frontend dependencies.
  - `buildFe` – builds `fe/dist`.
  - `copyFeToBe` – copies `fe/dist` into `be/src/main/resources/static`.
- `:be:processResources` depends on `copyFeToBe`, so `./gradlew :be:bootRun` and `./gradlew :be:build` include the FE dist automatically.

## Refactoring steps for large classes

When a class grows past ~400 lines, use this cleanup sequence:

1. Identify responsibilities mixed in one file (parsing, scoring, transport, UI state, etc.).
2. Extract pure logic first into utility classes (no Spring wiring), then inject/use them from the service/controller.
3. Keep orchestration in the main service and move low-level parsing/normalization to helpers.
4. Add regression tests before/after extraction to lock behavior.
5. Re-run `./gradlew :be:test` and `npm --prefix fe run build` after each major extraction.

Current extraction examples:
- `CandidateNameExtractor` for name parsing/normalization heuristics.
- `CandidateContactExtractor` for email/phone/url parsing and sanitization.

## Quick checklist

| Step | In-memory (`RAG_STORE=memory` or unset) | PostgreSQL (`RAG_STORE=postgres`) |
|------|----------------------------------------|-----------------------------------|
| 1    | (none)                                 | Postgres 16 + pgvector (e.g. Docker), `CREATE EXTENSION vector` |
| 2    | PDFs in `./downloaded-resumes` (or `RESUMES_PATH`) | Same |
| 3    | `OPENROUTER_API_KEY`                   | `OPENROUTER_API_KEY` + `DB_URL`, `DB_USER`, `DB_PASSWORD` |
| 4    | `./gradlew :be:bootRun`                | Same |
| 5    | `curl -X POST http://localhost:8084/api/ingest` or use Ingest tab | Same |
| 6    | Open http://localhost:8084 and query   | Same |

---

## Troubleshooting

- **“extension vector is not available”** – You are using `RAG_STORE=postgres` but pgvector is not installed or not created in the DB. See section 3.
- **“API key is required”** – Set `OPENROUTER_API_KEY`.
- **“documentsProcessed”: 0** – No PDFs in the resumes folder, or path wrong; check `RESUMES_PATH` and that the folder exists and contains `.pdf` files.
- **Port in use** – Change `server.port` in `be/src/main/resources/application.yml` or set `SERVER_PORT`.
