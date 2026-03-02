const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface SourceSegment {
  text: string
  source: string
}

export interface QueryResponse {
  answer: string
  sources: SourceSegment[]
}

export async function querySkills(question: string): Promise<QueryResponse> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question }),
  })
  if (!res.ok) {
    const err = await res.text()
    throw new Error(err || `Request failed: ${res.status}`)
  }
  return res.json()
}
