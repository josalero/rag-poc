# Frontend Routing and Runtime State

## Route model

The frontend uses hash-based routing with a tab-key contract.

Supported tabs:

- `ingest`
- `chat`
- `query`
- `match`
- `candidates`
- `compare`
- `audit`
- `eval`

Route forms:

- `#/chat`
- `#/query`
- `#/candidates`
- `#/candidates?candidate=<id>`

## Navigation state machine

```mermaid
stateDiagram-v2
    [*] --> Boot
    Boot --> HashParse: parse initial hash
    HashParse --> ActiveTab
    ActiveTab --> ActiveTab: tab click -> update hash
    ActiveTab --> ActiveTab: hashchange -> update tab + candidateId
    ActiveTab --> CandidateOpen: openCandidate(candidateId)
    CandidateOpen --> ActiveTab: close modal or tab switch
```

## Candidate refresh policy

- Candidate list refresh is event-driven only:
  - when Candidates tab becomes active.
  - when page/pageSize/sort changes while tab is active.
  - when user clicks explicit Refresh.
- Search/skill text changes alone do not auto-fetch; they apply when user presses `Find`.
- Periodic polling is intentionally disabled for `/api/candidates`.

## Ingest UI execution modes

```mermaid
flowchart TD
    A["Ingest tab"] --> B["Run Configured Server Folder"]
    A --> C["Choose Folder (browser files)"]

    B --> D["POST /api/ingest/stream (SSE)"]
    D --> E["append file events + done"]

    C --> F["batch PDFs (size/count limits)"]
    F --> G["POST /api/ingest/upload"]
    G --> H["merge file events across batches"]
```

## Candidate modal opening paths

- Query page:
  - source row "Candidate" button opens profile via hash route to `#/candidates?candidate=<id>`.
- Candidates page:
  - table row action opens profile and lazy-loads full candidate payload.
  - hash route `#/candidates?candidate=<id>` opens the profile modal directly.
- Compare page:
  - candidate selectors fetch full profile snapshots for side-by-side rendering.
