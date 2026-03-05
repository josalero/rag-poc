import { useEffect, useMemo, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  ConfigProvider,
  Divider,
  Empty,
  Grid,
  Input,
  InputNumber,
  Layout,
  List,
  Modal,
  Pagination,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import type { TableProps, TabsProps } from 'antd'
import {
  CloudUploadOutlined,
  MessageOutlined,
  DislikeOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileExcelOutlined,
  LikeOutlined,
  RocketOutlined,
  ReloadOutlined,
  SaveOutlined,
  SearchOutlined,
  SendOutlined,
  SwapOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { querySkills, type QueryResponse, type SourceSegment } from './api/query'
import { listIngestJobs, startIngestJob, triggerIngestStream, triggerIngestUpload, type IngestJobStatus, type IngestProgressEvent } from './api/ingest'
import {
  candidatesExportCsvUrl,
  fetchCandidates,
  fetchCandidate,
  resumeDownloadUrl,
  resumeViewUrl,
  type CandidateProfile,
} from './api/candidates'
import { fetchFeedbackStats, fetchRecommendedMinScore, submitQueryFeedback, type FeedbackStats } from './api/feedback'
import { fetchIngestAudit, type IngestAuditRun } from './api/audit'
import { pushToAts } from './api/integrations'
import { matchCandidates, type JobMatchResponse } from './api/match'
import { runEval, type EvalRunResponse } from './api/eval'
import { fetchMetricsSummary, type MetricsSummary } from './api/metrics'

const { Header, Content } = Layout
const { Text, Paragraph } = Typography
const { useBreakpoint } = Grid
const SAVED_QUERIES_KEY = 'rag_poc_saved_queries_v1'
const MAX_RESULTS_CAP = 200
const DEFAULT_QUERY_PAGE_SIZE = 10
const GLASS_CARD_CLASS = 'rounded-2xl border border-slate-200 bg-white shadow-sm'
const MAX_UPLOAD_BATCH_BYTES = 25 * 1024 * 1024
const MAX_UPLOAD_BATCH_FILES = 10
const TAB_KEYS = ['ingest', 'chat', 'query', 'match', 'candidates', 'compare', 'audit', 'eval'] as const
const CHAT_SAMPLE_QUESTIONS = [
  'Find candidates for [ROLE] with [SKILL_1], [SKILL_2], and [SKILL_3].',
  'List candidates in [LOCATION] with at least [MIN_YEARS] years of experience for [ROLE].',
  'Who best matches [ROLE] with must-have skills: [MUST_HAVE_SKILLS]?',
  'Show candidates suitable for [ROLE_A] or [ROLE_B], ranked by fit and explain why.',
  'Is [CANDIDATE_NAME] in our records?',
  'Compare top candidates for [ROLE] based on [CRITERIA_1], [CRITERIA_2], and [CRITERIA_3].',
  'Which candidates have experience with [TECH_STACK] and are open to [WORK_MODE_OR_LOCATION]?',
] as const
type TabKey = typeof TAB_KEYS[number]

type StructuredAnswer = {
  answer: string
  keyFindings: string[]
  limitations: string[]
  nextSteps: string[]
}

type GroupedSource = {
  source: string
  count: number
  bestScore: number
  items: SourceSegment[]
}

type SavedQuery = {
  id: string
  question: string
  maxResults: number
  minScore: number
  createdAt: string
}

type ChatMessage = {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources?: SourceSegment[]
  sourcePaging?: {
    query: string
    maxResults: number
    minScore: number
    pageSize: number
    loadedPage: number
    totalSources: number
    useFeedbackTuning: boolean
  }
}

function parseTabRouteFromHash(hashValue: string): { tab: TabKey; candidateId?: string } {
  const hash = (hashValue || '').trim()
  if (!hash.startsWith('#/')) {
    return { tab: 'ingest' }
  }
  const raw = hash.slice(2)
  const [routePart, queryPart = ''] = raw.split('?')
  const route = routePart.trim().toLowerCase()
  const isKnownTab = TAB_KEYS.some((key) => key === route)
  const tab: TabKey = isKnownTab ? route as TabKey : 'ingest'
  if (tab !== 'candidates' || !queryPart) {
    return { tab }
  }
  const params = new URLSearchParams(queryPart)
  const candidateId = params.get('candidate')?.trim()
  return { tab, candidateId: candidateId || undefined }
}

function buildHashForTab(tab: TabKey, candidateId?: string): string {
  if (tab === 'candidates' && candidateId) {
    const params = new URLSearchParams({ candidate: candidateId })
    return `#/${tab}?${params.toString()}`
  }
  return `#/${tab}`
}

function scoreTagColor(score: number): string {
  if (score >= 0.75) return 'success'
  if (score >= 0.5) return 'processing'
  if (score >= 0.3) return 'warning'
  return 'error'
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let idx = 0
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024
    idx += 1
  }
  return `${value.toFixed(idx === 0 ? 0 : 1)} ${units[idx]}`
}

function formatDate(value?: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString()
}

function formatPercent(value?: number): string {
  if (value === undefined || value === null || Number.isNaN(value)) return '-'
  return `${(value * 100).toFixed(0)}%`
}

function truncateText(text: string, maxChars: number): string {
  if (!text) return ''
  if (text.length <= maxChars) return text
  return `${text.slice(0, maxChars).trim()}...`
}

function splitUploadBatches(
  files: File[],
  maxBatchBytes: number = MAX_UPLOAD_BATCH_BYTES,
  maxBatchFiles: number = MAX_UPLOAD_BATCH_FILES
): File[][] {
  const batches: File[][] = []
  let current: File[] = []
  let currentBytes = 0

  for (const file of files) {
    const exceedsFiles = current.length >= maxBatchFiles
    const exceedsBytes = currentBytes > 0 && currentBytes + file.size > maxBatchBytes
    if (exceedsFiles || exceedsBytes) {
      batches.push(current)
      current = []
      currentBytes = 0
    }
    current.push(file)
    currentBytes += file.size
  }

  if (current.length > 0) {
    batches.push(current)
  }
  return batches
}

function extractBulletItems(block: string): string[] {
  return block
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.replace(/^[-*]\s+/, '').trim())
    .filter(Boolean)
}

function parseStructuredAnswer(raw: string): StructuredAnswer | null {
  if (!raw.trim()) return null
  const normalized = raw.replace(/\r/g, '')
  const sectionRegex = /(ANSWER|KEY_FINDINGS|LIMITATIONS|NEXT_STEPS):\s*([\s\S]*?)(?=\n(?:ANSWER|KEY_FINDINGS|LIMITATIONS|NEXT_STEPS):|$)/g
  const sections: Record<string, string> = {}

  let match = sectionRegex.exec(normalized)
  while (match) {
    sections[match[1]] = match[2].trim()
    match = sectionRegex.exec(normalized)
  }

  if (!sections.ANSWER) {
    return null
  }

  return {
    answer: sections.ANSWER,
    keyFindings: extractBulletItems(sections.KEY_FINDINGS ?? ''),
    limitations: extractBulletItems(sections.LIMITATIONS ?? ''),
    nextSteps: extractBulletItems(sections.NEXT_STEPS ?? ''),
  }
}

function groupBySource(sources: SourceSegment[]): GroupedSource[] {
  const grouped = new Map<string, GroupedSource>()
  for (const source of sources) {
    const sourceName = source.source || 'Unknown source'
    const existing = grouped.get(sourceName)
    if (existing) {
      existing.count += 1
      existing.bestScore = Math.max(existing.bestScore, source.score)
      existing.items.push(source)
    } else {
      grouped.set(sourceName, {
        source: sourceName,
        count: 1,
        bestScore: source.score,
        items: [source],
      })
    }
  }
  return Array.from(grouped.values()).sort((a, b) => b.bestScore - a.bestScore || b.count - a.count)
}

function buildChatQuestion(history: ChatMessage[], userQuestion: string): string {
  const cleanQuestion = userQuestion.trim()
  if (!cleanQuestion) return ''
  const recentTurns = history.slice(-6)
  if (recentTurns.length === 0) {
    return cleanQuestion
  }
  const conversation = recentTurns
    .map((turn) => `${turn.role === 'user' ? 'User' : 'Assistant'}: ${truncateText(turn.content.replace(/\s+/g, ' ').trim(), 320)}`)
    .join('\n')
  return `Conversation context:\n${conversation}\n\nCurrent question:\n${cleanQuestion}`
}

function topChatSources(sources: SourceSegment[], limit?: number): SourceSegment[] {
  const ordered = [...(sources ?? [])].sort((a, b) => a.rank - b.rank || b.score - a.score)
  if (typeof limit === 'number') {
    return ordered.slice(0, Math.max(1, limit))
  }
  return ordered
}

function chunkItems<T>(items: T[], chunkSize: number): T[][] {
  if (chunkSize <= 0) return [items]
  const chunks: T[][] = []
  for (let index = 0; index < items.length; index += chunkSize) {
    chunks.push(items.slice(index, index + chunkSize))
  }
  return chunks
}

