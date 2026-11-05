# 软考助手 · 流式对话 MVP

一个可运行的最小链路：**Vue3 前端 → SSE 流式 → Spring Boot 后端 → OpenAI 兼容模型接口**。
目标是先把「流式 + 取消 + 富文本渲染 + 上下文管理 + 双模型成本控制」这条最难的骨架跑通，再往上叠 RAG 和工具调用。

## 双模型分工

不是所有调用都值得用最强模型。主链路用质量档，旁路用成本档，两条路都各自计费可查。

| 角色 | 任务 | 模型 | 实测 |
|---|---|---|---|
| 主模型 | RAG 问答、长回答、工具调用 | `deepseek-ai/DeepSeek-V4.1-Flash` | TTFT 563ms，¥2/¥8 每百万 token |
| 快模型 | 会话标题、追问改写、上下文摘要 | `Qwen/Qwen3.5-27B`（关思考） | 标题 710ms，¥0.6/¥4.8 |

实测成本占比（一轮两问的会话）：主对话 ¥0.0175 / 辅助任务 ¥0.0004，**辅助任务成本约 2%**。

## 目录

```
ai-study-assistant/
├── backend/            Spring Boot 4 + Java 21
│   └── src/main/java/com/nanshan/study/
│       ├── chat/       对话编排（ChatService）、辅助任务（FastTaskService）
│       ├── llm/        LlmClient 抽象 + OpenAI 兼容实现 + Mock + 用量计量（UsageMeter）
│       ├── config/     配置绑定、双模型 Bean
│       └── web/        全局异常处理
├── frontend/           Vue3 + TS + Vite
│   └── src/
│       ├── api/chat.ts fetch + ReadableStream 手写 SSE 解析
│       ├── markdown.ts markdown-it + KaTeX 公式渲染
│       └── App.vue     对话界面（含首字延迟、token、成本统计）
└── docker-compose.yml  阶段二用：pgvector + Redis
```

## 启动

### 后端

```bash
cd backend
./run.sh                        # Windows: run.cmd（会自动加载 .env）
```

`.env` 已在 `.gitignore` 中，配置格式见 `.env.example`：

```ini
LLM_PROVIDER=openai-compat
LLM_BASE_URL=https://api.siliconflow.cn/v1
LLM_API_KEY=sk-xxx

# 主模型：质量优先
LLM_MODEL=deepseek-ai/DeepSeek-V4.1-Flash
LLM_PRICE_IN=2.0
LLM_PRICE_OUT=8.0

# 快模型：延迟与单价优先，留空则复用主模型
LLM_FAST_MODEL=Qwen/Qwen3.5-27B
LLM_FAST_PRICE_IN=0.6
LLM_FAST_PRICE_OUT=4.8
```

没有 `.env` 时自动退回 `mock` 模式，**不需要任何 Key** 也能看到流式效果。

两个价格配置只用于成本核算，不影响调用结果；换模型时记得同步改，否则 `/api/metrics` 的钱数是错的。

> **快模型的选型硬约束**：必须选「能关掉思考」的模型。
> 请求体会对快模型下发 `enable_thinking: false`，Qwen 系列支持，`stepfun-ai/Step-3.5-Flash` **不支持**（实测 48 个 token 全被推理吃掉、正文返回空）。
> 这类模型一旦落在辅助任务上，标题会变成一段思考过程。

### 前端

```bash
cd frontend
npm install
npm run dev                     # http://localhost:5173
```

Vite 已配置 `/api` 代理到 `localhost:8080`，不需要处理 CORS。

> 若启动时报 `Port xxxxx was already in use`，说明环境里有 `SERVER__PORT` / `SERVER_PORT` 变量覆盖了配置，加参数强制指定：`java -jar target/*.jar --server.port=8080`。

## SSE 协议约定

`POST /api/chat/stream`，请求体 `{ sessionId, message }`，响应为事件流：

| event | data | 说明 |
|---|---|---|
| `meta` | `{requestId, sessionId, model, fastModel}` | 首帧，前端拿到 requestId 用于取消 |
| `rewrite` | `{question}` | 多轮追问被改写成独立问句（仅短句/含指代时出现） |
| `thinking` | `{text}` | 推理模型的思考过程增量（V4 / R1） |
| `delta` | `{text}` | 正文增量 |
| `done` | `{length, thinkingLength, promptTokens, completionTokens, reasoningTokens, costCny, title?}` | 正常结束；`title` 仅在首轮出现 |
| `cancelled` | `{reason, partialLength}` | 被中断，附带已生成长度 |
| `error` | `{message}` | 上游或写流失败 |

