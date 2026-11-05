<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { cancelChat, fetchHealth, fetchMetrics, streamChat } from './api/chat'
import type { MetricsSnapshot } from './api/chat'
import { renderMarkdown, renderStreaming } from './markdown'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  thinking?: string
  streaming: boolean
  cancelled?: boolean
  /** 追问被改写成的独立问句，让用户确认系统理解成了什么 */
  rewritten?: string
  ttftMs?: number
  totalMs?: number
  tokens?: { prompt: number; completion: number }
  cost?: number
}

const sessionId = crypto.randomUUID()
const messages = ref<Message[]>([])
const input = ref('')
const busy = ref(false)
const currentRequestId = ref('')
const abortController = ref<AbortController | null>(null)
const listRef = ref<HTMLElement | null>(null)
const health = ref<{ model: string; fastModel?: string; mock: boolean } | null>(null)
const metrics = ref<MetricsSnapshot | null>(null)
const title = ref('')

const canSend = computed(() => !busy.value && input.value.trim().length > 0)
const modelLabel = computed(() => (health.value ? shortModel(health.value.model) : ''))
const fastLabel = computed(() =>
  health.value?.fastModel ? shortModel(health.value.fastModel) : ''
)

onMounted(async () => {
  try {
    health.value = await fetchHealth()
  } catch {
    health.value = null
  }
  refreshMetrics()
})

function shortModel(model: string): string {
  const name = model.split('/').pop() ?? model
  return name.replace('-Instruct', '').replace('deepseek-ai', '')
}

async function refreshMetrics() {
  try {
    metrics.value = await fetchMetrics()
  } catch {
    // 计量是旁路能力，取不到不影响对话
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || busy.value) return

  input.value = ''
  busy.value = true
  messages.value.push({ id: crypto.randomUUID(), role: 'user', content: text, streaming: false })

  // ⚠️ 必须用 reactive 包一层：push 进 ref([]) 的是原始对象，
  // 直接改 reply.content / reply.thinking 会绕过 Proxy 的 set 陷阱，
  // Vue 收不到依赖通知 —— 泵在后台一格格加字，屏幕上却纹丝不动，
  // 只有 busy/title/metrics 这些真响应式的值变化时才「顺手」重渲一次，
  // 于是整段文字一次性糊上去（2026-11-05 实锤：29 秒内 DOM 零更新）。
  const reply = reactive<Message>({
    id: crypto.randomUUID(),
    role: 'assistant',
    content: '',
    thinking: '',
    streaming: true
  })
  messages.value.push(reply)

  const startedAt = performance.now()
  let firstTokenAt = 0
  const controller = new AbortController()
  abortController.value = controller
  await scrollToBottom()

  // 正文走「打字机泵」：硅基流动输出可达 300 字/秒，「到达即渲染」在人眼里
  // 就是整段闪现。先攒进 pending，按自适应速率匀速放出——积压越多放得越快，
  // 最终自动追平；30fps 更新也把全文 markdown 重渲的频率压在主线程可承受范围。
  // 打字机泵：实测知了爱学 ≈30-40 字/秒匀速输出，观感最优雅。
  // 步长 = 1 + 积压/80（上限 8）：开头是舒适的 ~30 字/秒，长文积压大时自动提速。
  const TICK_MS = 33
  let contentBuf = '' // 网络已到达的完整正文（done 后用于立即补完）
  let thinkBuf = ''
  let pendingText = '' // 尚未放到屏幕上的正文
  let pumpTimer: number | null = null

  const startPump = () => {
    if (pumpTimer !== null) return
    pumpTimer = window.setInterval(() => {
      if (!pendingText) return
      const step = Math.min(8, 1 + Math.floor(pendingText.length / 80))
      reply.content += pendingText.slice(0, step)
      pendingText = pendingText.slice(step)
      scheduleScroll()
    }, TICK_MS)
  }
  const stopPump = () => {
    if (pumpTimer !== null) {
      clearInterval(pumpTimer)
      pumpTimer = null
    }
  }
  /** 等泵把积压全部放到屏幕上（done 之后收尾用，避免最后一段闪现） */
  const waitForPump = () =>
    new Promise<void>((resolve) => {
      const check = () => (pendingText ? requestAnimationFrame(check) : resolve())
      check()
    })

  // 思考过程只求不卡：rAF 合帧，不做限速
  let thinkQueued = false
  const queueThinking = () => {
    if (thinkQueued) return
    thinkQueued = true
    requestAnimationFrame(() => {
      thinkQueued = false
      reply.thinking = thinkBuf
      scheduleScroll()
    })
  }

  try {
    await streamChat(
      sessionId,
      text,
      {
        onMeta: (meta) => {
          currentRequestId.value = meta.requestId
        },
        onRewrite: (question) => {
          reply.rewritten = question
        },
        onThinking: (chunk) => {
          if (!firstTokenAt) firstTokenAt = performance.now()
          thinkBuf += chunk
          queueThinking()
        },
        onDelta: (chunk) => {
          if (!firstTokenAt) firstTokenAt = performance.now()
          contentBuf += chunk
          pendingText += chunk
          startPump()
        },
        onDone: (payload) => {
          if (payload.title) {
            title.value = payload.title
            document.title = `${payload.title} · 软考助手`
          }
          if (payload.promptTokens !== undefined) {
            reply.tokens = {
              prompt: payload.promptTokens,
              completion: payload.completionTokens ?? 0
            }
            reply.cost = payload.costCny
          }
        },
        onCancelled: () => {
          reply.cancelled = true
        },
        onError: (payload) => {
          pendingText += `\n\n> 出错了：${payload.message}`
        }
      },
      controller.signal
    )
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      pendingText += `\n\n> 连接失败：${(e as Error).message}`
    }
  } finally {
    // 用户主动停止：不等打字机，立即补完整内容
    if (reply.cancelled || (abortController.value?.signal.aborted ?? false)) {
      reply.content = contentBuf
    } else {
      await waitForPump()
    }
    stopPump()
    reply.thinking = thinkBuf
    reply.streaming = false
    reply.ttftMs = firstTokenAt ? Math.round(firstTokenAt - startedAt) : undefined
    reply.totalMs = Math.round(performance.now() - startedAt)
    busy.value = false
    currentRequestId.value = ''
    abortController.value = null
    await scrollToBottom()
    refreshMetrics()
  }
}