function CandidateInfoCard({
  candidate,
  onAtsPush,
}: {
  candidate: CandidateProfile | null
  onAtsPush?: (candidateId: string) => void
}) {
  if (!candidate) {
    return (
      <Card className={GLASS_CARD_CLASS}>
        <Empty description="Select a candidate to view details" />
      </Card>
    )
  }

  const quickFacts = [
    { key: 'Location', value: candidate.location || '-' },
    { key: 'Estimated years', value: candidate.estimatedYearsExperience ?? '-' },
    { key: 'Resume size', value: formatBytes(candidate.fileSizeBytes) },
    { key: 'Last ingested', value: formatDate(candidate.lastIngestedAt) },
  ]
  const latestVersion = candidate.versions?.[0]
  const confidenceEntries = Object.entries(latestVersion?.fieldConfidence ?? {})
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)

  return (
    <Card className={GLASS_CARD_CLASS} title={candidate.displayName}>
      <Space direction="vertical" size="middle" className="w-full">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Space wrap>
            <Button size="small" icon={<EyeOutlined />} href={resumeViewUrl(candidate.sourceFilename)} target="_blank">
              View Resume
            </Button>
            <Button size="small" icon={<DownloadOutlined />} href={resumeDownloadUrl(candidate.sourceFilename)}>
              Download PDF
            </Button>
            {onAtsPush && (
              <Button size="small" icon={<SendOutlined />} onClick={() => onAtsPush(candidate.id)}>
                Send To ATS
              </Button>
            )}
          </Space>
          <Tag color="cyan">Merged files: {candidate.sourceFilenames?.length ?? 1}</Tag>
        </div>

        <Row gutter={[12, 12]}>
          {quickFacts.map((item) => (
            <Col key={item.key} xs={24} sm={12}>
              <Card size="small" className="rounded-xl border border-slate-100 bg-slate-50">
                <Text type="secondary">{item.key}</Text>
                <div className="mt-1 text-sm font-semibold text-slate-900">{item.value}</div>
              </Card>
            </Col>
          ))}
        </Row>

        <Card size="small" className="rounded-xl border border-slate-100 bg-slate-50">
          <Space direction="vertical" size={6} className="w-full">
            <Text strong>Contact Channels</Text>
            <Text>
              Email:{' '}
              {candidate.email
                ? <a href={`mailto:${candidate.email}`}>{candidate.email}</a>
                : '-'}
            </Text>
            <Text>Phone: {candidate.phone || '-'}</Text>
            <Space wrap>
              {candidate.linkedinUrl ? (
                <Button size="small" icon={<EyeOutlined />} href={candidate.linkedinUrl} target="_blank">
                  LinkedIn
                </Button>
              ) : (
                <Text type="secondary">LinkedIn: -</Text>
              )}
              {candidate.githubUrl ? (
                <Button size="small" icon={<EyeOutlined />} href={candidate.githubUrl} target="_blank">
                  GitHub
                </Button>
              ) : (
                <Text type="secondary">GitHub: -</Text>
              )}
              {candidate.portfolioUrl ? (
                <Button size="small" icon={<EyeOutlined />} href={candidate.portfolioUrl} target="_blank">
                  Portfolio
                </Button>
              ) : (
                <Text type="secondary">Portfolio: -</Text>
              )}
            </Space>
          </Space>
        </Card>

        <Card size="small" className="rounded-xl border border-slate-100 bg-slate-50">
          <Text strong>Source Files</Text>
          <div className="mt-2 flex flex-wrap gap-2">
            {candidate.sourceFilenames.map((sourceFile) => (
              <Tag key={sourceFile}>{sourceFile}</Tag>
            ))}
          </div>
        </Card>

        <div className="space-y-2">
          <Text strong>All Extracted Skills</Text>
          <div className="flex flex-wrap gap-2">
            {(candidate.skills?.length ?? 0) === 0
              ? <Text type="secondary">No extracted skills yet</Text>
              : candidate.skills.map((skill) => <Tag key={skill}>{skill}</Tag>)}
          </div>
        </div>

        <div className="space-y-2">
          <Text strong>Most Significant Skills</Text>
          <div className="flex flex-wrap gap-2">
            {(candidate.significantSkills?.length ?? 0) === 0
              ? <Text type="secondary">No ranked skills yet</Text>
              : candidate.significantSkills.map((skill) => <Tag color="blue" key={skill}>{skill}</Tag>)}
          </div>
        </div>

        <div className="space-y-2">
          <Text strong>Suggested Role Matches</Text>
          <div className="flex flex-wrap gap-2">
            {(candidate.suggestedRoles?.length ?? 0) === 0
              ? <Text type="secondary">No role suggestions yet</Text>
              : candidate.suggestedRoles.slice(0, 3).map((role) => <Tag color="geekblue" key={role}>{role}</Tag>)}
          </div>
        </div>

        <Card size="small" className="rounded-xl border border-slate-100 bg-slate-50">
          <Space direction="vertical" size={6} className="w-full">
            <Text strong>Extraction Quality</Text>
            <Space wrap>
              <Tag color={latestVersion?.extractionMethod === 'hybrid-llm-rules' ? 'success' : 'default'}>
                Method: {latestVersion?.extractionMethod || 'rules-only'}
              </Tag>
              <Tag color="blue">Normalized chars: {latestVersion?.normalizedTextChars ?? 0}</Tag>
              <Tag color="cyan">
                Content hash: {latestVersion?.normalizedContentHash
                  ? truncateText(latestVersion.normalizedContentHash, 18)
                  : '-'}
              </Tag>
            </Space>

            <div>
              <Text strong>Top Field Confidence</Text>
              <div className="mt-1 flex flex-wrap gap-2">
                {confidenceEntries.length === 0
                  ? <Text type="secondary">No confidence scores available</Text>
                  : confidenceEntries.map(([field, value]) => (
                    <Tag key={field} color="processing">{field}: {formatPercent(value)}</Tag>
                  ))}
              </div>
            </div>

            <div>
              <Text strong>Validation Warnings</Text>
              <div className="mt-1">
                {(latestVersion?.validationWarnings?.length ?? 0) === 0
                  ? <Text type="secondary">No validation warnings</Text>
                  : (
                    <List
                      size="small"
                      dataSource={latestVersion?.validationWarnings ?? []}
                      renderItem={(warning, index) => <List.Item key={`warning-${index}`}>{warning}</List.Item>}
                    />
                  )}
              </div>
            </div>
          </Space>
        </Card>

        <Card size="small" className="rounded-xl border border-slate-100 bg-slate-50">
          <Text strong>Version History</Text>
          <List
            size="small"
            className="mt-2"
            dataSource={(candidate.versions ?? []).slice(0, 6)}
            locale={{ emptyText: 'No version history yet' }}
            renderItem={(version, index) => (
              <List.Item key={`${version.sourceFilename}-${version.ingestedAt}-${index}`}>
                <Space direction="vertical" size={0} className="w-full">
                  <Text strong>{version.sourceFilename}</Text>
                  <Text type="secondary">
                    {formatDate(version.ingestedAt)} · Skills: {version.significantSkills?.slice(0, 3).join(', ') || '-'}
                  </Text>
                </Space>
              </List.Item>
            )}
          />
        </Card>
      </Space>
    </Card>
  )
}

