package com.mobius.momo.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    val role: String = "",
    @SerialName("work_dir") val workDir: String = "",
)

@Serializable
data class Project(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    // 列表页展示用(后端 GET /api/projects 返回, 可选):
    @SerialName("issue_count") val issueCount: Int? = null,
    @SerialName("last_active") val lastActive: String? = null,
    @SerialName("last_session_activity_at") val lastSessionActivityAt: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    val starred: Boolean? = null,
    val disabled: Boolean? = null,
    // 拓展系统: 'normal' | 'extension'. 后端 kind=='extension' 的项目由 mobius/extension/<name>/ 同步而来,
    // 用于项目列表"扩展"筛选 tab。后端 shapeProjectForUser 用 ...project 展开, 此字段已随列表返回。
    val kind: String = "normal",
    @SerialName("extension_name") val extensionName: String? = null,
)

@Serializable
data class Issue(
    val id: String = "",
    @SerialName("project_id") val projectId: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "",
    // 列表页展示用(后端 GET /api/projects/:id/issues 返回, 可选):
    @SerialName("session_count") val sessionCount: Int? = null,
    @SerialName("last_active") val lastActive: String? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    // 注意: 后端 issue 列表的 pinned/use_worktree/starred/is_planning 是 0/1 整数(非布尔),
    // 这里不声明这些字段(ignoreUnknownKeys 会忽略), 否则 Boolean 声明会致整列表反序列化失败。
)

@Serializable
data class AssistantWorkspace(
    val project: Project = Project(),
    val issue: Issue = Issue(),
)

@Serializable
data class Session(
    @SerialName("session_id") val sessionId: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("assistant_role") val assistantRole: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("issue_id") val issueId: String = "",
    val model: String = "",
    @SerialName("agent_status") val agentStatus: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_active") val lastActive: String = "",
    @SerialName("job_failed") val jobFailed: Boolean? = null,
    @SerialName("job_accomplished") val jobAccomplished: Boolean? = null,
    // 项目钻取列表页展示用(后端 GET /api/issues/:id/sessions 返回, 可选):
    val status: String? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("raw_entry_count") val rawEntryCount: Int? = null,
    @SerialName("user_display_name") val userDisplayName: String? = null,
    @SerialName("model_label") val modelLabel: String? = null,
)

@Serializable
data class LoginResult(
    val token: String = "",
    val user: User = User(),
    @SerialName("expiresAt") val expiresAt: String = "",
)

@Serializable
data class AuthConfig(
    @SerialName("password_required") val passwordRequired: Boolean = true,
)

@Serializable
data class SessionModelOption(
    val key: String = "",
    val label: String = "",
    val title: String = "",
    val sub: String = "",
    val backend: String = "",
)

@Serializable
data class SessionPersonalityOption(
    val key: String = "",
    val label: String = "",
    val description: String = "",
)

@Serializable
data class SessionPresetConfig(
    val name: String = "Mobius",
    val description: String = DEFAULT_ASSISTANT_PRESET_DESCRIPTION,
    val personality: String = "balanced",
    val model: String = "codex",
    val role: String = "research_assistant",
    val language: String = "zh",
    @SerialName("existing_session_action") val existingSessionAction: String = "ignore",
    @SerialName("excluded_skill_ids") val excludedSkillIds: List<String> = emptyList(),
    @SerialName("excluded_memory_ids") val excludedMemoryIds: List<String> = emptyList(),
    @SerialName("required_skill_ids") val requiredSkillIds: List<String> = emptyList(),
    @SerialName("saved_at") val savedAt: String? = null,
)

@Serializable
data class AssistantPresetSessionSummary(
    @SerialName("session_id") val sessionId: String = "",
    val name: String = "",
    @SerialName("model_label") val modelLabel: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_active") val lastActive: String = "",
)

@Serializable
data class AssistantPresetPayload(
    val project: Project = Project(),
    val issue: Issue = Issue(),
    val preset: SessionPresetConfig = SessionPresetConfig(),
    @SerialName("personality_options") val personalityOptions: List<SessionPersonalityOption> = emptyList(),
    @SerialName("model_label") val modelLabel: String = "",
    @SerialName("current_session") val currentSession: AssistantPresetSessionSummary? = null,
)

@Serializable
data class AssistantMessageResult(
    val ok: Boolean = false,
    val created: Boolean = false,
    @SerialName("request_id") val requestId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("task_id") val taskId: String = "",
    val session: Session? = null,
    val project: Project = Project(),
    val issue: Issue = Issue(),
) {
    fun resolvedSessionId(): String =
        listOf(sessionId, taskId, session?.sessionId.orEmpty()).firstOrNull { it.isNotBlank() }.orEmpty()
}

enum class MessageAuthor {
    Momo,
    User,
    System,
}

data class ChatMessage(
    val id: String,
    val author: MessageAuthor,
    val text: String,
    val time: String,
    val voiceText: String? = null,
    val createdAtMillis: Long? = null,
    // 过程条目元数据: null = 普通文本消息, 非 null = 可折叠的过程卡片
    val processType: String? = null,   // "thinking" | "tool_call" | "tool_result" | "search" | ...
    val processLabel: String? = null,  // "正在思考…" | "正在调用工具：read" | ...
)

