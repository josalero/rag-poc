const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface EvalCaseResult {
  question: string
  termRecall: number
  sourceRecall: number
  confidenceScore: number
  returnedSources: number
  matchedTerms: string[]
  missingTerms: string[]
}

export interface EvalRunResponse {
  ranAt: string
  totalQueries: number
  averageTermRecall: number
  averageSourceRecall: number
  averageConfidence: number
  cases: EvalCaseResult[]
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

export async function runEval(payload?: {
  maxResults?: number
  minScore?: number
  useFeedbackTuning?: boolean
}): Promise<EvalRunResponse> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/evals/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload ?? {}),
  })
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}
