# 群聊 + @小莫执行任务 —— 实现方案设计

> 目标（来自 Issue）：实现类似微信的群聊。首屏=系统已注册用户列表；右上角建群入口，可邀请**真人用户**与**自己的小莫/分身**入群；群内 @ 指定小莫/分身 → 它执行任务 → 结果回传当前群聊。
>
> 本文档给「现状 → 三套主方案 + 一套贴合后端原生的变体 → 推荐 → 分阶段 MVP」。先读「关键结论」。

---

## 实现进度（2026-06-29）

**采用方案**：方案 A（复用 session 体系）的变体——群用独立 `conversations` 三表（不污染 sessions_v2），@触发复用 `runSessionMessage`。

**后端**（`/app/mobius`，已 commit `3da35c1`，`npm run typecheck` + `tests/conversations-smoke.ts` 全过）：
- ✅ P1 群数据模型（`db.ts:migrateConversations` 建 `conversations`/`conversation_members`/`conversation_messages`）+ 用户列表搜索（`GET /api/users`，`routes/users.ts`）+ 群 CRUD（`routes/conversations.ts` + `repositories/conversations.ts`）。
- ✅ P2 群消息收发（`POST /api/conversations/:id/messages`）+ 群 SSE（`GET /api/conversations/:id/events`，DB 轮询增量广播，真人 P0）。
- ✅ P3 @小莫触发：`triggerAgentMentions` 用 agent owner 身份调 `runSessionMessage`（跨用户授权=该 agent 在群成员表），`watchAgentReply` 轮询该 agent 的 assistant 回复，turn 完成时写一条群消息回传。

**移动端**（worktree `cb7cf2eb`）：
- ✅ 数据层：`Models.kt` 群模型 + `MobiusApi.kt` 群 API/群 SSE（`:shared:compileKotlinDesktop` 通过）。
- ⏳ ViewModel + UI（首屏用户列表+建群+群聊+@输入）：实现中。

**验证**：后端 `npm run typecheck` + `npx tsx tests/conversations-smoke.ts`；移动端 `:shared:compileKotlinDesktop`。端到端联调需重启后端（`pm2 reload`）使新路由/表生效。

**API 清单**：
- `GET /api/users?q=&page=&pageSize=` 用户列表+搜索
- `POST /api/conversations` 建群 / `GET /api/conversations` 我的群 / `GET /api/conversations/:id` 群详情+成员
- `POST|DELETE /api/conversations/:id/members[/:type/:id]` 成员管理
- `POST /api/conversations/:id/messages` 发群消息（mentions 触发 @agent）
- `GET /api/conversations/:id/events` 群 SSE

---

---

## 0. 关键结论（先读）

经对后端（`/app/mobius`，独立仓库）与移动端（KMP `shared`）的完整勘察：

1. **"让某个小莫/分身执行任务并拿回结果"的后端能力已完整具备，无需新建 agent 调度逻辑：**
   - `POST /api/sessions/:id/messages` → `runSessionMessage()`（`backend/routes/sessions.ts:1218`）即可触发**任意一个** agent session 执行。
   - `GET /api/sessions/:id/events`（`sessions.ts:563`）SSE 流式回灌历史 + 续接 live（jsonl_entry 透传完整消息）。
   - `POST /api/issues/:issueId/sessions/`（`sessions.ts:1357`）创建新 agent session（分身），`session_key = web:<uid>:<sid>`。
   - `backend/routes/assistant.ts:552-556` **后端原生就有「主体小莫把任务拆给分身、分身完成后汇总」的 prompt 设计**——多 agent 协作是已验证路径。

2. **真正缺失的只有 4 块（所有方案都要补）：**
   - ①「群」=多成员会话的持久化抽象（现 `sessions.user_id` 是单用户）。
   - ②群消息的**多人广播**（现 SSE 是单 session 维度）。
   - ③**普通用户可见的用户列表 API**：现仅 `GET /api/admin/users`（`adminAuth`），普通用户调不到。
   - ④**@mention 解析与路由**：把 `@某小莫` 映射到对应 agent session 并触发。

