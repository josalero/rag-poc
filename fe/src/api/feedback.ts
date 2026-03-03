const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface FeedbackStats {
  total: number
  helpful: number
  notHelpful: number
  helpfulRate: number
}

export interface QueryFeedbackEntry {
  question: string
  answer: string
  helpful: boolean
  notes: string
  createdAt: string
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

export async function submitQueryFeedback(payload: {
  question: string
  answer: string
  helpful: boolean
  notes?: string
}): Promise<QueryFeedbackEntry> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/query/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}

export async function fetchFeedbackStats(): Promise<FeedbackStats> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/query/feedback/stats`)
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}
