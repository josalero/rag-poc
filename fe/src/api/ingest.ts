const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface IngestResponse {
  documentsProcessed: number
}

export interface IngestUploadResponse extends IngestResponse {
  fileEvents: IngestProgressEvent[]
}

export interface IngestProgressEvent {
  type: 'file' | 'done'
  filename?: string
  status?: 'ingested' | 'skipped'
  reason?: string
  documentsProcessed?: number
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
  if (res.status === 413 && (!message || message === `Request failed: ${res.status}`)) {
    message = 'Upload too large. Choose fewer files or smaller PDFs, or use "Run Configured Server Folder".'
  }
  return new Error(message)
}

export async function triggerIngest(): Promise<IngestResponse> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/ingest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
  })
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}

export async function triggerIngestUpload(files: File[]): Promise<IngestUploadResponse> {
  const base = getBaseUrl()
  const formData = new FormData()
  for (const file of files) {
    formData.append('files', file, file.name)
  }
  const res = await fetch(`${base}/api/ingest/upload`, {
    method: 'POST',
    body: formData,
  })
  if (!res.ok) {
    throw await parseError(res)
  }
  return res.json()
}

/**
 * Streams ingest progress from POST /api/ingest/stream (SSE).
 * Calls onEvent for each event; onComplete with final count when done; onError on failure.
 */
export async function triggerIngestStream(
  onEvent: (event: IngestProgressEvent) => void,
  onError: (err: Error) => void,
  onComplete: (documentsProcessed: number) => void
): Promise<void> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/ingest/stream`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
  })
  if (!res.ok) {
    onError(await parseError(res))
    return
  }
  const reader = res.body?.getReader()
  if (!reader) {
    onError(new Error('No response body'))
    return
  }
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i]
        if (line.startsWith('data:')) {
          const json = line.slice(5).trim()
          if (json === '[DONE]' || !json) continue
          try {
            const event = JSON.parse(json) as IngestProgressEvent
            onEvent(event)
            if (event.type === 'done' && event.documentsProcessed !== undefined) {
              onComplete(event.documentsProcessed)
            }
          } catch {
            // ignore malformed JSON
          }
        }
      }
    }
    if (buffer.startsWith('data:')) {
      const json = buffer.slice(5).trim()
      if (json) {
        try {
          const event = JSON.parse(json) as IngestProgressEvent
          onEvent(event)
          if (event.type === 'done' && event.documentsProcessed !== undefined) {
            onComplete(event.documentsProcessed)
          }
        } catch {
          // ignore
        }
      }
    }
  } catch (err) {
    onError(err instanceof Error ? err : new Error('Stream failed'))
  } finally {
    reader.releaseLock()
  }
}