3. **数据模型现状（务必区分）：**
   - `user_groups`（`schema.sql:16`）是**员工组织部门**（"默认组"），**不是聊天群**，不能直接复用为群聊。
   - `messages`（`schema.sql:275`）：`task_id(=session_id), role, content, metadata(TEXT), turn_number`，**无 sender_id**——因为 session 单用户，user 消息天然是 owner 发的。群聊要支持多发送者，需扩字段或塞 `metadata`。
   - 小莫/分身**不是独立表**，就是 `sessions` 里的记录（`assistant_role`/`name`/`session_key` 区分）。

> 一句话：**群聊 = 在现有「1 用户 : N agent-session」之上，加一层「多成员会话」抽象 + 群广播 + 用户列表 + @路由。** 复用面极大，新建面集中在"群"这一层。

---

## 1. 现状对照（需求 vs 现有能力）

| 需求 | 现状 | 缺口 |
|---|---|---|
| 首屏：系统已注册用户列表 | `GET /api/admin/users`（仅管理员） | 普通用户可调的脱敏用户列表 API |
| 建群 + 邀请成员 | 无群概念；session 单 `user_id` | 群容器 + 成员关系表 |
| 成员含真人 + 小莫/分身 | 小莫/分身 = `sessions` 记录；真人 = `users` | 成员表需支持 user / agent 两类成员 |
| 群内发消息（多人群聊） | `messages` 无 sender_id；SSE 单 session | 多发送者标识 + 群广播 |
| @某小莫执行任务 | `POST /api/sessions/:id/messages` 已能触发任意 agent | @ 解析 → 定位 agent session → 触发 |
| 结果回传群聊 | agent SSE 流式回复已有 | 把 agent 回复作为群消息广播给全员 |

---

## 2. 三套主方案（按「复用度 / 改动量」排列）

### 方案 A —— 复用 session 作「群」容器 + 新增成员表（推荐，渐进式）

**核心思想**：群本身也是一个 `session`（新增 `scope_type='group'` 或 `session_key` 前缀 `group:<uid>:<sid>` 区分），成员、消息、SSE 全部在现有 session 体系上扩展。

**数据模型改动**
```sql
-- sessions 复用，靠 scope_type / session_key 区分群聊（建议加一列，便于查询）
ALTER TABLE sessions ADD COLUMN scope_type TEXT NOT NULL DEFAULT 'issue'; -- 'issue' | 'group'

-- 新增：群成员（user 与 agent 两类）
CREATE TABLE IF NOT EXISTS session_members (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL,            -- 群 session_id
  member_type TEXT NOT NULL,           -- 'user' | 'agent'
  member_id TEXT NOT NULL,             -- users.id 或 agent session_id
  display_name TEXT NOT NULL,          -- 群内昵称/展示名（agent 取 session.name）
  role TEXT NOT NULL DEFAULT 'member', -- 'owner' | 'member'
  joined_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE(session_id, member_type, member_id),
  FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
);

-- messages 扩多发送者（或塞 metadata JSON，避免改表）
ALTER TABLE messages ADD COLUMN sender_id TEXT;       -- 真人 user.id 或 agent session_id
ALTER TABLE messages ADD COLUMN sender_type TEXT;     -- 'user' | 'agent'
ALTER TABLE messages ADD COLUMN mention_targets TEXT; -- JSON: [{"type":"agent","id":"sid","name":"小莫A"}]
```

**新增/改动 API**
- `GET /api/users`（普通 auth，脱敏：`id, display_name, role, group_id`）—— 首屏用户列表。
- `POST /api/groups`（建群：name + members[]）→ 内部复用 `Sessions.insert` 建一个 `scope_type='group'` session + 批量插 `session_members`。
- `GET /api/groups`（我的群列表） / `GET /api/groups/:id`（群详情+成员）。
- `POST /api/groups/:id/messages`（发群消息：content + mentions[]）：
  1. 落 `messages`（sender=当前 user，mention_targets 记录 @ 的 agent）。
  2. 广播给群成员（SSE）。
  3. 对每个被 @ 的 agent 成员，后端调 `runSessionMessage({sessionId: agentSessionId, content: 拼装后的任务, source: 'group.mention'})` 触发其执行。
