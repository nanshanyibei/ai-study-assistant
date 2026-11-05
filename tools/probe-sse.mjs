/**
 * SSE 节奏探针：记录每个 delta 帧的到达时刻、长度，并离线模拟前端打字机泵，
 * 用来判断「两段式输出」是网络节奏问题还是前端泵的问题。
 *
 * 用法：node tools/probe-sse.mjs "你的问题"
 */
const question = process.argv[2] || '详细讲讲软考系统架构设计师里的质量属性场景，要分点并举例，内容长一些'
const url = 'http://localhost:8080/api/chat/stream'

const t0 = performance.now()
const res = await fetch(url, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ sessionId: 'probe-' + Date.now(), message: question })
})

const reader = res.body.getReader()
const decoder = new TextDecoder()
let buffer = ''
const frames = []

const stamp = (m) => ((performance.now() - t0) / 1000).toFixed(2).padStart(6) + 's ' + m

while (true) {
  const { done, value } = await reader.read()
  if (done) break
  buffer += decoder.decode(value, { stream: true })
  let sep
  while ((sep = buffer.indexOf('\n\n')) !== -1) {
    const frame = buffer.slice(0, sep)
    buffer = buffer.slice(sep + 2)
    const at = performance.now() - t0
    let event = 'message'
    const data = []
    for (const raw of frame.split('\n')) {
      const line = raw.trimEnd()
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).trim())
    }
    let payload = {}
    try { payload = JSON.parse(data.join('\n')) } catch {}
    frames.push({ at, event, payload })
    if (event === 'delta') {
      console.log(stamp(`delta  +${String(payload.text?.length ?? 0).padStart(3)} 字  「${(payload.text ?? '').replace(/\n/g, '⏎').slice(0, 40)}」`))
    } else {
      console.log(stamp(`${event} ${JSON.stringify(payload).slice(0, 120)}`))
    }
  }
}

// 离线重放打字机泵（与 App.vue 同一套算法），看屏幕上的字数随时间怎么涨
const deltas = frames.filter((f) => f.event === 'delta')
const total = deltas.reduce((n, f) => n + (f.payload.text?.length ?? 0), 0)
const endAt = frames.at(-1)?.at ?? 0
const TICK = 33
let shown = 0
let pending = 0
let di = 0
const curve = []
for (let t = 0; t <= endAt + 500; t += TICK) {
  while (di < deltas.length && deltas[di].at <= t) {
    pending += deltas[di].payload.text.length
    di++
  }
  if (pending > 0) {
    const step = Math.min(8, 1 + Math.floor(pending / 80))
    const take = Math.min(step, pending)
    shown += take
    pending -= take
  }
  curve.push({ t: t / 1000, shown, pending })
}

console.log('\n=== 汇总 ===')
console.log(`总字数 ${total}，帧数 ${deltas.length}，末帧 ${(endAt / 1000).toFixed(2)}s`)
const gaps = deltas.slice(1).map((f, i) => ({ gap: f.at - deltas[i].at, at: f.at / 1000 }))
const big = gaps.filter((g) => g.gap > 300).sort((a, b) => b.gap - a.gap).slice(0, 6)
console.log('最大 6 个帧间隔：', big.map((g) => `${g.gap.toFixed(0)}ms@${g.at.toFixed(1)}s`).join('  ') || '无 >300ms')

// 屏幕字数「停滞」的时段：连续 >400ms 没有任何新字上屏
const stalls = []
let lastGrow = 0
for (const p of curve) {
  if (p.shown > (curve[curve.indexOf(p) - 1]?.shown ?? 0)) lastGrow = p.t
  else if (p.t - lastGrow > 0.4 && shown > 0) {
    const last = stalls.at(-1)
    if (last && p.t - last.end < 0.2) last.end = p.t
    else stalls.push({ from: lastGrow, end: p.t })
  }
}
console.log('上屏停滞时段：', stalls.slice(0, 8).map((s) => `${s.from.toFixed(1)}→${s.end.toFixed(1)}s`).join('  ') || '无')

const finalPump = curve.at(-1)
console.log(`泵收尾：网络在 ${(endAt / 1000).toFixed(2)}s 结束后，屏幕在 ${finalPump.t.toFixed(2)}s 追平（滞后 ${(finalPump.t - endAt / 1000).toFixed(2)}s）`)
console.log('\n前 3 秒每秒上屏字数：')
for (let s = 0; s < 3; s++) {
  const a = curve.find((p) => p.t >= s)?.shown ?? 0
  const b = curve.find((p) => p.t >= s + 1)?.shown ?? 0
  console.log(`  ${s}-${s + 1}s: ${b - a} 字`)
}
