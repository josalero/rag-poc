const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface MetricsSummary {
  queryCount: number
  queryErrors: number
  queryErrorRate: number
  avgQueryLatencyMs: number
  avgSourcesPerQuery: number
  ingestRunCount: number
  ingestProcessed: number
  ingestSkipped: number
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

export async function fetchMetricsSummary(): Promise<MetricsSummary> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/metrics/summary`)
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}