- `GET /api/groups/:id/events`（群 SSE）：回灌群历史 + 广播新消息（含 agent 回复）。**复用现有 SSE 实现思路**（`sessions.ts:563` 的回灌+续接模式）。
- agent 执行完一条 → 其 SSE 的 `jsonl_entry` 由后端**中转**为该群的一条 agent 群消息广播（监听 agent session 的 thought stream，把 assistant 文本回写群 messages + 广播）。

**@小莫 → 执行 → 回传 的链路（方案 A 核心）**
```
用户在群输入框输入 "@小莫A 帮我查一下X" → POST /api/groups/:id/messages
  后端:
    1. 存 user 消息(带 mention_targets=[agent:小莫A])
    2. 广播 user 消息给全员
    3. 解析 mention → 小莫A 对应的 agent session_id（群成员表里查）
    4. runSessionMessage(agentSessionId, 任务prompt)  ← 复用现有触发入口
    5. 订阅小莫A 的 SSE/thought stream → 每条 assistant 文本 → 落群 messages(sender_type=agent) + 广播
  前端:
    群 SSE 收到 agent 消息 → 作为小莫A 的气泡渲染（带 sender_name）
```

**优 / 缺**
- ✅ 最大化复用 session / messages / SSE / agent 触发基建；概念统一（群也是 session）。
- ✅ `runSessionMessage` 原样复用，agent 执行逻辑零改动。
- ⚠️ session 语义被复用（原本=单用户 agent 会话），查询时要靠 `scope_type` 严格区分，避免污染 `listAssistantSessions` 等现有列表。
- ⚠️ `messages` 加列需迁移；或先用 `metadata` JSON 过渡（更轻）。

**工作量**：中。后端≈1 个新路由文件 `groups.ts` + 2 张表 + `runSessionMessage` 复用 + 群 SSE；移动端≈新增群列表/建群/群聊 3 个界面 + 群 SSE 接入。

---

### 方案 B —— 纯前端编排 + 轻量群消息表（最小后端改动，快速 MVP）

**核心思想**：后端**不动 session/agent 模型**，只加①用户列表 API ②一张轻量群消息表。"@小莫执行任务"完全由**前端编排**：前端识别 @ → 直接调现成的 `POST /api/sessions/:id/messages` 给该 agent → 前端订阅该 agent 的 SSE 拿结果 → 把结果当群消息展示并落表。

**数据模型改动（最小）**
```sql
-- 仅一张群消息表，与现有 messages 平行，不碰 sessions
CREATE TABLE IF NOT EXISTS group_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  group_id TEXT NOT NULL,              -- 群标识（可由前端生成或后端建群接口给）
  sender_id TEXT NOT NULL,
  sender_type TEXT NOT NULL,           -- 'user' | 'agent'
  sender_name TEXT NOT NULL,
  content TEXT NOT NULL,
  mention_targets TEXT,                -- JSON
  source_agent_session TEXT,           -- agent 消息时记来源 session
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
-- 群成员关系可选：前端建群时本地维护，或加一张极简 group_members。
```

**新增 API（极少）**
- `GET /api/users`（脱敏用户列表，同方案 A）。
- `POST /api/groups/:id/messages`（存+广播群消息）+ `GET /api/groups/:id/events`（群 SSE）。
- 建群/成员：可纯前端维护（MVP），或加一个极简 `POST /api/groups`。

**@小莫链路（前端编排）**
```
前端输入 "@小莫A 查X" →
  1. POST /api/groups/:id/messages （存 user 群消息）
  2. 前端直接 POST /api/sessions/{小莫A的sessionId}/messages  ← 现成接口
  3. 前端开 SSE GET /api/sessions/{小莫A}/events 拿 agent 回复  ← 现成
  4. 每条回复 → POST /api/groups/:id/messages(sender_type=agent) 落表 + 展示
```