function ChatTab({ onOpenCandidate }: { onOpenCandidate: (candidateId: string) => void }) {
  const screens = useBreakpoint()
  const isMobile = !screens.md
  const [question, setQuestion] = useState('')
  const [maxResults, setMaxResults] = useState(40)
  const [minScore, setMinScore] = useState(0.75)
  const [useFeedbackTuning, setUseFeedbackTuning] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [sourceVisibleCountByMessage, setSourceVisibleCountByMessage] = useState<Record<string, number>>({})
  const [loadingMoreSourcesByMessage, setLoadingMoreSourcesByMessage] = useState<Record<string, boolean>>({})
  const [chatCandidateNameById, setChatCandidateNameById] = useState<Record<string, string>>({})

  useEffect(() => {
    const candidateIds = Array.from(
      new Set(
        messages
          .flatMap((message) => message.sources ?? [])
          .map((source) => source.candidateId)
          .filter(Boolean)
      )
    )
    if (candidateIds.length === 0) return
    const missing = candidateIds.filter((id) => !chatCandidateNameById[id])
    if (missing.length === 0) return

    let cancelled = false
    void Promise.all(
      missing.map(async (candidateId) => {
        try {
          const candidate = await fetchCandidate(candidateId)
          return { candidateId, name: candidate.displayName || candidateId } as const
        } catch {
          return { candidateId, name: candidateId } as const
        }
      })
    ).then((resolved) => {
      if (cancelled) return
      setChatCandidateNameById((prev) => {
        const next = { ...prev }
        for (const item of resolved) {
          next[item.candidateId] = item.name
        }
        return next
      })
    })

    return () => {
      cancelled = true
    }
  }, [messages, chatCandidateNameById])

  async function loadMoreSourcesForMessage(messageId: string) {
    const message = messages.find((item) => item.id === messageId && item.role === 'assistant')
    if (!message?.sourcePaging) return
    const loadedSources = message.sources ?? []
    if (loadedSources.length >= message.sourcePaging.totalSources) return

    const nextPage = message.sourcePaging.loadedPage + 1
    setLoadingMoreSourcesByMessage((prev) => ({ ...prev, [messageId]: true }))
    try {
      const response = await querySkills(message.sourcePaging.query, {
        maxResults: message.sourcePaging.maxResults,
        minScore: message.sourcePaging.minScore,
        page: nextPage,
        pageSize: message.sourcePaging.pageSize,
        useFeedbackTuning: message.sourcePaging.useFeedbackTuning,
      })
      setMessages((prev) => prev.map((item) => {
        if (item.id !== messageId || item.role !== 'assistant') return item
        const existing = item.sources ?? []
        const incoming = response.sources ?? []
        const byKey = new Map<string, SourceSegment>()
        for (const source of [...existing, ...incoming]) {
          const key = `${source.rank}:${source.source}:${source.candidateId}`
          byKey.set(key, source)
        }
        return {
          ...item,
          sources: Array.from(byKey.values()).sort((a, b) => a.rank - b.rank || b.score - a.score),
          sourcePaging: {
            ...(item.sourcePaging ?? message.sourcePaging),
            loadedPage: Math.max(item.sourcePaging?.loadedPage ?? 1, response.page ?? nextPage),
            totalSources: response.totalSources ?? (item.sourcePaging?.totalSources ?? message.sourcePaging.totalSources),
          },
        }
      }))
    } catch {
      setError('Failed to load more sources for this response')
    } finally {
      setLoadingMoreSourcesByMessage((prev) => ({ ...prev, [messageId]: false }))
    }
  }

  async function onShowMoreSources(message: ChatMessage) {
    if (message.role !== 'assistant') return
    const visibleCount = sourceVisibleCountByMessage[message.id] ?? 4
    const orderedSources = topChatSources(message.sources ?? [])
    if (visibleCount < orderedSources.length) {
      setSourceVisibleCountByMessage((prev) => ({
        ...prev,
        [message.id]: Math.min((prev[message.id] ?? 4) + 4, orderedSources.length),
      }))
      return
    }
    const paging = message.sourcePaging
    if (!paging || orderedSources.length >= paging.totalSources) return
    await loadMoreSourcesForMessage(message.id)
    setSourceVisibleCountByMessage((prev) => ({
      ...prev,
      [message.id]: (prev[message.id] ?? 4) + 4,
    }))
  }

  async function sendMessage() {
    const rawQuestion = question.trim()
    if (!rawQuestion || loading) return
    const nextUserMessage: ChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: rawQuestion,
    }
    const nextMessages = [...messages, nextUserMessage]
    setMessages(nextMessages)
    setQuestion('')
    setLoading(true)
    setError(null)
    try {
      const conversationQuestion = buildChatQuestion(nextMessages, rawQuestion)
      const response = await querySkills(conversationQuestion, {
        maxResults,
        minScore,
        page: 1,
        pageSize: 8,
        useFeedbackTuning,
      })
      setMessages((prev) => [
        ...prev,
        {
          id: `a-${Date.now()}`,
          role: 'assistant',
          content: response.answer,
          sources: response.sources,
          sourcePaging: {
            query: conversationQuestion,
            maxResults,
            minScore,
            pageSize: response.pageSize ?? 8,
            loadedPage: response.page ?? 1,
            totalSources: response.totalSources ?? (response.sources?.length ?? 0),
            useFeedbackTuning,
          },
        },
      ])
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Chat query failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Card
        className={GLASS_CARD_CLASS}
        title="Talent Assistant Chat"
        extra={(
          <Space>
            <Button onClick={() => setUseFeedbackTuning((prev) => !prev)}>
              Feedback Tuning: {useFeedbackTuning ? 'On' : 'Off'}
            </Button>
            <Button onClick={() => { setMessages([]); setError(null); setSourceVisibleCountByMessage({}); setLoadingMoreSourcesByMessage({}) }} disabled={messages.length === 0 || loading}>
              New Chat
            </Button>
          </Space>
        )}
      >
        <Row gutter={[16, 16]} align="top">
          <Col xs={24} xl={16}>
            <Space direction="vertical" size="middle" className="w-full">
              <Paragraph type="secondary" className="mb-0">
                Ask naturally about candidates, skills, roles, years of experience, location, and profile fit. Responses include grounded sources.
              </Paragraph>
              <div className="max-h-[640px] space-y-3 overflow-y-auto rounded-xl border border-slate-200 bg-slate-50 p-3">
                {messages.length === 0 ? (
                  <Empty description="Start a conversation with the talent assistant" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                ) : (
                  messages.map((message) => {
                    const structured = message.role === 'assistant' ? parseStructuredAnswer(message.content) : null
                    const rankedSources = message.role === 'assistant' ? topChatSources(message.sources ?? []) : []
                    const visibleCount = sourceVisibleCountByMessage[message.id] ?? 4
                    const visibleSources = rankedSources.slice(0, visibleCount)
                    const hasMoreLoadedSources = visibleCount < rankedSources.length
                    const totalAvailableSources = message.sourcePaging?.totalSources ?? rankedSources.length
                    const hasMoreServerSources = rankedSources.length < totalAvailableSources
                    const canShowLessSources = rankedSources.length > 4 && visibleCount > 4
                    return (
                      <div
                        key={message.id}
                        className={message.role === 'user' ? 'flex justify-end' : 'flex justify-start'}
                      >
                        <div className={message.role === 'user'
                          ? 'max-w-[85%] rounded-2xl bg-emerald-600 px-4 py-3 text-white shadow-sm'
                          : 'max-w-[90%] rounded-2xl border border-slate-200 bg-white px-4 py-3 text-slate-900 shadow-sm'}
                        >
                          {message.role === 'assistant' && structured ? (
                            <Space direction="vertical" size={6} className="w-full">
                              <Paragraph className="mb-0 whitespace-pre-wrap">{structured.answer}</Paragraph>
                              {structured.keyFindings.length > 0 && (
                                <div>
                                  <Text strong>Key findings</Text>
                                  <List
                                    size="small"
                                    dataSource={structured.keyFindings}
                                    renderItem={(item, index) => <List.Item key={`${message.id}-k-${index}`}>{item}</List.Item>}
                                  />
                                </div>
                              )}
                            </Space>
                          ) : (
                            <Paragraph className="mb-0 whitespace-pre-wrap">{message.content}</Paragraph>
                          )}
                          {message.role === 'assistant' && (message.sources?.length ?? 0) > 0 && (
                            <div className="mt-3 rounded-xl border border-slate-200 bg-slate-50 p-2">
                              <div className="mb-2 flex items-center justify-between">
                                <Text strong>Top Sources</Text>
                                <Tag>{visibleSources.length}/{totalAvailableSources}</Tag>
                              </div>
                              <List
                                size="small"
                                dataSource={visibleSources}
                                renderItem={(source) => (
                                  <List.Item
                                    key={`${message.id}-${source.rank}-${source.source}-${source.candidateId}`}
                                    className="px-0"
                                    actions={[
                                      source.source
                                        ? (
                                          <Button size="small" icon={<EyeOutlined />} href={resumeViewUrl(source.source)} target="_blank">
                                            View
                                          </Button>
                                        )
                                        : null,
                                      source.source
                                        ? (
                                          <Button size="small" icon={<DownloadOutlined />} href={resumeDownloadUrl(source.source)}>
                                            PDF
                                          </Button>
                                        )
                                        : null,
                                      source.candidateId
                                        ? (
                                          <Button size="small" icon={<UserOutlined />} onClick={() => onOpenCandidate(source.candidateId)}>
                                            Candidate
                                          </Button>
                                        )
                                        : null,
                                    ].filter(Boolean)}
                                  >
                                    <Space direction="vertical" size={2} className="w-full">
                                      <Space wrap size={6}>
                                        <Tag color="blue">#{source.rank}</Tag>
                                        <Tag color={scoreTagColor(source.score)}>{source.score.toFixed(3)}</Tag>
                                      </Space>
                                      <Text strong>
                                        {source.candidateId
                                          ? truncateText(chatCandidateNameById[source.candidateId] ?? source.candidateId, isMobile ? 28 : 44)
                                          : truncateText(source.source || 'Unknown source', isMobile ? 28 : 44)}
                                      </Text>
                                      <Text type="secondary">
                                        {truncateText(source.source || 'Unknown source', isMobile ? 30 : 52)}
                                      </Text>
                                    </Space>
                                  </List.Item>
                                )}
                              />
                              {(hasMoreLoadedSources || hasMoreServerSources || canShowLessSources) && (
                                <Space wrap className="mt-2">
                                  {(hasMoreLoadedSources || hasMoreServerSources) && (
                                    <Button
                                      size="small"
                                      loading={Boolean(loadingMoreSourcesByMessage[message.id])}
                                      onClick={() => { void onShowMoreSources(message) }}
                                    >
                                      Show more
                                    </Button>
                                  )}
                                  {canShowLessSources && (
                                    <Button
                                      size="small"
                                      onClick={() => setSourceVisibleCountByMessage((prev) => ({
                                        ...prev,
                                        [message.id]: 4,
                                      }))}
                                    >
                                      Show less
                                    </Button>
                                  )}
                                </Space>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    )
                  })
                )}
                {loading && (
                  <div className="flex justify-start">
                    <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3 shadow-sm">
                      <Text type="secondary">Thinking...</Text>
                    </div>
                  </div>
                )}
              </div>
              <Space.Compact className="w-full">
                <Input
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                  onPressEnter={() => { void sendMessage() }}
                  placeholder="Ask about candidates, role fit, skills, years, location, or comparisons..."
                  disabled={loading}
                  allowClear
                />
                <Button type="primary" icon={<SendOutlined />} loading={loading} onClick={() => { void sendMessage() }}>
                  Send
                </Button>
              </Space.Compact>
              {error && <Alert type="error" showIcon message={error} />}
            </Space>
          </Col>

          <Col xs={24} xl={8}>
            <Space direction="vertical" size="middle" className="w-full">
              <Card size="small" className="rounded-xl border border-slate-200 bg-slate-50" title="Chat Settings">
                <Row gutter={[12, 12]}>
                  <Col xs={24}>
                    <Text type="secondary">Max sources</Text>
                    <InputNumber
                      min={1}
                      max={MAX_RESULTS_CAP}
                      value={maxResults}
                      onChange={(value) => setMaxResults(value ?? 40)}
                      className="mt-2 w-full"
                    />
                  </Col>
                  <Col xs={24}>
                    <Text type="secondary">Minimum score</Text>
                    <InputNumber
                      min={0}
                      max={1}
                      step={0.05}
                      value={minScore}
                      onChange={(value) => setMinScore(value ?? 0.75)}
                      className="mt-2 w-full"
                    />
                  </Col>
                </Row>
              </Card>
              <Card
                size="small"
                className="rounded-xl border border-emerald-100 bg-emerald-50"
                title="Sample Questions"
              >
                <List
                  size="small"
                  dataSource={CHAT_SAMPLE_QUESTIONS}
                  renderItem={(sample, index) => (
                    <List.Item
                      key={`chat-sample-${index}`}
                      actions={[
                        <Button key={`chat-sample-use-${index}`} size="small" onClick={() => setQuestion(sample)}>
                          Use
                        </Button>,
                      ]}
                    >
                      <Text>{isMobile ? truncateText(sample, 92) : sample}</Text>
                    </List.Item>
                  )}
                />
              </Card>
            </Space>
          </Col>
        </Row>
      </Card>
    </Space>
  )
}

function QueryTab({ onOpenCandidate }: { onOpenCandidate: (candidateId: string) => void }) {
  const screens = useBreakpoint()
  const isMobile = !screens.md
  const [question, setQuestion] = useState('')
  const [activeQuestion, setActiveQuestion] = useState('')
  const [maxResults, setMaxResults] = useState(60)
  const [minScore, setMinScore] = useState(0.75)
  const [useFeedbackTuning, setUseFeedbackTuning] = useState(true)
  const [result, setResult] = useState<QueryResponse | null>(null)
  const [savedQueries, setSavedQueries] = useState<SavedQuery[]>([])
  const [candidateNameById, setCandidateNameById] = useState<Record<string, string>>({})
  const [candidateRolesById, setCandidateRolesById] = useState<Record<string, string[]>>({})
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [feedbackStats, setFeedbackStats] = useState<FeedbackStats | null>(null)
  const [feedbackNotes, setFeedbackNotes] = useState('')
  const [submittingFeedback, setSubmittingFeedback] = useState(false)
  const sourcesLoading = loading && result !== null

  useEffect(() => {
    try {
      const raw = localStorage.getItem(SAVED_QUERIES_KEY)
      if (!raw) return
      const parsed = JSON.parse(raw) as SavedQuery[]
      if (Array.isArray(parsed)) {
        setSavedQueries(parsed.slice(0, 12))
      }
    } catch {
      // ignore invalid local storage data
    }
  }, [])

  useEffect(() => {
    fetchFeedbackStats().then(setFeedbackStats).catch(() => {
      // best effort only
    })
  }, [])

  useEffect(() => {
    const candidateIds = Array.from(
      new Set((result?.sources ?? []).map((source) => source.candidateId).filter(Boolean))
    )
    if (candidateIds.length === 0) return
    const missingIds = candidateIds.filter(
      (candidateId) => !candidateNameById[candidateId] || candidateRolesById[candidateId] === undefined
    )
    if (missingIds.length === 0) return

    let cancelled = false
    void Promise.all(
      missingIds.map(async (candidateId) => {
        try {
          const candidate = await fetchCandidate(candidateId)
          return {
            candidateId,
            displayName: candidate.displayName || candidateId,
            roles: Array.isArray(candidate.suggestedRoles) ? candidate.suggestedRoles : [],
          } as const
        } catch {
          return {
            candidateId,
            displayName: candidateId,
            roles: [],
          } as const
        }
      })
    ).then((entries) => {
      if (cancelled) return
      setCandidateNameById((prev) => {
        const next = { ...prev }
        for (const entry of entries) {
          next[entry.candidateId] = entry.displayName
        }
        return next
      })
      setCandidateRolesById((prev) => {
        const next = { ...prev }
        for (const entry of entries) {
          next[entry.candidateId] = entry.roles
        }
        return next
      })
    })

    return () => {
      cancelled = true
    }
  }, [result?.sources, candidateNameById, candidateRolesById])

  const structuredAnswer = useMemo(() => parseStructuredAnswer(result?.answer ?? ''), [result?.answer])
  const groupedSources = useMemo(() => groupBySource(result?.sources ?? []), [result?.sources])
  const scoreStats = useMemo(() => {
    const scores = (result?.sources ?? []).map((source) => source.score)
    if (scores.length === 0) return null
    const sum = scores.reduce((acc, value) => acc + value, 0)
    return {
      avg: sum / scores.length,
      min: Math.min(...scores),
      max: Math.max(...scores),
    }
  }, [result?.sources])

  function persistSavedQueries(next: SavedQuery[]) {
    setSavedQueries(next)
    localStorage.setItem(SAVED_QUERIES_KEY, JSON.stringify(next.slice(0, 12)))
  }

  async function runQuery(params: {
    q: string
    page: number
    pageSize: number
    saveQuery: boolean
    overrideMaxResults?: number
    overrideMinScore?: number
  }) {
    if (!params.q.trim()) return
    setLoading(true)
    setError(null)
    try {
      const effectiveMaxResults = params.overrideMaxResults ?? maxResults
      const effectiveMinScore = params.overrideMinScore ?? minScore
      const data = await querySkills(params.q.trim(), {
        maxResults: effectiveMaxResults,
        minScore: effectiveMinScore,
        page: params.page,
        pageSize: params.pageSize,
        useFeedbackTuning,
      })
      setResult(data)
      setActiveQuestion(params.q.trim())
      setFeedbackNotes('')
      if (params.saveQuery) {
        const next: SavedQuery[] = [
          {
            id: `${Date.now()}`,
            question: params.q.trim(),
            maxResults: effectiveMaxResults,
            minScore: effectiveMinScore,
            createdAt: new Date().toISOString(),
          },
          ...savedQueries.filter((item) => item.question.trim().toLowerCase() !== params.q.trim().toLowerCase()),
        ]
        persistSavedQueries(next)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Query failed')
    } finally {
      setLoading(false)
    }
  }

  async function submitFeedback(helpful: boolean) {
    if (!result || !activeQuestion) return
    setSubmittingFeedback(true)
    try {
      await submitQueryFeedback({
        question: activeQuestion,
        answer: result.answer,
        helpful,
        notes: feedbackNotes,
        minScoreUsed: minScore,
        avgSourceScore: result.sources.length > 0
          ? result.sources.reduce((sum, source) => sum + source.score, 0) / result.sources.length
          : undefined,
      })
      const stats = await fetchFeedbackStats()
      setFeedbackStats(stats)
      setFeedbackNotes('')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send feedback')
    } finally {
      setSubmittingFeedback(false)
    }
  }

  const sourceColumns = useMemo<TableProps<SourceSegment>['columns']>(
    () => [
      {
        title: '#',
        dataIndex: 'rank',
        key: 'rank',
        width: 70,
        fixed: 'left',
        render: (rank: number) => <Tag color="blue">#{rank}</Tag>,
      },
      {
        title: 'Source',
        dataIndex: 'source',
        key: 'source',
        width: isMobile ? 220 : 280,
        ellipsis: true,
        render: (source: string) => <Text strong>{source || 'Unknown source'}</Text>,
      },
      {
        title: 'Score',
        dataIndex: 'score',
        key: 'score',
        width: 120,
        render: (score: number) => <Tag color={scoreTagColor(score)}>{score.toFixed(3)}</Tag>,
      },
      {
        title: 'Candidate',
        dataIndex: 'candidateId',
        key: 'candidateId',
        width: isMobile ? 190 : 220,
        render: (candidateId: string) => (
          candidateId ? (
            <Button size="small" type="link" icon={<UserOutlined />} onClick={() => onOpenCandidate(candidateId)}>
              {truncateText(candidateNameById[candidateId] ?? 'Candidate Profile', isMobile ? 24 : 32)}
            </Button>
          ) : (
            <Text type="secondary">-</Text>
          )
        ),
      },
      {
        title: 'Roles',
        dataIndex: 'candidateId',
        key: 'roles',
        width: isMobile ? 220 : 280,
        render: (candidateId: string) => {
          const roles = candidateRolesById[candidateId] ?? []
          return roles.length > 0
            ? roles.slice(0, 3).map((role) => <Tag color="geekblue" key={`${candidateId}-${role}`}>{role}</Tag>)
            : <Text type="secondary">-</Text>
        },
      },
      {
        title: 'Actions',
        key: 'actions',
        width: isMobile ? 180 : 220,
        render: (_, source) => (
          <Space wrap>
            {source.source && (
              <>
                <Button size="small" icon={<EyeOutlined />} href={resumeViewUrl(source.source)} target="_blank">
                  View
                </Button>
                <Button size="small" icon={<DownloadOutlined />} href={resumeDownloadUrl(source.source)}>
                  PDF
                </Button>
              </>
            )}
          </Space>
        ),
      },
    ],
    [candidateNameById, candidateRolesById, isMobile, onOpenCandidate]
  )

  const groupedColumns = useMemo<TableProps<GroupedSource>['columns']>(
    () => [
      {
        title: 'Source',
        dataIndex: 'source',
        key: 'source',
        render: (source: string) => <Text strong>{source}</Text>,
      },
      {
        title: 'Matches',
        dataIndex: 'count',
        key: 'count',
        width: 120,
        render: (count: number) => <Tag>{count}</Tag>,
      },
      {
        title: 'Best Score',
        dataIndex: 'bestScore',
        key: 'bestScore',
        width: 140,
        render: (bestScore: number) => <Tag color={scoreTagColor(bestScore)}>{bestScore.toFixed(3)}</Tag>,
      },
    ],
    []
  )

  const queryTabs: TabsProps['items'] = [
    {
      key: 'ranked',
      label: `Ranked (${result?.totalSources ?? 0})`,
      children: (
        <Space direction="vertical" size="middle" className="w-full">
          <Pagination
            current={result?.page}
            pageSize={result?.pageSize}
            total={result?.totalSources}
            disabled={sourcesLoading}
            showSizeChanger
            pageSizeOptions={['5', '10', '20', '50']}
            showTotal={(total, range) => `${range[0]}-${range[1]} of ${total}`}
            onChange={(page, pageSize) => {
              void runQuery({
                q: activeQuestion || question,
                page,
                pageSize,
                saveQuery: false,
              })
            }}
          />
          <Table<SourceSegment>
            rowKey={(source) => `${source.rank}-${source.source}-${source.score}-${source.candidateId}`}
            size="small"
            columns={sourceColumns}
            dataSource={result?.sources ?? []}
            loading={sourcesLoading}
            pagination={false}
            scroll={{ x: 1040 }}
            expandable={{
              expandedRowRender: (source) => (
                <div className="space-y-3 py-1">
                  <Paragraph className="mb-0 whitespace-pre-wrap">{source.text}</Paragraph>
                  <Space wrap>
                    {source.source && (
                      <>
                        <Button size="small" icon={<EyeOutlined />} href={resumeViewUrl(source.source)} target="_blank">
                          View Resume
                        </Button>
                        <Button size="small" icon={<DownloadOutlined />} href={resumeDownloadUrl(source.source)}>
                          Download PDF
                        </Button>
                      </>
                    )}
                    {source.candidateId && (
                      <Button size="small" icon={<UserOutlined />} onClick={() => onOpenCandidate(source.candidateId)}>
                        Open Candidate
                      </Button>
                    )}
                  </Space>
                </div>
              ),
            }}
          />
        </Space>
      ),
    },
    {
      key: 'grouped',
      label: `Grouped (${groupedSources.length})`,
      children: (
        <Table<GroupedSource>
          rowKey={(group) => group.source}
          size="small"
          columns={groupedColumns}
          dataSource={groupedSources}
          loading={sourcesLoading}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 680 }}
          expandable={{
            expandedRowRender: (group) => (
              <List
                size="small"
                dataSource={group.items.slice(0, 5)}
                renderItem={(item) => (
                  <List.Item key={`${group.source}-${item.rank}`}>
                    <Space direction="vertical" size={2} className="w-full">
                      <Text type="secondary">
                        #{item.rank} · {item.score.toFixed(3)}
                      </Text>
                      <Text>{truncateText(item.text.replace(/\s+/g, ' '), 260)}</Text>
                    </Space>
                  </List.Item>
                )}
              />
            ),
          }}
        />
      ),
    },
  ]

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Card className={GLASS_CARD_CLASS} title="Smart Query">
        <Space direction="vertical" size="middle" className="w-full">
          <Paragraph type="secondary" className="mb-0">
            Search all qualifying resume segments with ranked evidence and open candidate profiles directly.
          </Paragraph>
          <Row gutter={[12, 12]}>
            <Col xs={24} lg={14}>
              <Space.Compact className="w-full">
                <Input
                  size="large"
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                  placeholder="e.g. Which candidates have Java + Spring + AWS?"
                  allowClear
                />
                <Button
                  type="primary"
                  size="large"
                  icon={<SearchOutlined />}
                  loading={loading}
                  onClick={() => runQuery({ q: question, page: 1, pageSize: result?.pageSize ?? DEFAULT_QUERY_PAGE_SIZE, saveQuery: true })}
                >
                  Ask
                </Button>
              </Space.Compact>
            </Col>
            <Col xs={12} lg={5}>
              <Text type="secondary">Max sources</Text>
              <InputNumber
                min={1}
                max={MAX_RESULTS_CAP}
                value={maxResults}
                onChange={(value) => setMaxResults(value ?? 60)}
                className="mt-2 w-full"
              />
            </Col>
            <Col xs={12} lg={5}>
              <Text type="secondary">Minimum score</Text>
              <InputNumber
                min={0}
                max={1}
                step={0.05}
                value={minScore}
                onChange={(value) => setMinScore(value ?? 0)}
                className="mt-2 w-full"
              />
              <Button
                className="mt-2 w-full"
                onClick={() => {
                  void fetchRecommendedMinScore(minScore).then((recommended) => {
                    setMinScore(Number(recommended.toFixed(2)))
                  }).catch(() => {
                    // best effort only
                  })
                }}
              >
                Use feedback recommendation
              </Button>
            </Col>
          </Row>
          <Space wrap>
            <Button type={useFeedbackTuning ? 'primary' : 'default'} onClick={() => setUseFeedbackTuning((prev) => !prev)}>
              Feedback Tuning: {useFeedbackTuning ? 'On' : 'Off'}
            </Button>
          </Space>

          {savedQueries.length > 0 && (
            <div>
              <Text strong>Saved queries</Text>
              <Space wrap className="mt-2">
                {savedQueries.map((item) => (
                  <Button
                    key={item.id}
                    size="small"
                    icon={<SaveOutlined />}
                    onClick={() => {
                      setQuestion(item.question)
                      setMaxResults(item.maxResults)
                      setMinScore(item.minScore)
                      void runQuery({
                        q: item.question,
                        page: 1,
                        pageSize: DEFAULT_QUERY_PAGE_SIZE,
                        saveQuery: false,
                        overrideMaxResults: item.maxResults,
                        overrideMinScore: item.minScore,
                      })
                    }}
                  >
                    {truncateText(item.question, 54)}
                  </Button>
                ))}
              </Space>
            </div>
          )}
        </Space>
      </Card>

      {error && (
        <Alert
          type="error"
          showIcon
          closable
          message={error}
          onClose={() => setError(null)}
        />
      )}

      {result && (
        <Row gutter={[16, 16]}>
          <Col xs={24} xl={14}>
            <Card className={GLASS_CARD_CLASS} title="Answer">
              {structuredAnswer ? (
                <Space direction="vertical" size="middle" className="w-full">
                  <div>
                    <Text strong>Direct Answer</Text>
                    <Paragraph className="mb-0 mt-2 whitespace-pre-wrap">{structuredAnswer.answer}</Paragraph>
                  </div>
                  {structuredAnswer.keyFindings.length > 0 && (
                    <div>
                      <Text strong>Key Findings</Text>
                      <List size="small" dataSource={structuredAnswer.keyFindings} renderItem={(item, i) => <List.Item key={`k-${i}`}>{item}</List.Item>} />
                    </div>
                  )}
                  {structuredAnswer.limitations.length > 0 && (
                    <div>
                      <Text strong>Limitations</Text>
                      <List size="small" dataSource={structuredAnswer.limitations} renderItem={(item, i) => <List.Item key={`l-${i}`}>{item}</List.Item>} />
                    </div>
                  )}
                  {structuredAnswer.nextSteps.length > 0 && (
                    <div>
                      <Text strong>Next Steps</Text>
                      <List size="small" dataSource={structuredAnswer.nextSteps} renderItem={(item, i) => <List.Item key={`n-${i}`}>{item}</List.Item>} />
                    </div>
                  )}
                </Space>
              ) : (
                <Paragraph className="mb-0 whitespace-pre-wrap">{result.answer}</Paragraph>
              )}

              <Divider className="my-4" />
              <Space direction={isMobile ? 'vertical' : 'horizontal'} className="w-full">
                <Input.TextArea
                  value={feedbackNotes}
                  onChange={(e) => setFeedbackNotes(e.target.value)}
                  rows={2}
                  placeholder="Optional feedback notes"
                  className="min-w-[260px]"
                />
                <Space wrap>
                  <Button icon={<LikeOutlined />} loading={submittingFeedback} onClick={() => void submitFeedback(true)}>
                    Helpful
                  </Button>
                  <Button icon={<DislikeOutlined />} loading={submittingFeedback} onClick={() => void submitFeedback(false)}>
                    Not helpful
                  </Button>
                </Space>
              </Space>
              {feedbackStats && (
                <Space wrap className="mt-3">
                  <Tag color="processing">
                    Helpful rate: {feedbackStats.helpfulRate.toFixed(1)}% ({feedbackStats.helpful}/{feedbackStats.total})
                  </Tag>
                  <Tag color="blue">
                    Recommended min score: {feedbackStats.recommendedMinScore.toFixed(2)}
                  </Tag>
                </Space>
              )}
            </Card>
          </Col>

          <Col xs={24} xl={10}>
            <Card className={GLASS_CARD_CLASS} title="Explainability">
              <Row gutter={[12, 12]}>
                <Col xs={12}><Statistic title="Total sources" value={result.totalSources} /></Col>
                <Col xs={12}><Statistic title="Page size" value={result.pageSize} /></Col>
                <Col xs={12}><Statistic title="Current page" value={result.page} /></Col>
                <Col xs={12}><Statistic title="Min score" value={minScore.toFixed(2)} /></Col>
              </Row>
              {scoreStats && (
                <Paragraph type="secondary" className="mb-0 mt-3">
                  Page scores: avg {scoreStats.avg.toFixed(3)}, min {scoreStats.min.toFixed(3)}, max {scoreStats.max.toFixed(3)}.
                </Paragraph>
              )}
              {activeScopeId && (
                <Tag color="cyan" className="mt-3">
                  Scope: {activeScopeId}
                </Tag>
              )}
              {result.explainability && (
                <Space direction="vertical" size={8} className="mt-3 w-full">
                  <Tag color={scoreTagColor(result.explainability.confidenceScore)}>
                    Confidence: {result.explainability.confidenceScore.toFixed(3)}
                  </Tag>
                  <div>
                    <Text strong>Matched terms</Text>
                    <div className="mt-1 flex flex-wrap gap-2">
                      {(result.explainability.matchedTerms ?? []).length > 0
                        ? result.explainability.matchedTerms.map((term) => <Tag color="green" key={`m-${term}`}>{term}</Tag>)
                        : <Text type="secondary">-</Text>}
                    </div>
                  </div>
                  <div>
                    <Text strong>Missing terms</Text>
                    <div className="mt-1 flex flex-wrap gap-2">
                      {(result.explainability.missingTerms ?? []).length > 0
                        ? result.explainability.missingTerms.map((term) => <Tag color="orange" key={`x-${term}`}>{term}</Tag>)
                        : <Text type="secondary">-</Text>}
                    </div>
                  </div>
                </Space>
              )}
            </Card>
          </Col>

          <Col xs={24}>
            <Card
              className={GLASS_CARD_CLASS}
              title={`Sources (${result.totalSources})`}
              extra={(
                <Text type="secondary">
                  {sourcesLoading ? 'Loading page...' : `Showing ${result.sources.length} on this page`}
                </Text>
              )}
            >
              {result.totalSources === 0 ? (
                <Alert type="info" showIcon message="No sources matched this query." />
              ) : (
                <Tabs defaultActiveKey="ranked" items={queryTabs} />
              )}
            </Card>
          </Col>
        </Row>
      )}
    </Space>
  )
}

function CandidatesTab({
  selectedCandidateId,
  isActive,
}: {
  selectedCandidateId?: string
  isActive: boolean
}) {
  const screens = useBreakpoint()
  const isMobile = !screens.md
  const [search, setSearch] = useState('')
  const [skill, setSkill] = useState('')
  const [sort, setSort] = useState('name_asc')
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(12)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [atsMessage, setAtsMessage] = useState<string | null>(null)
  const [items, setItems] = useState<CandidateProfile[]>([])
  const [total, setTotal] = useState(0)
  const [selected, setSelected] = useState<CandidateProfile | null>(null)
  const [profileOpen, setProfileOpen] = useState(false)
  const [profileLoading, setProfileLoading] = useState(false)

  async function loadCandidates() {
    setLoading(true)
    setError(null)
    try {
      const data = await fetchCandidates({ search, skill, sort, page, pageSize })
      setItems(data.items)
      setTotal(data.total)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load candidates')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!isActive) return
    void loadCandidates()
  }, [isActive, page, pageSize, sort])

  useEffect(() => {
    if (!selectedCandidateId) return
    setProfileOpen(true)
    setProfileLoading(true)
    void fetchCandidate(selectedCandidateId)
      .then((candidate) => {
        setSelected(candidate)
      })
      .catch(() => {
        setError('Failed to open candidate profile')
      })
      .finally(() => {
        setProfileLoading(false)
      })
  }, [selectedCandidateId])

  async function handleAtsPush(candidateId: string) {
    try {
      const response = await pushToAts({ candidateId })
      setAtsMessage(`ATS push status: ${response.status}`)
    } catch (err) {
      setAtsMessage(err instanceof Error ? err.message : 'Failed to push to ATS')
    }
  }

  async function openProfile(candidate: CandidateProfile) {
    setSelected(candidate)
    setProfileOpen(true)
    setProfileLoading(true)
    try {
      const fullProfile = await fetchCandidate(candidate.id)
      setSelected(fullProfile)
    } catch {
      setError('Failed to load candidate profile details')
    } finally {
      setProfileLoading(false)
    }
  }

  const candidateColumns = useMemo<TableProps<CandidateProfile>['columns']>(
    () => [
      {
        title: 'Candidate',
        key: 'displayName',
        width: isMobile ? 180 : 220,
        render: (_, candidate) => (
          <Text strong>{candidate.displayName}</Text>
        ),
      },
      {
        title: 'Top skills',
        key: 'skills',
        width: isMobile ? 120 : 150,
        render: (_, candidate) => {
          const topSkills = (candidate.significantSkills ?? []).slice(0, 4)
          if (topSkills.length === 0) {
            return <Text type="secondary">-</Text>
          }
          return (
            <Space direction="vertical" size={4}>
              {chunkItems(topSkills, 2).map((row, rowIndex) => (
                <Space key={`${candidate.id}-skills-row-${rowIndex}`}>
                  {row.map((skill) => (
                    <Tag color="blue" key={`${candidate.id}-${skill}`}>{skill}</Tag>
                  ))}
                </Space>
              ))}
            </Space>
          )
        },
      },
      {
        title: 'Roles',
        key: 'roles',
        width: isMobile ? 170 : 210,
        render: (_, candidate) => (
          <Space wrap>
            {(candidate.suggestedRoles ?? []).slice(0, 3).map((role) => (
              <Tag color="geekblue" key={`${candidate.id}-${role}`}>{role}</Tag>
            ))}
            {(candidate.suggestedRoles?.length ?? 0) === 0 && <Text type="secondary">-</Text>}
          </Space>
        ),
      },
      {
        title: 'Actions',
        key: 'actions',
        width: isMobile ? 110 : 130,
        render: (_, candidate) => (
          <Button
            type="link"
            icon={<EyeOutlined />}
            onClick={(event) => {
              event.stopPropagation()
              void openProfile(candidate)
            }}
          >
            Profile
          </Button>
        ),
      },
    ],
    [isMobile]
  )

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Card
        className={GLASS_CARD_CLASS}
        title="Candidate Directory"
        extra={(
          <Space wrap>
            <Button icon={<FileExcelOutlined />} href={candidatesExportCsvUrl({ search, skill, sort })}>
              Export CSV
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => void loadCandidates()}>Refresh</Button>
          </Space>
        )}
      >
        <Row gutter={[12, 12]}>
          <Col xs={24} md={10}>
            <Input placeholder="Search name, file, preview" value={search} onChange={(e) => setSearch(e.target.value)} />
          </Col>
          <Col xs={24} md={6}>
            <Input placeholder="Skill filter (e.g. JAVA)" value={skill} onChange={(e) => setSkill(e.target.value)} />
          </Col>
          <Col xs={24} md={6}>
            <Select
              className="w-full"
              value={sort}
              options={[
                { label: 'Name asc', value: 'name_asc' },
                { label: 'Name desc', value: 'name_desc' },
                { label: 'Years desc', value: 'years_desc' },
                { label: 'Skills desc', value: 'skills_desc' },
                { label: 'Ingested desc', value: 'ingested_desc' },
              ]}
              onChange={(value) => setSort(value)}
            />
          </Col>
          <Col xs={24} md={2}>
            <Button type="primary" icon={<SearchOutlined />} onClick={() => { setPage(1); void loadCandidates() }}>
              Find
            </Button>
          </Col>
        </Row>
      </Card>

      {error && <Alert type="error" showIcon message={error} />}
      {atsMessage && <Alert type="info" showIcon message={atsMessage} closable onClose={() => setAtsMessage(null)} />}

      <Card
        className={GLASS_CARD_CLASS}
        title={`Candidates (${total})`}
        extra={<Text type="secondary">Open a candidate profile from Actions</Text>}
        loading={loading}
      >
        <Table<CandidateProfile>
          rowKey={(candidate) => candidate.id}
          columns={candidateColumns}
          dataSource={items}
          pagination={false}
          locale={{ emptyText: 'No candidates found' }}
          scroll={{ x: isMobile ? 740 : 980 }}
          rowClassName={(candidate) => selected?.id === candidate.id ? 'bg-emerald-50/60' : ''}
          onRow={(candidate) => ({
            onClick: () => {
              void openProfile(candidate)
            },
          })}
        />
        <div className="mt-4">
          <Pagination
            current={page}
            pageSize={pageSize}
            total={total}
            showSizeChanger
            pageSizeOptions={['8', '12', '20', '50']}
            onChange={(nextPage, nextPageSize) => {
              setPage(nextPage)
              setPageSize(nextPageSize)
            }}
            showTotal={(t, range) => `${range[0]}-${range[1]} of ${t}`}
          />
        </div>
      </Card>

      <Modal
        title="Candidate Profile"
        open={profileOpen}
        onCancel={() => setProfileOpen(false)}
        footer={null}
        width={isMobile ? '100%' : 980}
        style={isMobile ? { top: 12, paddingBottom: 12 } : { top: 24 }}
      >
        <div className="max-h-[72vh] overflow-y-auto pr-1">
          {profileLoading && !selected
            ? <Card className={GLASS_CARD_CLASS} loading />
            : <CandidateInfoCard candidate={selected} onAtsPush={(candidateId) => void handleAtsPush(candidateId)} />}
        </div>
      </Modal>
    </Space>
  )
}

