const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface CandidateProfileVersion {
  sourceFilename: string
  ingestedAt: string
  skills: string[]
  significantSkills: string[]
  suggestedRoles: string[]
  estimatedYearsExperience: number | null
  location: string
  preview: string
}

export interface CandidateProfile {
  id: string
  sourceFilename: string
  sourceFilenames: string[]
  displayName: string
  email: string
  phone: string
  linkedinUrl: string
  githubUrl: string
  portfolioUrl: string
  skills: string[]
  significantSkills: string[]
  suggestedRoles: string[]
  estimatedYearsExperience: number | null
  location: string
  fileSizeBytes: number
  fileLastModifiedAt: string | null
  lastIngestedAt: string | null
  preview: string
  versions: CandidateProfileVersion[]
}

export interface CandidateSearchResponse {
  items: CandidateProfile[]
  page: number
  pageSize: number
  total: number
}

function buildQuery(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    search.set(key, String(value))
  })
  const query = search.toString()
  return query ? `?${query}` : ''
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

export async function fetchCandidates(params: {
  search?: string
  skill?: string
  location?: string
  sort?: string
  page?: number
  pageSize?: number
}): Promise<CandidateSearchResponse> {
  const base = getBaseUrl()
  const query = buildQuery(params)
  const res = await fetch(`${base}/api/candidates${query}`)
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}

export async function fetchCandidate(id: string): Promise<CandidateProfile> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/candidates/${encodeURIComponent(id)}`)
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}

export function resumeViewUrl(sourceFilename: string): string {
  const base = getBaseUrl()
  return `${base}/api/resumes/${encodeURIComponent(sourceFilename)}`
}

export function resumeDownloadUrl(sourceFilename: string): string {
  const base = getBaseUrl()
  return `${base}/api/resumes/${encodeURIComponent(sourceFilename)}?download=true`
}

export function candidatesExportCsvUrl(params?: {
  search?: string
  skill?: string
  location?: string
  sort?: string
}): string {
  const base = getBaseUrl()
  const query = buildQuery(params ?? {})
  return `${base}/api/export/candidates.csv${query}`
}
