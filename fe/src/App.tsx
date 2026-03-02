import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  ConfigProvider,
  Input,
  Layout,
  Space,
  Tabs,
  Typography,
} from 'antd'
import { SearchOutlined, CloudUploadOutlined } from '@ant-design/icons'
import { querySkills, type QueryResponse } from './api/query'
import { triggerIngestStream, type IngestProgressEvent } from './api/ingest'

const { Header, Content } = Layout
const { Title, Text, Paragraph } = Typography

function QueryTab() {
  const [question, setQuestion] = useState('')
  const [result, setResult] = useState<QueryResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!question.trim()) return
    setLoading(true)
    setError(null)
    setResult(null)
    try {
      const data = await querySkills(question.trim())
      setResult(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Request failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      <Paragraph type="secondary" style={{ marginBottom: 24 }}>
        Ask about skills or experience in the resume corpus.
      </Paragraph>

      <form onSubmit={handleSubmit}>
        <Space.Compact style={{ width: '100%', marginBottom: 24 }}>
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
      </form>

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
            <Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
              {result.answer}
            </Paragraph>
          </Card>
          {result.sources.length > 0 && (
            <Card title="Sources" size="small">
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {result.sources.map((s, i) => (
                  <li key={i} style={{ marginBottom: 8 }}>
                    <Text strong>{s.source}</Text>: {s.text.slice(0, 120)}…
                  </li>
                ))}
              </ul>
            </Card>
          )}
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
