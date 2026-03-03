# System Architecture

## Runtime topology

```mermaid
flowchart LR
    FE["Frontend (Vite + React + AntD/Tailwind)"] --> API["Spring Boot API (:8084)"]

    API --> Q["RagService (query + ranking + explainability)"]
    API --> ING["ResumeIngestionService (folder/upload + stream/job)"]
    API --> CAND["CandidateProfileService (in-memory directory + versions)"]
    API --> AUD["IngestAuditService"]
    API --> OBS["ObservabilityService"]
    API --> FEED["QueryFeedbackService"]

    ING --> EMB["EmbeddingStoreIngestor"]
    Q --> EMQ["EmbeddingModel"]
    Q --> CHAT["ChatModel"]
    ING --> EMBSTORE["EmbeddingStore<TextSegment>"]
    Q --> EMBSTORE

    EMBSTORE --> MEM["InMemoryEmbeddingStore (memory profile)"]
    EMBSTORE --> PG["PgVectorEmbeddingStore (postgres profile)"]
```

## Primary backend modules

```mermaid
flowchart TB
    C1["/api/query"] --> S1["SkillsQueryController"]
    S1 --> RAG["RagService"]

    C2["/api/ingest, /api/ingest/stream, /api/ingest/upload"] --> IC["IngestController"]
    IC --> IS["ResumeIngestionService"]
    IC --> JS["IngestJobService"]

    C3["/api/candidates"] --> CC["CandidateController"]
    CC --> CPS["CandidateProfileService"]

    C4["/api/query/feedback"] --> FC["QueryFeedbackController"]
    FC --> FS["QueryFeedbackService"]

    C5["/api/metrics/summary"] --> MC["ObservabilityController"]
    MC --> MS["ObservabilityService"]
```

## Data planes

- Retrieval plane:
  - Segment embeddings are stored in `EmbeddingStore<TextSegment>`.
  - Query path performs vector retrieval, hybrid rescoring, deduplication, and answer generation.
- Candidate profile plane:
  - Candidate profile state is maintained in `CandidateProfileService` in memory.
  - Per-ingest snapshots are persisted as `CandidateProfileVersion` records inside the profile object.
  - Snapshot includes provenance fields (`extractionMethod`, normalized hash, confidence/evidence maps, warnings).

## Control/telemetry planes

- Ingest audit:
  - `IngestAuditService` captures per-run file events and run summaries.
- Metrics:
  - `ObservabilityService` tracks query/ingest counters and extraction quality counters.
- Feedback:
  - `QueryFeedbackService` stores answer feedback and recommends dynamic min-score thresholds.

## Deployment profiles

- `RAG_STORE=memory`:
  - Fast local iteration, non-persistent embeddings.
- `RAG_STORE=postgres`:
  - Persistent embeddings via pgvector, Flyway-managed schema.
