const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface SourceSegment {
  text: string
  source: string
  score: number
  rank: number
  candidateId: string
  vectorScore: number
  keywordScore: number
  matchedTerms: string[]
  missingTerms: string[]
}

export interface QueryExplainability {
  matchedTerms: string[]
  missingTerms: string[]
  confidenceScore: number
}

export interface QueryResponse {
  answer: string
  sources: SourceSegment[]
  page: number
  pageSize: number
  totalSources: number
  explainability: QueryExplainability
}

export interface QueryOptions {
  maxResults?: number
  minScore?: number
  page?: number
  pageSize?: number
  useFeedbackTuning?: boolean
}

export async function querySkills(question: string, options?: QueryOptions): Promise<QueryResponse> {
  const base = getBaseUrl()
  const body: {
    question: string
    maxResults?: number
    minScore?: number
    page?: number
    pageSize?: number
    useFeedbackTuning?: boolean
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
  if (options?.useFeedbackTuning !== undefined) {
    body.useFeedbackTuning = options.useFeedbackTuning
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
