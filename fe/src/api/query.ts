const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface SourceSegment {
  text: string
  source: string
  score: number
  rank: number
  candidateId: string
}

export interface QueryResponse {
  answer: string
  sources: SourceSegment[]
  page: number
  pageSize: number
  totalSources: number
}

export interface QueryOptions {
  maxResults?: number
  minScore?: number
  page?: number
  pageSize?: number
}

export async function querySkills(question: string, options?: QueryOptions): Promise<QueryResponse> {
  const base = getBaseUrl()
  const body: {
    question: string
    maxResults?: number
    minScore?: number
    page?: number
    pageSize?: number
  } = { question }
  if (options?.maxResults !== undefined) {
    body.maxResults = options.maxResults
  }
  if (options?.minScore !== undefined) {
    body.minScore = options.minScore
  }
  if (options?.page !== undefined) {
    body.page = options.page
  }
  if (options?.pageSize !== undefined) {
    body.pageSize = options.pageSize
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
