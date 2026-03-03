const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface JobMatchCandidate {
  candidateId: string
  displayName: string
  overallScore: number
  mustHaveCoverage: number
  skillCoverage: number
  yearsFit: number
  seniorityFit: number
  matchedSkills: string[]
  missingMustHave: string[]
  suggestedRoles: string[]
}

export interface JobMatchResponse {
  items: JobMatchCandidate[]
  page: number
  pageSize: number
  total: number
  inferredMustHaveSkills: string[]
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

export async function matchCandidates(payload: {
  jobDescription: string
  mustHaveSkills?: string[]
  minScore?: number
  page?: number
  pageSize?: number
}): Promise<JobMatchResponse> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/match`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}