**优 / 缺**
- ✅ 后端改动最小（不动 sessions/messages/agent），最快出 demo。
- ✅ 完全不碰现有 1:1 小莫会话，零回归风险。
- ⚠️ 群与现有 session 体系平行，长期是两套消息存储。
- ⚠️ 多人实时性（别的成员也要看到 agent 回复）依赖群 SSE 广播；前端要管"哪个 agent 在跑"的状态。
- ⚠️ 跨端/刷新恢复要靠 group_messages 持久化补。

**工作量**：小。适合"两周内出可用 demo"。

---

### 方案 C —— 独立 conversations 子系统（最干净，长期架构）

**核心思想**：群聊用全新三表，与 `sessions` 彻底解耦；member 引用 user 或 assistant（agent session）；自带群 SSE 与消息 API。

**数据模型改动**
```sql
CREATE TABLE conversations (
  id TEXT PRIMARY KEY, name TEXT, owner_id TEXT, created_at TEXT, last_active TEXT
);
CREATE TABLE conversation_members (
  conversation_id TEXT, member_type TEXT, -- 'user'|'agent'
  member_id TEXT, display_name TEXT, role TEXT,
  agent_session_id TEXT,  -- agent 成员指向其 session（跨用户共享时用）
  UNIQUE(conversation_id, member_type, member_id)
);
CREATE TABLE conversation_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id TEXT, sender_id TEXT, sender_type TEXT,
  content TEXT, mention_targets TEXT, created_at TEXT
);
```
其余（建群/成员/消息/SSE/@触发）与方案 A 同构，只是命名独立。

**优 / 缺**
- ✅ 模型最干净，完全不影响现有小莫会话；扩展性最强（未来频道、@all、已读都好加）。
- ⚠️ 工作量最大，部分消息/SSE 基建要重写一套。
- ⚠️ 与现有 `messages` 表功能重叠，需明确边界。

**工作量**：大。适合"把群聊作为长期一级功能"。

---

## 3. 变体方案 D —— 多 agent 协作工作台（最贴合后端原生设计）

**核心思想**：不强调真人社交群，把"群"做成 **「@多个小莫/分身并行协作」的工作面板**，直接复用 `assistant.ts:552-556` 已有的「主体小莫编排分身 + 汇总」机制：群里 @ 多个小莫 ≈ 让一个编排者 fork 分身并行执行 + 把各结果汇总回群。

- 真人成员可选、为辅；主体 = "我"，群里 @ 小莫即触发其分身执行。
- 后端**几乎零新增调度逻辑**（编排能力已在）。
- ⚠️ 偏离"微信式真人+agent 混合群聊"语义；真人之间的聊天是次要能力。

**适用**：如果你的真实诉求更偏"多 AI 协作解决问题"而非"同事群+AI 助手"，这个方案性价比最高。可作为方案 A 上线后的增强模式。

---

## 4. 推荐与分阶段 MVP

**推荐：方案 A（复用 session + session_members）。** 理由：复用度最高、`runSessionMessage` 零改动、群与现有 session 概念统一、可控渐进。若想最快验证产品形态，可先用**方案 B** 出 MVP，跑通后再迁移到方案 A 的数据模型（两者前端几乎一致）。

**分阶段（方案 A）：**

**Phase 1 —— 群骨架 + @小莫单 agent（可演示）**
- 后端：`GET /api/users`；`scope_type` 迁移；`session_members` 表；`POST/GET /api/groups`、`POST /api/groups/:id/messages`、`GET /api/groups/:id/events`（群 SSE）；@ 单个 agent → `runSessionMessage` 触发 → agent 回复中转为群消息。
- 移动端：新增 `AppScreen.GroupList`（首屏=用户列表 + 右上角建群）、`CreateGroupSheet`（勾选用户/小莫/分身）、`GroupChatScreen`（群消息流 + @ 输入）。复用现有 `MessageRow`/`ChatInputBar`/`streamSession` 的 SSE 模式。

