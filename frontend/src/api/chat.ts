export interface MetaPayload {
  requestId: string
  sessionId: string
  model: string
  fastModel?: string
}

export interface DonePayload {
  length: number
  thinkingLength?: number
  promptTokens?: number
  completionTokens?: number
  reasoningTokens?: number
  costCny?: number
  /** 首轮结束后由快模型生成的会话标题 */
  title?: string
}

export interface StreamHandlers {
  onMeta?: (meta: MetaPayload) => void
  /** 多轮追问被改写成独立问句时触发，用于让用户确认「系统理解成了什么」 */
  onRewrite?: (question: string) => void
  onThinking?: (text: string) => void
  onDelta?: (text: string) => void
  onDone?: (payload: DonePayload) => void
  onCancelled?: (payload: { reason: string; partialLength: number }) => void
  onError?: (payload: { message: string }) => void
}

/**
 * 消费后端的 SSE 流。
 * 用 fetch + ReadableStream 手写解析，而不是 EventSource：
 * EventSource 只支持 GET、不能带 body、也不方便中途 abort。
 */
export async function streamChat(
  sessionId: string,
  message: string,
  handlers: StreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, message }),
    signal
  })

  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => '')
    throw new Error(`HTTP ${response.status} ${text.slice(0, 200)}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let sep: number
    while ((sep = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      dispatch(frame, handlers)
    }
  }
}

function dispatch(frame: string, handlers: StreamHandlers) {
  let event = 'message'
  const dataLines: string[] = []

  for (const rawLine of frame.split('\n')) {
    const line = rawLine.trimEnd()
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }

  if (dataLines.length === 0) return
  const raw = dataLines.join('\n')
  const payload = safeParse(raw)
  switch (event) {
    case 'meta':
      handlers.onMeta?.(payload as MetaPayload)
      break
    case 'rewrite':
      handlers.onRewrite?.((payload as { question: string }).question)
      break
    case 'thinking':
      handlers.onThinking?.((payload as { text: string }).text)
      break
    case 'delta':
      handlers.onDelta?.((payload as { text: string }).text)
      break
    case 'done':
      handlers.onDone?.(payload as DonePayload)
      break
    case 'cancelled':
      handlers.onCancelled?.(payload as { reason: string; partialLength: number })
      break
    case 'error':
      handlers.onError?.(payload as { message: string })
      break
  }
}

function safeParse(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

/** 服务端主动中断：用户点停止时调用，避免模型继续烧 token */
export async function cancelChat(requestId: string): Promise<boolean> {
  const res = await fetch('/api/chat/cancel', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId })
  })
  const data = (await res.json()) as { cancelled: boolean }
  return data.cancelled
}

export interface MetricsRow {
  model: string
  purpose: string
  calls: number
  promptTokens: number
  completionTokens: number
  avgLatencyMs: number
  costCny: number
}

export interface MetricsSnapshot {
  total: {
    calls: number
    promptTokens: number
    completionTokens: number
    avgLatencyMs: number
    costCny: number
  }
  breakdown: MetricsRow[]
}

export async function fetchMetrics(): Promise<MetricsSnapshot> {
  const res = await fetch('/api/metrics')
  return res.json()
}

export async function fetchHealth(): Promise<{
  status: string
  provider: string
  model: string
  fastModel?: string
  mock: boolean
}> {
  const res = await fetch('/api/health')
  return res.json()
}