function CompareTab() {
  const screens = useBreakpoint()
  const [options, setOptions] = useState<CandidateProfile[]>([])
  const [candidateAId, setCandidateAId] = useState<string | undefined>(undefined)
  const [candidateBId, setCandidateBId] = useState<string | undefined>(undefined)
  const [candidateA, setCandidateA] = useState<CandidateProfile | null>(null)
  const [candidateB, setCandidateB] = useState<CandidateProfile | null>(null)

  useEffect(() => {
    void fetchCandidates({ page: 1, pageSize: 200, sort: 'name_asc' })
      .then((data) => setOptions(data.items))
      .catch(() => {
        // non-blocking
      })
  }, [])

  useEffect(() => {
    if (!candidateAId) {
      setCandidateA(null)
      return
    }
    void fetchCandidate(candidateAId).then(setCandidateA).catch(() => setCandidateA(null))
  }, [candidateAId])

  useEffect(() => {
    if (!candidateBId) {
      setCandidateB(null)
      return
    }
    void fetchCandidate(candidateBId).then(setCandidateB).catch(() => setCandidateB(null))
  }, [candidateBId])

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Card className={GLASS_CARD_CLASS} title="Compare Candidates" extra={<SwapOutlined />}>
        <Paragraph type="secondary" className="mb-3">
          Pick any two profiles and compare extracted details side-by-side.
        </Paragraph>
        <Row gutter={[16, 12]}>
          <Col xs={24} md={12}>
            <Text type="secondary">Candidate A</Text>
            <Select
              className="mt-2 w-full"
              allowClear
              value={candidateAId}
              options={options.map((candidate) => ({ label: candidate.displayName, value: candidate.id }))}
              onChange={(value) => setCandidateAId(value)}
            />
          </Col>
          <Col xs={24} md={12}>
            <Text type="secondary">Candidate B</Text>
            <Select
              className="mt-2 w-full"
              allowClear
              value={candidateBId}
              options={options.map((candidate) => ({ label: candidate.displayName, value: candidate.id }))}
              onChange={(value) => setCandidateBId(value)}
            />
          </Col>
        </Row>
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={12}><CandidateInfoCard candidate={candidateA} /></Col>
        <Col xs={24} xl={12}><CandidateInfoCard candidate={candidateB} /></Col>
      </Row>
      {!screens.md && (
        <Alert
          type="info"
          showIcon
          message="Tip: rotate your device for easier side-by-side comparison."
        />
      )}
    </Space>
  )
}