**Phase 2 —— 多 agent 并行 @ + 汇总**
- 群内同时 @ 多个小莫，后端并行触发多个 agent，各自回复独立成气泡（或由主体小莫汇总，即方案 D 的能力叠加）。

**Phase 3 —— 社交完善**
- 成员管理（踢人/退群/转让）、未读/红点、@all、消息已读、邀请**别人的**小莫（跨用户 agent 共享权限设计）。

---

## 5. 关键设计决策（需你拍板）

1. **@ 的 agent 来源范围**：
   - (a) 只能 @ 自己的主小莫 + 自己的分身（`GET /api/assistant/sessions`，最简单，权限干净）。
   - (b) 还能 @ 群里别人邀请进来的小莫（跨用户共享 agent session，需做访问授权）。
   - MVP 建议 (a)。

2. **跨用户 agent 访问**：现 `runSessionMessage` 用 `userOf(req)` 校验 session 归属，@ 别人的小莫需要放宽（按"群成员身份"授权）。Phase 3 再处理。

3. **用户列表可见性**：`GET /api/users` 返回哪些字段？建议只给 `id + display_name + role`，不暴露 `work_dir/password_hash`。是否按 `group_id`（组织部门）过滤、是否支持搜索，需确认。

4. **消息存储**：`messages` 加列 vs `metadata` JSON。加列更规范、可索引；metadata 更轻、零迁移风险。MVP 可 metadata，正式加列。

5. **agent 回复"流式 vs 整段"回群**：流式（每条 jsonl_entry 即时广播，体验好但消息多）vs 整段（agent 轮次结束后广播一条，简洁）。建议流式，与现有 SSE 一致。

6. **群 SSE 复用 vs 新建**：复用 `GET /api/sessions/:id/events`（群也是 session，回灌群 messages + 广播）最省；新建 `GET /api/groups/:id/events` 更清晰。建议新建，避免污染单聊 SSE 逻辑。

---

## 6. 风险与开放问题

- **并发与一致性**：多 agent 同时 @ 触发，群消息时序、agent 回复交错需有序号/时间戳保证。
- **权限模型**：群成员对 agent 的访问授权（尤其跨用户）需设计，避免越权触发他人 agent。
- **审计**：现有 `auditSessionAccess` 等审计钩子需扩展到群消息/@触发。
- **移动端 state 膨胀**：现有 `UiState` 已较大（消息/分身/流式共用），群聊建议拆出独立 `GroupUiState`，避免重组开销（参考已修复的输入卡顿教训）。
- **后端为独立仓库 `/app/mobius`**：后端改动需在该仓库提 PR 并重启服务生效；移动端在本 worktree。两端需协同发版。

---

## 附：关键代码位置速查

| 能力 | 位置 |
|---|---|
| 触发 agent 执行（核心复用） | `/app/mobius/backend/routes/sessions.ts:1218` `POST /:id/messages` → `runSessionMessage` |
| SSE 流式（核心复用） | `sessions.ts:563` `GET /:id/events` |
| 创建分身 session | `sessions.ts:1357` `issueScoped.post`，`session_key=web:<uid>:<sid>` |
| 主体编排分身（原生设计） | `assistant.ts:552-556` `assistantPrompt` |
| 列小莫/分身 | `assistant.ts:821` `listAssistantSessions` / `:1198` `GET /sessions` |
| 小莫 workspace（project+issue） | `assistant.ts:1215` `GET /workspace` |
| 管理员用户列表（需普通化） | `admin.ts:558` `GET /users`（`adminAuth`） |
| sessions 表 | `schema.sql:245` |
| messages 表（无 sender_id） | `schema.sql:275` |
| users 表 | `schema.sql:27` |
| 移动端 ViewModel/SSE/UI | `shared/.../viewmodel/MomoAppViewModel.kt`、`data/MobiusApi.kt`、`ui/MomoApp.kt` |
