const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface AtsPushEvent {
  candidateId: string
  jobId: string
  notes: string
  status: string
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

export async function pushToAts(payload: {
  candidateId: string
  jobId?: string
  notes?: string
}): Promise<AtsPushEvent> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/integrations/ats/push`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}

export async function fetchAtsEvents(limit = 30): Promise<AtsPushEvent[]> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/integrations/ats/events?limit=${encodeURIComponent(String(limit))}`)
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}
