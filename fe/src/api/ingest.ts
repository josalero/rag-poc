const getBaseUrl = () => import.meta.env.VITE_API_URL ?? ''

export interface IngestResponse {
  documentsProcessed: number
}

export interface IngestProgressEvent {
  type: 'file' | 'done'
  filename?: string
  status?: 'ingested' | 'skipped'
  reason?: string
  documentsProcessed?: number
}

export async function triggerIngest(): Promise<IngestResponse> {
  const base = getBaseUrl()
  const res = await fetch(`${base}/api/ingest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({})) as { message?: string; error?: string }
    const message = body.message ?? body.error ?? `Request failed: ${res.status}`
    throw new Error(message)
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
    const text = await res.text()
    onError(new Error(text || `Request failed: ${res.status}`))
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
