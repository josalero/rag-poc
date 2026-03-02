# RAG POC – How to run

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
- `RAG_MAX_RESULTS` – max retrieved chunks used for answer generation (default: `50`)
- `RAG_MAX_ALLOWED_RESULTS` – hard upper bound accepted from per-query `maxResults` (default: `200`)
- `RAG_MIN_SCORE` – minimum similarity score to keep a chunk as context (default: `0.0`)
- `RAG_NO_RESULTS_ANSWER` – fallback answer when no chunks pass filtering (default: `I couldn't find relevant information in the ingested resumes.`)

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

**Web UI (recommended):** Open **http://localhost:8084**, go to the **Ingest** tab, and click **Run ingestion**. The UI streams progress and lists each file as it’s ingested or skipped. Ingestion is idempotent: re-running does not duplicate data (existing segments per file are replaced).

**API (one-shot):**

```bash
curl -X POST http://localhost:8084/api/ingest
```

You should get something like `{"documentsProcessed": 5}`. If the folder is empty or missing, you get `0`.

**API (streaming):** `POST /api/ingest/stream` returns Server-Sent Events so you can see progress per file. The frontend uses this for the Ingest tab.

---

## 6. Use the query

**Option A – Web UI**

Open **http://localhost:8084** in a browser. The left-hand menu has **Query** and **Ingest**. Use **Query** to ask questions (e.g. “Who has experience with Java?”). Use **Ingest** to run ingestion (with live file-by-file progress).

**Option B – API (curl)**

```bash
curl -X POST http://localhost:8084/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "Who has experience with Java?", "maxResults": 100, "minScore": 0.2}'
```

Response shape: `{"answer": "...", "sources": [{"text": "...", "source": "filename.pdf"}, ...]}`.
Each source includes a `score` field (similarity, higher is more relevant): `{"text":"...","source":"filename.pdf","score":0.87}`.
The `answer` text is sectioned (`ANSWER`, `KEY_FINDINGS`, `LIMITATIONS`, `NEXT_STEPS`) so the UI can render it in a structured way.

Notes:
- `maxResults` and `minScore` are optional per-query overrides.
- Returned sources are still similarity-ranked retrieval results (`top-k`), so "all qualifying" depends on the requested `maxResults` and the configured `RAG_MAX_ALLOWED_RESULTS`.

**Option C – Swagger**

Open **http://localhost:8084/swagger-ui.html**, find **POST /api/query**, click “Try it out”, set the body to `{"question": "Your question here"}`, then “Execute”.

---

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