async function stop() {
  if (currentRequestId.value) {
    await cancelChat(currentRequestId.value).catch(() => false)
  }
  abortController.value?.abort()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

function render(message: Message): string {
  return message.streaming ? renderStreaming(message.content) : renderMarkdown(message.content)
}

let scrollQueued = false
function scheduleScroll() {
  if (scrollQueued) return
  scrollQueued = true
  requestAnimationFrame(() => {
    scrollQueued = false
    scrollToBottom()
  })
}

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}
</script>

<template>
  <div class="app">
    <header class="bar">
      <div class="brand">
        <span class="dot" />
        <strong>{{ title || '软考助手' }}</strong>
        <span class="muted">{{ title ? '会话中' : '流式对话 MVP' }}</span>
      </div>
      <div class="status">
        <span v-if="metrics && metrics.total.calls > 0" class="chip spend">
          ¥{{ metrics.total.costCny.toFixed(4) }} · {{ metrics.total.calls }} 次调用
        </span>
        <span v-if="health" class="chip" :class="{ mock: health.mock }">
          {{ health.mock ? 'mock 模式' : modelLabel }}
        </span>
        <span v-if="fastLabel && !health?.mock" class="chip fast" title="辅助任务模型">
          辅助 {{ fastLabel }}
        </span>
        <span v-if="!health" class="chip error">后端未连接</span>
      </div>
    </header>

    <main ref="listRef" class="messages">
      <div v-if="messages.length === 0" class="empty">
        <h1>问点什么开始</h1>
        <p class="muted">选中科目的知识点、真题解析、备考计划都可以问。</p>
        <div class="samples">
          <button v-for="s in ['可用度怎么算？', '系统架构设计考什么？', '给我一份 4 周备考计划']" :key="s"
            @click="input = s">{{ s }}</button>
        </div>
      </div>

      <article v-for="m in messages" :key="m.id" class="msg" :class="m.role">
        <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="bubble">
          <div v-if="m.rewritten" class="rewrite">
            <span class="rewrite-tag">已补全指代</span>{{ m.rewritten }}
          </div>
          <details v-if="m.thinking" class="think" :open="m.streaming">
            <summary>思考过程 · {{ m.thinking.length }} 字</summary>
            <div class="think-body">{{ m.thinking }}</div>
          </details>
          <div v-if="m.role === 'assistant' && m.streaming && !m.thinking && !m.content" class="dots">
            <span /><span /><span />
          </div>
          <div class="content" :class="{ typing: m.streaming && !!m.content }" v-html="render(m)" />
          <div v-if="m.role === 'assistant' && !m.streaming" class="meta">
            <span v-if="m.cancelled" class="tag warn">已停止</span>
            <span v-if="m.ttftMs">首字 {{ m.ttftMs }}ms</span>
            <span v-if="m.totalMs">总计 {{ m.totalMs }}ms</span>
            <span>{{ m.content.length }} 字</span>
            <span v-if="m.tokens" class="tokens">
              {{ m.tokens.prompt }} in / {{ m.tokens.completion }} out
            </span>
            <span v-if="m.cost !== undefined" class="cost">¥{{ m.cost.toFixed(4) }}</span>
          </div>
        </div>
      </article>
    </main>

    <footer class="composer">
      <textarea v-model="input" rows="1" placeholder="Enter 发送，Shift + Enter 换行" @keydown="onKeydown" />
      <button v-if="busy" class="danger" @click="stop">停止</button>
      <button v-else :disabled="!canSend" @click="send">发送</button>
    </footer>
  </div>
</template>
