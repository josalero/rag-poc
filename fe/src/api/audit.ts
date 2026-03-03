const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface IngestAuditFileEntry {
  filename: string
  status: 'ingested' | 'skipped'
  reason?: string
  timestamp: string
}

export interface IngestAuditRun {
  id: string
  startedAt: string
  finishedAt: string
  processed: number
  skipped: number
  files: IngestAuditFileEntry[]
}

async function parseError(res: Response): Promise<Error> {
  const payload = await res.text().catch(() => '')
  let message = payload || `Request failed: ${res.status}`
  try {
    const parsed = payload ? JSON.parse(payload) as { error?: string; message?: string } : {}
    message = parsed.message ?? parsed.error ?? message
  } catch {
    // plain text fallback
  }
  return new Error(message)
}

export async function fetchIngestAudit(limit = 20): Promise<IngestAuditRun[]> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/ingest/audit?limit=${encodeURIComponent(String(limit))}`)
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}