function MatchTab({ onOpenCandidate }: { onOpenCandidate: (candidateId: string) => void }) {
  const [jobDescription, setJobDescription] = useState('')
  const [mustHaveSkills, setMustHaveSkills] = useState('')
  const [minScore, setMinScore] = useState(0.75)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<JobMatchResponse | null>(null)

  async function runMatch(page = 1, pageSize = 10) {
    if (!jobDescription.trim()) {
      setError('Please provide a job description')
      return
    }
    setLoading(true)
    setError(null)
    try {
      const response = await matchCandidates({
        jobDescription: jobDescription.trim(),
        mustHaveSkills: mustHaveSkills
          .split(',')
          .map((skill) => skill.trim())
          .filter(Boolean),
        minScore,
        page,
        pageSize,
      })
      setResult(response)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Matching failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Card className={GLASS_CARD_CLASS} title="Job Match Scoring">
        <Space direction="vertical" size="middle" className="w-full">
          <Input.TextArea
            rows={4}
            value={jobDescription}
            onChange={(event) => setJobDescription(event.target.value)}
            placeholder="Paste a job description to rank candidates by fit."
          />
          <Input
            value={mustHaveSkills}
            onChange={(event) => setMustHaveSkills(event.target.value)}
            placeholder="Optional must-have skills (comma-separated)"
          />
          <Space align="center" wrap>
            <Text type="secondary">Minimum score</Text>
            <InputNumber
              min={0}
              max={1}
              step={0.01}
              value={minScore}
              onChange={(value) => {
                if (typeof value === 'number' && !Number.isNaN(value)) {
                  setMinScore(value)
                }
              }}
            />
          </Space>
          <Space wrap>
            <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={() => void runMatch(1, result?.pageSize ?? 10)}>
              Rank Candidates
            </Button>
          </Space>
          {error && <Alert type="error" showIcon message={error} />}
          {result && (
            <Space direction="vertical" size="middle" className="w-full">
              <Space wrap>
                <Tag color="blue">Total matches: {result.total}</Tag>
                <Tag color="cyan">Min score: {minScore.toFixed(2)}</Tag>
                <Tag color="processing">
                  Must-have applied: {(result.inferredMustHaveSkills ?? []).join(', ') || 'Auto-detect unavailable'}
                </Tag>
              </Space>
              {result.total === 0 && (
                <Alert type="info" showIcon message="No candidates matched this job description at the selected threshold." />
              )}
              <Table
                rowKey={(item) => item.candidateId}
                loading={loading}
                dataSource={result.items}
                pagination={{
                  current: result.page,
                  pageSize: result.pageSize,
                  total: result.total,
                  showSizeChanger: true,
                  onChange: (page, pageSize) => {
                    void runMatch(page, pageSize)
                  },
                }}
                columns={[
                  {
                    title: 'Candidate',
                    dataIndex: 'displayName',
                    key: 'displayName',
                    render: (displayName: string, row: JobMatchResponse['items'][number]) => (
                      <Button type="link" icon={<UserOutlined />} onClick={() => onOpenCandidate(row.candidateId)}>
                        {displayName}
                      </Button>
                    ),
                  },
                  {
                    title: 'Overall',
                    dataIndex: 'overallScore',
                    key: 'overallScore',
                    width: 120,
                    render: (value: number) => <Tag color={scoreTagColor(value)}>{value.toFixed(3)}</Tag>,
                  },
                  {
                    title: 'Must-have',
                    dataIndex: 'mustHaveCoverage',
                    key: 'mustHaveCoverage',
                    width: 130,
                    render: (_: number, row: JobMatchResponse['items'][number]) => {
                      const matched = Array.isArray(row.matchedSkills) ? row.matchedSkills.length : 0
                      const missing = Array.isArray(row.missingMustHave) ? row.missingMustHave.length : 0
                      const total = matched + missing
                      if (total === 0) {
                        return <Text type="secondary">-</Text>
                      }
                      const pct = (matched / total) * 100
                      return `${pct.toFixed(0)}% (${matched}/${total})`
                    },
                  },
                  {
                    title: 'Skill',
                    dataIndex: 'skillCoverage',
                    key: 'skillCoverage',
                    width: 110,
                    render: (value: number) => (
                      Number.isFinite(value)
                        ? `${(Math.max(0, Math.min(1, value)) * 100).toFixed(0)}%`
                        : '-'
                    ),
                  },
                  {
                    title: 'Roles',
                    dataIndex: 'suggestedRoles',
                    key: 'suggestedRoles',
                    width: 240,
                    render: (values?: string[]) => {
                      const roles = Array.isArray(values) ? values : []
                      return roles.length > 0
                        ? roles.slice(0, 3).map((value) => <Tag color="geekblue" key={value}>{value}</Tag>)
                        : <Text type="secondary">-</Text>
                    },
                  },
                  {
                    title: 'Missing Must-have',
                    dataIndex: 'missingMustHave',
                    key: 'missingMustHave',
                    render: (values?: string[]) => {
                      const normalized = Array.isArray(values) ? values : []
                      return normalized.length > 0
                        ? normalized.map((value) => <Tag color="orange" key={value}>{value}</Tag>)
                        : <Text type="secondary">-</Text>
                    },
                  },
                ]}
                scroll={{ x: 920 }}
              />
            </Space>
          )}
        </Space>
      </Card>
    </Space>
  )
}

function EvalTab() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<EvalRunResponse | null>(null)

  async function handleRunEval() {
    setLoading(true)
    setError(null)
    try {
      const response = await runEval({ minScore: 0.75, useFeedbackTuning: true })
      setResult(response)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to run evaluation')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Card className={GLASS_CARD_CLASS} title="RAG Evaluation">
        <Space direction="vertical" size="middle" className="w-full">
          <Paragraph type="secondary" className="mb-0">
            Run built-in quality checks to track retrieval relevance and confidence over time.
          </Paragraph>
          <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={() => void handleRunEval()}>
            Run Evaluation Suite
          </Button>
          {error && <Alert type="error" showIcon message={error} />}
          {result && (
            <Space direction="vertical" size="middle" className="w-full">
              <Row gutter={[12, 12]}>
                <Col xs={12} md={6}><Statistic title="Queries" value={result.totalQueries} /></Col>
                <Col xs={12} md={6}><Statistic title="Avg Term Recall" value={(result.averageTermRecall * 100).toFixed(1)} suffix="%" /></Col>
                <Col xs={12} md={6}><Statistic title="Avg Source Recall" value={(result.averageSourceRecall * 100).toFixed(1)} suffix="%" /></Col>
                <Col xs={12} md={6}><Statistic title="Avg Confidence" value={result.averageConfidence.toFixed(3)} /></Col>
              </Row>
              <Table
                rowKey={(item) => item.question}
                dataSource={result.cases}
                pagination={false}
                columns={[
                  { title: 'Query', dataIndex: 'question', key: 'question' },
                  { title: 'Term Recall', dataIndex: 'termRecall', key: 'termRecall', width: 120, render: (value: number) => `${(value * 100).toFixed(0)}%` },
                  { title: 'Source Recall', dataIndex: 'sourceRecall', key: 'sourceRecall', width: 130, render: (value: number) => `${(value * 100).toFixed(0)}%` },
                  { title: 'Confidence', dataIndex: 'confidenceScore', key: 'confidenceScore', width: 120, render: (value: number) => <Tag color={scoreTagColor(value)}>{value.toFixed(3)}</Tag> },
                ]}
                scroll={{ x: 760 }}
              />
            </Space>
          )}
        </Space>
      </Card>
    </Space>
  )
}

function IngestTab() {
  const [loading, setLoading] = useState(false)
  const [jobsLoading, setJobsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [events, setEvents] = useState<IngestProgressEvent[]>([])
  const [lastResult, setLastResult] = useState<number | null>(null)
  const [ingestJobs, setIngestJobs] = useState<IngestJobStatus[]>([])
  const [selectedFiles, setSelectedFiles] = useState<File[]>([])
  const [selectedFolderName, setSelectedFolderName] = useState<string>('')
  const folderInputRef = useRef<HTMLInputElement | null>(null)
  const fileListRef = useRef<HTMLUListElement | null>(null)

  const fileEvents = events.filter((event) => event.type === 'file')
  const selectedPdfFiles = useMemo(
    () => selectedFiles.filter((file) => file.name.toLowerCase().endsWith('.pdf')),
    [selectedFiles]
  )
  const selectedPdfCount = selectedPdfFiles.length
  const uploadBatches = useMemo(
    () => splitUploadBatches(selectedPdfFiles),
    [selectedPdfFiles]
  )

  useEffect(() => {
    const input = folderInputRef.current
    if (!input) return
    input.setAttribute('webkitdirectory', '')
    input.setAttribute('directory', '')
    input.setAttribute('mozdirectory', '')
  }, [])

  useEffect(() => {
    if (!fileListRef.current) return
    fileListRef.current.scrollTop = fileListRef.current.scrollHeight
  }, [fileEvents.length])

  async function loadJobs() {
    setJobsLoading(true)
    try {
      const jobs = await listIngestJobs(20)
      setIngestJobs(jobs)
    } catch {
      // best effort only
    } finally {
      setJobsLoading(false)
    }
  }

  useEffect(() => {
    void loadJobs()
  }, [])

  useEffect(() => {
    const hasRunningJob = ingestJobs.some((job) => job.status === 'queued' || job.status === 'running')
    if (!hasRunningJob) return
    const interval = window.setInterval(() => {
      void loadJobs()
    }, 3000)
    return () => window.clearInterval(interval)
  }, [ingestJobs])

  function handleChooseFolder() {
    folderInputRef.current?.click()
  }

  function handleFolderSelected(event: ChangeEvent<HTMLInputElement>) {
    const nextFiles = Array.from(event.target.files ?? [])
    setSelectedFiles(nextFiles)
    const first = nextFiles[0] as (File & { webkitRelativePath?: string }) | undefined
    const relativePath = first?.webkitRelativePath ?? ''
    const folderName = relativePath.includes('/') ? relativePath.split('/')[0] : ''
    setSelectedFolderName(folderName || '')
  }

  async function uploadWithAdaptiveSplitting(files: File[]): Promise<{ processed: number; events: IngestProgressEvent[] }> {
    if (files.length === 0) {
      return { processed: 0, events: [] }
    }

    try {
      const response = await triggerIngestUpload(files)
      return { processed: response.documentsProcessed, events: response.fileEvents }
    } catch (err) {
      const message = err instanceof Error ? err.message.toLowerCase() : ''
      const tooLarge = message.includes('upload too large')
        || message.includes('maximum upload size exceeded')
        || message.includes('payload too large')

      if (tooLarge && files.length > 1) {
        const midpoint = Math.ceil(files.length / 2)
        const left = await uploadWithAdaptiveSplitting(files.slice(0, midpoint))
        const right = await uploadWithAdaptiveSplitting(files.slice(midpoint))
        return {
          processed: left.processed + right.processed,
          events: [...left.events, ...right.events],
        }
      }

      if (tooLarge && files.length === 1) {
        const file = files[0]
        return {
          processed: 0,
          events: [
            {
              type: 'file',
              filename: file.name,
              status: 'skipped',
              reason: 'File too large for upload endpoint. Use "Run Configured Server Folder" for this file.',
            },
          ],
        }
      }

      throw err
    }
  }

  async function handleUploadIngest() {
    if (selectedPdfFiles.length === 0) {
      setError('No PDF files selected. Please choose a folder containing .pdf resumes.')
      return
    }
    setLoading(true)
    setError(null)
    setEvents([])
    setLastResult(null)
    try {
      const aggregatedEvents: IngestProgressEvent[] = []
      let totalProcessed = 0

      for (let i = 0; i < uploadBatches.length; i++) {
        const partial = await uploadWithAdaptiveSplitting(uploadBatches[i])
        totalProcessed += partial.processed
        aggregatedEvents.push(...partial.events)
        setEvents([...aggregatedEvents])
        setLastResult(totalProcessed)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload ingest failed')
    } finally {
      setLoading(false)
    }
  }

  async function handleIngest() {
    setLoading(true)
    setError(null)
    setEvents([])
    setLastResult(null)
    await triggerIngestStream(
      (event) => setEvents((prev) => [...prev, event]),
      (err) => setError(err.message),
      (documentsProcessed) => {
        setLastResult(documentsProcessed)
        setLoading(false)
      }
    )
    setLoading(false)
  }

  async function handleStartBackgroundJob() {
    setError(null)
    try {
      await startIngestJob()
      await loadJobs()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start background ingest job')
    }
  }

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Card className={GLASS_CARD_CLASS} title="Ingest Resumes">
        <Space direction="vertical" size="middle" className="w-full">
          <input
            ref={folderInputRef}
            type="file"
            multiple
            className="hidden"
            onChange={handleFolderSelected}
          />
          <Paragraph type="secondary" className="mb-0">
            Run ingestion after adding/updating files in your resumes folder. Only `.pdf` files are accepted; non-PDF files are skipped and shown in the ingest log. This also refreshes candidate metadata and audit history.
          </Paragraph>
          <Space wrap>
            <Button size="large" onClick={handleChooseFolder}>
              Choose Folder
            </Button>
            <Button
              type="primary"
              size="large"
              icon={<CloudUploadOutlined />}
              loading={loading}
              disabled={selectedPdfFiles.length === 0}
              onClick={handleUploadIngest}
            >
              {loading ? 'Ingesting...' : 'Ingest Selected Folder'}
            </Button>
            <Button size="large" loading={loading} onClick={handleIngest}>
              Run Configured Server Folder
            </Button>
            <Button size="large" icon={<RocketOutlined />} onClick={() => void handleStartBackgroundJob()}>
              Run As Background Job
            </Button>
          </Space>

          <Alert
            type="info"
            showIcon
            message={selectedFolderName
              ? `Selected folder: ${selectedFolderName}`
              : 'No folder selected yet'}
            description={`Files selected: ${selectedFiles.length}. PDF files detected: ${selectedPdfCount}. Upload batches: ${uploadBatches.length || 0}.`}
          />

          {error && <Alert type="error" showIcon message={error} />}
          {lastResult !== null && <Alert type="success" showIcon message={`Ingestion completed. ${lastResult} document(s) processed.`} />}
          {(loading || fileEvents.length > 0) && (
            <Card className="rounded-xl border border-slate-200" title={loading ? 'Files (ingesting...)' : 'Files'}>
              <ul
                ref={fileListRef}
                className="m-0 max-h-80 list-disc overflow-y-auto pl-5"
              >
                {fileEvents.length === 0 && loading && (
                  <li className="text-slate-500">Waiting for files...</li>
                )}
                {fileEvents.map((event, index) => (
                  <li key={`${event.filename}-${event.status}-${index}`} className="mb-2">
                    <Text strong>{event.filename || 'Unknown file'}</Text>{' '}
                    {event.status === 'ingested' && <Text type="success">- ingested</Text>}
                    {event.status === 'skipped' && (
                      <Text type="warning">
                        - skipped{event.reason ? `: ${event.reason}` : ''}
                      </Text>
                    )}
                  </li>
                ))}
              </ul>
            </Card>
          )}

          <Card
            className="rounded-xl border border-slate-200"
            title="Background Ingestion Jobs"
            extra={<Button size="small" icon={<ReloadOutlined />} onClick={() => void loadJobs()}>Refresh</Button>}
          >
            <Table<IngestJobStatus>
              rowKey={(job) => job.id}
              size="small"
              loading={jobsLoading}
              dataSource={ingestJobs}
              pagination={false}
              locale={{ emptyText: 'No jobs yet' }}
              columns={[
                { title: 'Started', dataIndex: 'startedAt', key: 'startedAt', render: (value: string) => formatDate(value) },
                { title: 'Status', dataIndex: 'status', key: 'status', render: (status: string) => <Tag color={status === 'completed' ? 'success' : status === 'failed' ? 'error' : 'processing'}>{status}</Tag> },
                { title: 'Processed', dataIndex: 'processed', key: 'processed', width: 110 },
                { title: 'Skipped', dataIndex: 'skipped', key: 'skipped', width: 100 },
                { title: 'Message', dataIndex: 'message', key: 'message', render: (message: string) => message ? <Text>{message}</Text> : <Text type="secondary">-</Text> },
              ]}
              scroll={{ x: 760 }}
            />
          </Card>
        </Space>
      </Card>
    </Space>
  )
}

function AuditTab() {
  type AuditFileRow = IngestAuditRun['files'][number]
  const [runs, setRuns] = useState<IngestAuditRun[]>([])
  const [metrics, setMetrics] = useState<MetricsSummary | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function loadAudit() {
    setLoading(true)
    setError(null)
    try {
      const [data, summary] = await Promise.all([
        fetchIngestAudit(30),
        fetchMetricsSummary().catch(() => null),
      ])
      setRuns(data)
      setMetrics(summary)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load audit history')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadAudit()
  }, [])

  const runColumns = useMemo<TableProps<IngestAuditRun>['columns']>(
    () => [
      {
        title: 'Started',
        dataIndex: 'startedAt',
        key: 'startedAt',
        render: (startedAt: string) => formatDate(startedAt),
      },
      {
        title: 'Processed',
        dataIndex: 'processed',
        key: 'processed',
        width: 120,
      },
      {
        title: 'Skipped',
        dataIndex: 'skipped',
        key: 'skipped',
        width: 120,
      },
      {
        title: 'Finished',
        dataIndex: 'finishedAt',
        key: 'finishedAt',
        render: (finishedAt: string) => formatDate(finishedAt),
      },
    ],
    []
  )

  const fileColumns = useMemo<TableProps<AuditFileRow>['columns']>(
    () => [
      {
        title: 'File',
        dataIndex: 'filename',
        key: 'filename',
        render: (filename: string) => <Text strong>{filename}</Text>,
      },
      {
        title: 'Status',
        dataIndex: 'status',
        key: 'status',
        width: 130,
        render: (status: 'ingested' | 'skipped') => (
          <Tag color={status === 'ingested' ? 'success' : 'warning'}>{status}</Tag>
        ),
      },
      {
        title: 'Timestamp',
        dataIndex: 'timestamp',
        key: 'timestamp',
        width: 220,
        render: (timestamp: string) => formatDate(timestamp),
      },
      {
        title: 'Reason',
        dataIndex: 'reason',
        key: 'reason',
        render: (reason?: string) => reason ? <Text type="warning">{reason}</Text> : <Text type="secondary">-</Text>,
      },
    ],
    []
  )

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Card className={GLASS_CARD_CLASS} title="Ingestion Audit" extra={<Button icon={<ReloadOutlined />} onClick={() => void loadAudit()}>Refresh</Button>}>
        {metrics && (
          <Space direction="vertical" size="small" className="mb-4 w-full">
            <Row gutter={[12, 12]}>
              <Col xs={12} md={6}><Statistic title="Queries" value={metrics.queryCount} /></Col>
              <Col xs={12} md={6}><Statistic title="Query Errors" value={metrics.queryErrors} /></Col>
              <Col xs={12} md={6}><Statistic title="Avg Query Latency (ms)" value={metrics.avgQueryLatencyMs.toFixed(1)} /></Col>
              <Col xs={12} md={6}><Statistic title="Ingest Runs" value={metrics.ingestRunCount} /></Col>
            </Row>
            <Row gutter={[12, 12]}>
              <Col xs={12} md={8}>
                <Statistic title="LLM Extractions" value={metrics.candidateExtractionLlmAttempts ?? 0} />
              </Col>
              <Col xs={12} md={8}>
                <Statistic title="LLM Fallbacks" value={metrics.candidateExtractionLlmFailures ?? 0} />
              </Col>
              <Col xs={24} md={8}>
                <Statistic title="Validation Warnings" value={metrics.candidateExtractionValidationWarnings ?? 0} />
              </Col>
            </Row>
          </Space>
        )}
        {error && <Alert type="error" showIcon message={error} className="mb-3" />}
        {runs.length === 0 ? (
          <Empty description={loading ? 'Loading...' : 'No ingestion runs yet'} />
        ) : (
          <Table<IngestAuditRun>
            rowKey={(run) => run.id}
            size="small"
            columns={runColumns}
            dataSource={runs}
            loading={loading}
            pagination={{ pageSize: 10, showSizeChanger: false }}
            scroll={{ x: 760 }}
            expandable={{
              expandedRowRender: (run) => (
                <Space direction="vertical" size="small" className="w-full">
                  <Text type="secondary">Run ID: {run.id}</Text>
                  <Table<AuditFileRow>
                    rowKey={(file) => `${run.id}-${file.filename}-${file.timestamp}`}
                    size="small"
                    columns={fileColumns}
                    dataSource={run.files}
                    pagination={false}
                    scroll={{ x: 760 }}
                  />
                </Space>
              ),
            }}
          />
        )}
      </Card>
    </Space>
  )
}

export default function App() {
  const screens = useBreakpoint()
  const isMobile = !screens.lg
  const initialRoute = parseTabRouteFromHash(typeof window !== 'undefined' ? window.location.hash : '')
  const [activeTab, setActiveTab] = useState<TabKey>(initialRoute.tab)
  const [selectedCandidateId, setSelectedCandidateId] = useState<string | undefined>(initialRoute.candidateId)

  function syncHash(tab: TabKey, candidateId?: string, mode: 'push' | 'replace' = 'push') {
    if (typeof window === 'undefined') return
    const nextHash = buildHashForTab(tab, candidateId)
    if (window.location.hash === nextHash) return
    const base = window.location.href.split('#')[0]
    if (mode === 'replace') {
      window.history.replaceState(null, '', `${base}${nextHash}`)
    } else {
      window.location.hash = nextHash
    }
  }

  useEffect(() => {
    if (typeof window === 'undefined') return
    if (!window.location.hash || !window.location.hash.startsWith('#/')) {
      syncHash(activeTab, selectedCandidateId, 'replace')
    }

    function onHashChange() {
      const next = parseTabRouteFromHash(window.location.hash)
      setActiveTab(next.tab)
      setSelectedCandidateId(next.candidateId)
    }

    window.addEventListener('hashchange', onHashChange)
    return () => {
      window.removeEventListener('hashchange', onHashChange)
    }
  }, [])

  function handleTabChange(key: string) {
    const nextTab = TAB_KEYS.find((tab) => tab === key) ?? 'ingest'
    setActiveTab(nextTab)
    if (nextTab !== 'candidates') {
      setSelectedCandidateId(undefined)
      syncHash(nextTab)
      return
    }
    syncHash('candidates', selectedCandidateId)
  }

  function openCandidate(candidateId: string) {
    setSelectedCandidateId(candidateId)
    setActiveTab('candidates')
    syncHash('candidates', candidateId)
  }

  return (
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: '#0f7a62',
          borderRadius: 12,
          colorBgLayout: '#f8fafc',
        },
      }}
    >
      <Layout className="min-h-screen bg-slate-50">
        <Header
          className="h-auto border-b border-slate-800 bg-slate-900 px-4 py-3 md:px-6"
          style={{ lineHeight: 'normal' }}
        >
          <div className="mx-auto flex w-full max-w-[1360px] items-center">
            <div className="text-[22px] font-semibold tracking-tight text-white">
              Resume RAG Studio
            </div>
          </div>
        </Header>
        <Content className="px-3 py-4 md:px-6 md:py-6">
          <div className="mx-auto w-full max-w-[1360px]">
          <Tabs
            activeKey={activeTab}
            onChange={handleTabChange}
            tabPosition="top"
            size={isMobile ? 'middle' : 'large'}
            className="[&_.ant-tabs-nav]:mb-4 [&_.ant-tabs-tab]:px-3 [&_.ant-tabs-nav::before]:border-slate-200 [&_.ant-tabs-content-holder]:p-0"
            items={[
              {
                key: 'ingest',
                label: 'Ingest',
                children: <IngestTab />,
              },
              {
                key: 'chat',
                label: (
                  <Space size={6}>
                    <MessageOutlined />
                    Chat
                  </Space>
                ),
                children: <ChatTab onOpenCandidate={openCandidate} />,
              },
              {
                key: 'query',
                label: 'Query',
                children: <QueryTab onOpenCandidate={openCandidate} />,
              },
              {
                key: 'match',
                label: 'Match',
                children: <MatchTab onOpenCandidate={openCandidate} />,
              },
              {
                key: 'candidates',
                label: 'Candidates',
                children: (
                  <CandidatesTab
                    selectedCandidateId={selectedCandidateId}
                    isActive={activeTab === 'candidates'}
                  />
                ),
              },
              {
                key: 'compare',
                label: 'Compare',
                children: <CompareTab />,
              },
              {
                key: 'audit',
                label: 'Audit',
                children: <AuditTab />,
              },
              {
                key: 'eval',
                label: 'Eval',
                children: <EvalTab />,
              },
            ]}
            style={{ minHeight: 0 }}
          />
          </div>
        </Content>
      </Layout>
    </ConfigProvider>
  )
}
