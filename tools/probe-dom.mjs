/**
 * 真浏览器采样：用 Chrome CDP 打开本地页面，发一个问题，每 50ms 采一次
 * 「屏幕上实际渲染出来的正文字数」，用来定位「两段式输出」到底卡在哪。
 *
 * 用法：node tools/probe-dom.mjs "问题"
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9333
const PAGE = 'http://localhost:5173/'
const HEADLESS = process.env.HEADLESS !== '0'
const question = process.argv[2] || '详细讲讲软考系统架构设计师里的质量属性场景，要分点并举例'

const profile = mkdtempSync(join(tmpdir(), 'cdp-'))
const chrome = spawn(
  CHROME,
  [
    ...(HEADLESS ? ['--headless=new'] : ['--window-position=1980,1080']),
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${profile}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-backgrounding-occluded-windows',
    '--disable-renderer-backgrounding',
    '--disable-features=IntensiveWakeUpThrottling,CalculateNativeWinOcclusion',
    '--window-size=1000,760',
    'about:blank'
  ],
  { stdio: 'ignore' }
)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let version
for (let i = 0; i < 60; i++) {
  try {
    version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json()
    break
  } catch {
    await sleep(250)
  }
}
if (!version) {
  chrome.kill()
  throw new Error('Chrome 没起来')
}
console.log('browser:', version.Browser)

const target = await (
  await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(PAGE)}`, { method: 'PUT' })
).json()

const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((r) => (ws.onopen = r))
let seq = 0
const waiting = new Map()
ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data)
  if (msg.id && waiting.has(msg.id)) {
    const { resolve, reject } = waiting.get(msg.id)
    waiting.delete(msg.id)
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
  }
}
const send = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++seq
    waiting.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })

const evaluate = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ' ' + (r.result?.description ?? ''))
  return r.result.value
}

await send('Runtime.enable')
await send('Page.enable')
await sleep(2500)

// 注入采样器：每 50ms 记录一次屏幕正文长度 + 各区块结构，跑完后可取回
await evaluate(`
(() => {
  window.__samples = []
  window.__t0 = performance.now()
  const pick = () => {
    const nodes = document.querySelectorAll('.msg.assistant .content')
    const el = nodes[nodes.length - 1]
    if (!el) return null
    return {
      t: performance.now() - window.__t0,
      len: el.textContent.length,
      html: el.innerHTML.length,
      // 顶层块的数量和各自字数：用来判断「是不是分块吐出来的」
      blocks: [...el.children].map(c => c.textContent.length),
      typing: el.classList.contains('typing'),
      thinkLen: document.querySelectorAll('.think-body').length
        ? [...document.querySelectorAll('.think-body')].pop().textContent.length : 0
    }
  }
  window.__timer = setInterval(() => {
    const s = pick()
    if (s) window.__samples.push(s)
  }, 50)
})()
`)

// 输入问题并发送
await evaluate(`
(() => {
  const ta = document.querySelector('textarea')
  ta.value = ${JSON.stringify(question)}
  ta.dispatchEvent(new Event('input', { bubbles: true }))
  return true
})()
`)
await sleep(300)
await evaluate(`document.querySelector('.composer button:not(.danger)').click(), true`)

// 采样直到流结束（typing 消失）或超时
const started = Date.now()
let lastLen = -1
let idle = 0
let shots = 0
while (Date.now() - started < 180_000) {
  await sleep(200)
  // 正文上屏后抓两张截图，肉眼确认光标位置与观感
  if (shots < 2 && lastLen > 120) {
    const shot = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(join(import.meta.dirname, `shot-${shots}.png`), Buffer.from(shot.data, 'base64'))
    shots++
  }
  const st = await evaluate(`(() => {
    const a = window.__samples.at(-1)
    // .meta 只在 !streaming 时渲染，用它判断流真的结束了
    const finished = !!document.querySelector('.msg.assistant .meta')
    return { finished, len: a ? a.len : 0, samples: window.__samples.length }
  })()`)
  if (st.len === lastLen) idle++
  else idle = 0
  lastLen = st.len
  if (st.finished && st.samples > 20) break
}

const samples = await evaluate(`(clearInterval(window.__timer), window.__samples)`)
const visibility = await evaluate(`document.visibilityState`)
console.log('采样时页面可见性：', visibility)
writeFileSync(join(import.meta.dirname, 'last-samples.json'), JSON.stringify(samples))
ws.close()
chrome.kill()
try { rmSync(profile, { recursive: true, force: true }) } catch {}

// ===== 分析 =====
console.log(`\n采样 ${samples.length} 条，终点字数 ${samples.at(-1)?.len}`)

const rate = (from, to) => {
  const a = samples.find((s) => s.t >= from)
  const b = [...samples].reverse().find((s) => s.t <= to)
  if (!a || !b) return 'n/a'
  return `${b.len - a.len} 字 / ${((to - from) / 1000).toFixed(1)}s = ${((b.len - a.len) / ((to - from) / 1000)).toFixed(0)} 字/秒`
}

// 停滞：屏幕上字数连续 >400ms 不变，但后面还在涨（说明中途卡住过）
const stalls = []
let anchor = 0
for (let i = 1; i < samples.length; i++) {
  if (samples[i].len !== samples[i - 1].len) {
    const gap = samples[i].t - samples[anchor].t
    if (gap > 400 && anchor > 0) {
      stalls.push({ from: samples[anchor].t, to: samples[i].t, atLen: samples[anchor].len })
    }
    anchor = i
  }
}
console.log('\n中途停滞段（字数不变 >400ms 后又继续涨）：')
if (!stalls.length) console.log('  无')
for (const s of stalls.slice(0, 10)) {
  console.log(`  ${(s.from / 1000).toFixed(2)}s → ${(s.to / 1000).toFixed(2)}s  卡在 ${s.atLen} 字处，停 ${((s.to - s.from) / 1000).toFixed(2)}s`)
}

// 每半秒一行，直观看曲线
console.log('\n时间轴（每 0.5s）：')
let next = 0
for (const s of samples) {
  if (s.t / 1000 >= next) {
    const bar = '█'.repeat(Math.min(60, Math.round(s.len / 60)))
    console.log(
      `  ${(s.t / 1000).toFixed(1).padStart(5)}s  ${String(s.len).padStart(4)}字  块数${s.blocks.length}  html ${String(s.html).padStart(6)}  ${bar}`
    )
    next += 0.5
  }
}

console.log('\n分段速率：')
const end = samples.at(-1).t
for (let t = 0; t < end; t += 5000) {
  console.log(`  ${(t / 1000).toFixed(0)}-${((t + 5000) / 1000).toFixed(0)}s  ${rate(t, Math.min(t + 5000, end))}`)
}
