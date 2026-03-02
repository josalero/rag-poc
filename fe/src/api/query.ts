const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface SourceSegment {
  text: string
  source: string
  score: number
}

export interface QueryResponse {
  answer: string
  sources: SourceSegment[]
}

export interface QueryOptions {
  maxResults?: number
  minScore?: number
}

export async function querySkills(question: string, options?: QueryOptions): Promise<QueryResponse> {
  const base = getBaseUrl()
  const body: { question: string; maxResults?: number; minScore?: number } = { question }
  if (options?.maxResults !== undefined) {
    body.maxResults = options.maxResults
  }
  if (options?.minScore !== undefined) {
    body.minScore = options.minScore
  }
  const res = await fetch(`${base}/api/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const payload = await res.text().catch(() => '')
    let parsed: { message?: string; error?: string } = {}
    try {
      parsed = payload ? JSON.parse(payload) as { message?: string; error?: string } : {}
    } catch {
      // keep plain-text payload as fallback
    }
    const message = parsed.message ?? parsed.error ?? (payload || `Request failed: ${res.status}`)
    throw new Error(message)
  }
  return res.json()
}