data class StreamTextChunk(
    val id: String,
    val author: MessageAuthor,
    val text: String,
    val time: String,
    val voiceText: String? = null,
    val createdAtMillis: Long? = null,
    // 过程条目元数据 (SSE 的 process 条目也产生 chunk, 携带这些字段)
    val processType: String? = null,
    val processLabel: String? = null,
)

/**
 * 一个对话"轮次" = 1 条用户提问 + N 条 agent 回复(含文本+过程条目).
 * 从 flat messages 列表中扫描 User 消息作为轮次边界推导而来.
 */
data class Turn(
    val userMessage: ChatMessage?,          // null = 首轮无用户消息的续接场景
    val assistantItems: List<ChatMessage>,  // 文本回复 + 过程条目混合
    val isStreaming: Boolean = false,       // 最后一轮正在接收 SSE 流时为 true
)

data class AssistantSnapshot(
    val session: Session = Session(),
    val messages: List<ChatMessage> = emptyList(),
    val status: AssistantSessionStatus = AssistantSessionStatus(),
)

data class AssistantSessionStatus(
    val working: Boolean = false,
    val failed: Boolean = false,
    val agentStatus: String = "",
)

// 精简模式的项目页条目: 所有项目下的 session 平铺(带项目/Issue 名与活跃状态)。
// @Serializable: 磁盘缓存(TAB_CACHE_LITE_SESSIONS)序列化需要 — 缺注解会在运行时抛异常
// 导致精简模式数据加载崩溃(iOS 上无兜底直接表现为"加载不出来")。
@Serializable
data class LiteSessionEntry(
    val session: Session,
    val projectName: String = "",
    val issueTitle: String = "",
)

// 新建会话时可勾选的上下文条目(skill/memory), 排除法: 未勾选的作为 excluded_*_ids 提交。
@Serializable
data class ContextPickItem(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val scope: String = "project",
)

data class CloneDraft(
    val projectId: String = "",
    val issueId: String = "",
    val title: String = "",
    val description: String = "",
    val model: String = "codex",
)

const val DEFAULT_ASSISTANT_PRESET_DESCRIPTION =
    "你是 Mobius，莫比乌斯AI的项目助理。先读取skills/mobius-assistant/SKILL.md获取你的服务指南，再执行任务。征求用户的确认时，你必须参考“正确服务话术案例”与用户沟通！"

// ===== 群聊 (conversations) =====
// 对应后端 /api/users 与 /api/conversations. 成员含真人(user) + 小莫/分身(agent).

@Serializable
data class UserDirectoryEntry(
    val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    val role: String = "",
    @SerialName("is_self") val isSelf: Boolean = false,
)

@Serializable
data class UserDirectoryResult(
    val users: List<UserDirectoryEntry> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 50,
    val total: Int = 0,
)

@Serializable
data class ConversationSummary(
    val id: String = "",
    val name: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_active") val lastActive: String = "",
    @SerialName("last_message") val lastMessage: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("member_count") val memberCount: Int = 0,
    val type: String = "group",
    val unread: Int = 0,
    // 群成员展示名（用于群头像取首字符）。后端列表接口若不返回则为空，UI 会走缓存/兜底。
    @SerialName("member_names") val memberNames: List<String> = emptyList(),
)

@Serializable
data class ConversationMember(
    val id: Long = 0,
    @SerialName("conversation_id") val conversationId: String = "",
    @SerialName("member_type") val memberType: String = "user",
    @SerialName("member_id") val memberId: String = "",
    @SerialName("display_name") val displayName: String = "",
    val role: String = "member",
    @SerialName("agent_session_id") val agentSessionId: String? = null,
    @SerialName("agent_owner_id") val agentOwnerId: String? = null,
    @SerialName("agent_owner_name") val agentOwnerName: String? = null,
    @SerialName("joined_at") val joinedAt: String = "",
    val online: Boolean = false,
) {
    val isAgent: Boolean get() = memberType == "agent"
    val isOwner: Boolean get() = role == "owner"
}

@Serializable
data class ConversationMessage(
    val id: Long = 0,
    @SerialName("conversation_id") val conversationId: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("sender_type") val senderType: String = "user",
    @SerialName("sender_name") val senderName: String = "",
    val content: String = "",
    @SerialName("mention_targets") val mentionTargetsJson: String? = null,
    @SerialName("source_agent_session") val sourceAgentSession: String? = null,
    @SerialName("created_at") val createdAt: String = "",
) {
    val isAgent: Boolean get() = senderType == "agent"
}

@Serializable
data class ConversationDetail(
    val conversation: ConversationSummary = ConversationSummary(),
    val members: List<ConversationMember> = emptyList(),
)

@Serializable
data class ConversationMemberInput(
    val type: String,
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("agent_session_id") val agentSessionId: String? = null,
)

// createConversation 的 body 直接走 buildMap, 无需单独的 request class.
