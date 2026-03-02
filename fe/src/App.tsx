import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  ConfigProvider,
  InputNumber,
  Input,
  Layout,
  List,
  Pagination,
  Progress,
  Row,
  Space,
  Tag,
  Tabs,
  Typography,
} from 'antd'
import { SearchOutlined, CloudUploadOutlined } from '@ant-design/icons'
import { querySkills, type QueryResponse, type SourceSegment } from './api/query'
import { triggerIngestStream, type IngestProgressEvent } from './api/ingest'

const { Header, Content } = Layout
const { Title, Text, Paragraph } = Typography
const MAX_RESULTS_CAP = 200
const PREVIEW_CHARS = 260
const DEFAULT_SOURCE_PAGE_SIZE = 10

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
  segments: SourceSegment[]
}

function scoreTagColor(score: number): string {
  if (score >= 0.75) return 'success'
  if (score >= 0.5) return 'processing'
  if (score >= 0.3) return 'warning'
  return 'error'
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

function groupSourcesByDocument(sources: SourceSegment[]): GroupedSource[] {
  const grouped = new Map<string, GroupedSource>()
  for (const source of sources) {
    const sourceName = source.source || 'Unknown source'
    const existing = grouped.get(sourceName)
    if (existing) {
      existing.count += 1
      existing.bestScore = Math.max(existing.bestScore, source.score)
      existing.segments.push(source)
      continue
    }
    grouped.set(sourceName, {
      source: sourceName,
      count: 1,
      bestScore: source.score,
      segments: [source],
    })
  }

  return Array.from(grouped.values())
    .map((group) => ({
      ...group,
      segments: [...group.segments].sort((a, b) => b.score - a.score),
    }))
    .sort((a, b) => b.bestScore - a.bestScore || b.count - a.count)
}

function QueryTab() {
  const [question, setQuestion] = useState('')
  const [maxResults, setMaxResults] = useState(50)
  const [minScore, setMinScore] = useState(0.2)
  const [result, setResult] = useState<QueryResponse | null>(null)
  const [expandedSources, setExpandedSources] = useState<Record<string, boolean>>({})
  const [sourcePage, setSourcePage] = useState(1)
  const [sourcePageSize, setSourcePageSize] = useState(DEFAULT_SOURCE_PAGE_SIZE)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const structuredAnswer = useMemo(
    () => parseStructuredAnswer(result?.answer ?? ''),
    [result?.answer]
  )
  const totalSources = result?.sources.length ?? 0
  const pagedRankedSources = useMemo(() => {
    const sources = result?.sources ?? []
    const start = (sourcePage - 1) * sourcePageSize
    return sources.slice(start, start + sourcePageSize)
  }, [result?.sources, sourcePage, sourcePageSize])
  const groupedSources = useMemo(
    () => groupSourcesByDocument(result?.sources ?? []),
    [result?.sources]
  )

  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil(totalSources / sourcePageSize))
    if (sourcePage > totalPages) {
      setSourcePage(totalPages)
    }
  }, [totalSources, sourcePageSize, sourcePage])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!question.trim()) return
    setLoading(true)
    setError(null)
    setResult(null)
    setExpandedSources({})
    setSourcePage(1)
    try {
      const data = await querySkills(question.trim(), {
        maxResults,
        minScore,
      })
      setResult(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Request failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ maxWidth: 960, margin: '0 auto' }}>
      <Paragraph type="secondary" style={{ marginBottom: 24 }}>
        Ask about skills or experience in the resume corpus. Responses include structured sections and ranked evidence.
      </Paragraph>

      <Card title="Query" size="small" style={{ marginBottom: 24 }}>
        <form onSubmit={handleSubmit}>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Space.Compact style={{ width: '100%' }}>
              <Input
                size="large"
                placeholder="e.g. Who has Java experience?"
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                disabled={loading}
                allowClear
              />
              <Button
                type="primary"
                size="large"
                htmlType="submit"
                loading={loading}
                icon={<SearchOutlined />}
              >
                Ask
              </Button>
            </Space.Compact>

            <Row gutter={[16, 12]}>
              <Col xs={24} md={12}>
                <Text type="secondary">Max sources ({1}-{MAX_RESULTS_CAP})</Text>
                <div>
                  <InputNumber
                    min={1}
                    max={MAX_RESULTS_CAP}
                    value={maxResults}
                    onChange={(value) => setMaxResults(value ?? 50)}
                    disabled={loading}
                    style={{ width: '100%', marginTop: 6 }}
                  />
                </div>
              </Col>
              <Col xs={24} md={12}>
                <Text type="secondary">Minimum score ({minScore.toFixed(2)})</Text>
                <div style={{ marginTop: 6, paddingInline: 4 }}>
                  <InputNumber
                    min={0}
                    max={1}
                    step={0.05}
                    value={minScore}
                    onChange={(value) => setMinScore(value ?? 0)}
                    disabled={loading}
                    style={{ width: '100%' }}
                  />
                </div>
              </Col>
            </Row>
          </Space>
        </form>
      </Card>

      {error && (
        <Alert
          type="error"
          message={error}
          showIcon
          closable
          onClose={() => setError(null)}
          style={{ marginBottom: 24 }}
        />
      )}

      {result && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Card title="Answer" size="small">
            {structuredAnswer ? (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <div>
                  <Text strong>Direct Answer</Text>
                  <Paragraph style={{ marginBottom: 0, marginTop: 8, whiteSpace: 'pre-wrap' }}>
                    {structuredAnswer.answer}
                  </Paragraph>
                </div>
                {structuredAnswer.keyFindings.length > 0 && (
                  <div>
                    <Text strong>Key Findings</Text>
                    <List
                      size="small"
                      dataSource={structuredAnswer.keyFindings}
                      renderItem={(item, i) => <List.Item key={`finding-${i}`}>{item}</List.Item>}
                      style={{ marginTop: 8 }}
                    />
                  </div>
                )}
                {structuredAnswer.limitations.length > 0 && (
                  <div>
                    <Text strong>Limitations</Text>
                    <List
                      size="small"
                      dataSource={structuredAnswer.limitations}
                      renderItem={(item, i) => <List.Item key={`limitation-${i}`}>{item}</List.Item>}
                      style={{ marginTop: 8 }}
                    />
                  </div>
                )}
                {structuredAnswer.nextSteps.length > 0 && (
                  <div>
                    <Text strong>Next Steps</Text>
                    <List
                      size="small"
                      dataSource={structuredAnswer.nextSteps}
                      renderItem={(item, i) => <List.Item key={`next-step-${i}`}>{item}</List.Item>}
                      style={{ marginTop: 8 }}
                    />
                  </div>
                )}
              </Space>
            ) : (
              <Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                {result.answer}
              </Paragraph>
            )}
          </Card>
          <Card
            title={`Sources (${result.sources.length})`}
            size="small"
            extra={
              <Text type="secondary">
                Requested up to {maxResults} · min score {minScore.toFixed(2)}
              </Text>
            }
          >
            {result.sources.length === 0 ? (
              <Alert
                type="info"
                showIcon
                message="No source segments matched the current threshold."
              />
            ) : (
              <Tabs
                defaultActiveKey="ranked"
                items={[
                  {
                    key: 'ranked',
                    label: `Ranked Excerpts (${result.sources.length})`,
                    children: (
                      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                        <Pagination
                          current={sourcePage}
                          total={totalSources}
                          pageSize={sourcePageSize}
                          showSizeChanger
                          pageSizeOptions={['5', '10', '20', '50']}
                          hideOnSinglePage={false}
                          showTotal={(total, range) => `${range[0]}-${range[1]} of ${total} sources`}
                          onChange={(page, pageSize) => {
                            setSourcePage(page)
                            setSourcePageSize(pageSize)
                          }}
                        />
                        <List
                          dataSource={pagedRankedSources}
                          split={false}
                          renderItem={(s, i) => {
                            const rank = (sourcePage - 1) * sourcePageSize + i + 1
                            const sourceKey = `${s.source}|${s.score}|${s.text.slice(0, 80)}`
                            const isExpanded = expandedSources[sourceKey] ?? false
                            const text = isExpanded || s.text.length <= PREVIEW_CHARS
                              ? s.text
                              : `${s.text.slice(0, PREVIEW_CHARS)}...`
                          return (
                            <List.Item key={`${s.source}-${i}`} style={{ paddingInline: 0 }}>
                              <Card size="small" style={{ width: '100%' }}>
                                <Space direction="vertical" size="small" style={{ width: '100%' }}>
                                  <Space wrap>
                                    <Tag color="blue">#{rank}</Tag>
                                    <Text strong>{s.source || 'Unknown source'}</Text>
                                    <Tag color={scoreTagColor(s.score)}>
                                      score {s.score.toFixed(3)}
                                    </Tag>
                                  </Space>
                                  <Progress
                                    percent={Math.max(0, Math.min(100, s.score * 100))}
                                    size="small"
                                    showInfo={false}
                                  />
                                  <Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                                    {text}
                                  </Paragraph>
                                  {s.text.length > PREVIEW_CHARS && (
                                    <Button
                                      size="small"
                                      type="link"
                                      style={{ padding: 0, alignSelf: 'flex-start' }}
                                      onClick={() =>
                                        setExpandedSources((prev) => ({ ...prev, [sourceKey]: !isExpanded }))
                                      }
                                    >
                                      {isExpanded ? 'Show less' : 'Show full excerpt'}
                                    </Button>
                                  )}
                                </Space>
                              </Card>
                            </List.Item>
                          )
                        }}
                        />
                        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                          <Pagination
                            current={sourcePage}
                            total={totalSources}
                            pageSize={sourcePageSize}
                            showSizeChanger
                            pageSizeOptions={['5', '10', '20', '50']}
                            hideOnSinglePage={false}
                            onChange={(page, pageSize) => {
                              setSourcePage(page)
                              setSourcePageSize(pageSize)
                            }}
                          />
                        </div>
                      </Space>
                    ),
                  },
                  {
                    key: 'grouped',
                    label: `By Resume (${groupedSources.length})`,
                    children: (
                      <List
                        dataSource={groupedSources}
                        split={false}
                        pagination={{
                          pageSize: 8,
                          showSizeChanger: false,
                        }}
                        renderItem={(group) => (
                          <List.Item key={group.source} style={{ paddingInline: 0 }}>
                            <Card
                              size="small"
                              style={{ width: '100%' }}
                              title={group.source}
                              extra={
                                <Space size="small">
                                  <Tag>{group.count} excerpt(s)</Tag>
                                  <Tag color={scoreTagColor(group.bestScore)}>
                                    best {group.bestScore.toFixed(3)}
                                  </Tag>
                                </Space>
                              }
                            >
                              <List
                                size="small"
                                dataSource={group.segments.slice(0, 3)}
                                renderItem={(segment, idx) => (
                                  <List.Item key={`${group.source}-${idx}`}>
                                    <Space direction="vertical" size={2} style={{ width: '100%' }}>
                                      <Text type="secondary">
                                        score {segment.score.toFixed(3)}
                                      </Text>
                                      <Text>
                                        {segment.text.length > 200
                                          ? `${segment.text.slice(0, 200)}...`
                                          : segment.text}
                                      </Text>
                                    </Space>
                                  </List.Item>
                                )}
                              />
                              {group.count > 3 && (
                                <Text type="secondary">
                                  + {group.count - 3} more excerpt(s) for this resume in ranked view.
                                </Text>
                              )}
                            </Card>
                          </List.Item>
                        )}
                      />
                    ),
                  },
                ]}
              />
            )}
          </Card>
        </Space>
      )}
    </div>
  )
}