其他接口：

| 接口 | 用途 |
|---|---|
| `POST /api/chat/cancel` | 主动取消一轮生成 |
| `GET /api/chat/{sessionId}/history` | 会话消息 |
| `GET /api/chat/{sessionId}/meta` | 标题、上下文摘要、轮次数（调试摘要压缩用） |
| `DELETE /api/chat/{sessionId}` | 清空会话 |
| `GET /api/metrics` | 按「模型 × 用途」聚合的调用量、token、平均延迟、人民币成本 |
| `DELETE /api/metrics` | 清零计量 |
| `GET /api/health` | 主模型与快模型标识 |

## 已经处理掉的问题（面试可讲）

1. **取消要穿透到上游**：客户端断开 → `SseEmitter.onCompletion` → `CancellationToken` → 读流线程下一帧退出，避免继续烧 token。
2. **残缺回答也要入库**：中断时把已生成部分写回上下文，保证用户看到的和模型记住的一致。
3. **SSE 写失败等于断连**：`emitter.send` 抛异常时立即取消上游，不空转。
4. **上下文不再硬截断**：超出窗口的旧对话暂存，由快模型异步压成 200 字摘要接回 system。
   硬截断会丢用户早期说过的约束（科目、目标、时间），摘要能保住这些。
5. **前端不用 EventSource**：EventSource 只能 GET、不能带 body、不能中途 abort，改用 fetch + ReadableStream 手写解析。
6. **首字延迟可观测**：前端记录 TTFT 与总耗时，直接展示在消息下方。
7. **思考过程与正文分通道**：推理模型的 `reasoning_content` 单独走 `thinking` 事件，前端折叠展示；且**思考内容不入会话上下文**，否则会被当成 assistant 正文污染下一轮。
8. **双模型 + 成本可核算**：辅助任务（标题/改写/摘要）走单价低一档的快模型，按「模型 × 用途」二维聚合 token 与人民币成本，能直接回答「钱花在哪」。
   实测辅助任务只占总成本 2%，但拿掉它主链路的体验会明显变差。
9. **改写结果给用户看**：追问被补全成独立问句后走 `rewrite` 事件显示在前端，答非所问时能一眼看出是「理解错了」还是「答错了」。

## 踩过的两个坑

1. **推理模型的思考 token 会吃掉短输出的 max_tokens**。
   给 `stepfun-ai/Step-3.5-Flash` 设 `max_tokens=48` 生成标题，返回 `finish_reason=length`、`content` 为空、`reasoning_content` 有 325 字。
   如果此时用 `reasoning_content` 兜底当答案，标题就会变成「首先，用户要求我作为会话标题生成器…」。
   正确做法：快模型必须支持 `enable_thinking=false`，且**正文为空就抛异常降级，绝不拿思考内容顶替**。

2. **思考 token 计入 completion 计费**。
   实测 `DeepSeek-V4-Pro` 一次回答：思考 602 token、正文 79 字，成本约是「只看正文」的 8 倍。
   短问答（判分、分类）要用非推理模型，否则钱花在用户看不见的地方。

## 阶段二要做的事

| 优先级 | 事项 | 落点 |
|---|---|---|
| P0 | 教材/真题入库 + 向量检索 + 引用溯源 | `llm/` 增加 `RetrievalService`，`buildContext` 前拼接命中片段；embedding 用 `BAAI/bge-m3`，重排用 `BAAI/bge-reranker-v2-m3`（硅基流动同一 Key） |
| P0 | 评测集（30 条 QA）+ 召回@5 指标 | `backend/src/test` 或独立脚本 |
| P1 | 工具调用：出题 / 判分 / 生成计划 | 新增 `tool/` 包，模型返回 tool_calls 后执行并二次对话 |
| P1 | 会话落 Redis、消息落 MySQL | 替换 `ChatService` 里的内存 Map |
| P2 | 流式 markdown 增量解析 | 前端 `markdown.ts` 换 ruler + 节点缓存 |

```bash
docker compose up -d pgvector redis
```

## 说明

- `MockLlmClient` 只用于验证链路，输出固定文案，不产生真实回答。
- 公式渲染 MVP 用占位符替换，流式过程中可能把 `$...$` 切断，阶段二换增量解析。