function IngestTab() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [events, setEvents] = useState<IngestProgressEvent[]>([])
  const [lastResult, setLastResult] = useState<number | null>(null)
  const fileListRef = useRef<HTMLUListElement>(null)

  const fileEvents = events.filter((e) => e.type === 'file')
  useEffect(() => {
    if (fileListRef.current) {
      fileListRef.current.scrollTop = fileListRef.current.scrollHeight
    }
  }, [fileEvents.length])

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

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      <Paragraph type="secondary" style={{ marginBottom: 24 }}>
        Ingest resumes from the configured folder into the vector store. Run this after adding or updating PDFs.
      </Paragraph>

      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Button
          type="primary"
          size="large"
          icon={<CloudUploadOutlined />}
          loading={loading}
          onClick={handleIngest}
        >
          {loading ? 'Ingesting…' : 'Run ingestion'}
        </Button>

        {error && (
          <Alert
            type="error"
            message={error}
            showIcon
            closable
            onClose={() => setError(null)}
          />
        )}

        {lastResult !== null && (
          <Alert
            type="success"
            message={`Ingestion completed. ${lastResult} document(s) processed.`}
            showIcon
          />
        )}

        {(loading || fileEvents.length > 0) && (
          <Card title={loading ? 'Files (ingesting…)' : 'Files'} size="small">
            <ul
              ref={fileListRef}
              style={{ margin: 0, paddingLeft: 20, maxHeight: 300, overflow: 'auto' }}
            >
              {fileEvents.length === 0 && loading && (
                <li key="placeholder" style={{ color: '#999' }}>Waiting for files…</li>
              )}
              {fileEvents.map((e, i) => (
                <li key={i} style={{ marginBottom: 4 }}>
                  <Text strong>{e.filename}</Text>
                  {e.status === 'ingested' && (
                    <Text type="success"> — ingested</Text>
                  )}
                  {e.status === 'skipped' && (
                    <Text type="warning"> — skipped{e.reason ? `: ${e.reason}` : ''}</Text>
                  )}
                </li>
              ))}
            </ul>
          </Card>
        )}
      </Space>
    </div>
  )
}

export default function App() {
  return (
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: '#1677ff',
          borderRadius: 8,
        },
      }}
    >
      <Layout style={{ minHeight: '100vh' }}>
        <Header
          style={{
            display: 'flex',
            alignItems: 'center',
            paddingInline: 24,
            background: '#001529',
          }}
        >
          <Title level={4} style={{ margin: 0, color: '#fff' }}>
            RAG POC — Skills on Resumes
          </Title>
        </Header>
        <Content style={{ padding: 24 }}>
          <Tabs
            defaultActiveKey="query"
            tabPosition="left"
            items={[
              {
                key: 'query',
                label: 'Query',
                children: <QueryTab />,
              },
              {
                key: 'ingest',
                label: 'Ingest',
                children: <IngestTab />,
              },
            ]}
            style={{ minHeight: 400 }}
          />
        </Content>
      </Layout>
    </ConfigProvider>
  )
}
