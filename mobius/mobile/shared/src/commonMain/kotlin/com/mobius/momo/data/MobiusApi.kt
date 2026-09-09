package com.mobius.momo.data

import com.mobius.momo.domain.AssistantMessageResult
import com.mobius.momo.domain.AssistantPresetPayload
import com.mobius.momo.domain.AssistantPresetSessionSummary
import com.mobius.momo.domain.AssistantSessionStatus
import com.mobius.momo.domain.AssistantSnapshot
import com.mobius.momo.domain.AssistantWorkspace
import com.mobius.momo.domain.AuthConfig
import com.mobius.momo.domain.ChatMessage
import com.mobius.momo.domain.Issue
import com.mobius.momo.domain.LoginResult
import com.mobius.momo.domain.MessageAuthor
import com.mobius.momo.domain.Project
import com.mobius.momo.domain.Session
import com.mobius.momo.domain.SessionModelOption
import com.mobius.momo.domain.SessionPresetConfig
import com.mobius.momo.domain.StreamTextChunk
import com.mobius.momo.domain.ContextPickItem
import com.mobius.momo.domain.ConversationDetail
import com.mobius.momo.domain.ConversationMember
import com.mobius.momo.domain.ConversationMemberInput
import com.mobius.momo.domain.ConversationMessage
import com.mobius.momo.domain.ConversationSummary
import com.mobius.momo.domain.UserDirectoryResult
import io.ktor.client.request.delete
import io.ktor.http.encodeURLParameter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class UploadedAttachment(
    val path: String,
    val name: String,
    val size: Long,
)

data class AssistantPromptAttachment(
    val path: String,
    val name: String,
    val size: Long,
    val type: String,
    val mimeType: String,
)

class AssistantPresetRequiresSessionDeleteException(
    message: String,
    val currentSession: AssistantPresetSessionSummary?,
) : Exception(message)

@Serializable
private data class AssistantPresetSaveRequest(
    val preset: SessionPresetConfig,
    @SerialName("delete_current_session") val deleteCurrentSession: Boolean,
)

@Serializable
private data class CreateSessionRequest(
    val name: String,
    val description: String,
    val model: String,
    val language: String = "zh",
    @SerialName("excluded_skill_ids") val excludedSkillIds: List<String> = emptyList(),
    @SerialName("excluded_memory_ids") val excludedMemoryIds: List<String> = emptyList(),
)

@Serializable
private data class SessionMessageRequest(
    val content: String,
    @SerialName("input_text") val inputText: String,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
private data class CreateConversationBody(
    val name: String,
    val members: List<ConversationMemberInput>,
)

@Serializable
private data class CreateProjectBody(
    val name: String,
    val description: String,
    val bindPath: String,
    val visibility: String = "private",
)

@Serializable
private data class CreateIssueBody(
    val title: String,
    val description: String,
)

@Serializable
private data class PostConversationMessageBody(
    val content: String,
    val mentions: List<ConversationMemberInput>,
)

/** Mobius /api/ext 调用体：转发到扩展 handler，handler 按 ext_main_payload.action 分发。 */
@Serializable
private data class ExtCallRequest(
    @SerialName("extension_name") val extensionName: String,
    @SerialName("ext_main_payload") val extMainPayload: Map<String, String>,
)

class MobiusApi(
    private val baseUrl: String,
    private val storage: SecureStorage,
    private val onUnauthorized: suspend () -> Unit,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client: HttpClient = createMobiusHttpClient(
        onUnauthorized = onUnauthorized,
    )

    fun close() {
        client.close()
    }

    // ===== 群聊 (conversations) =====
    // 对应后端 /api/users 与 /api/conversations (后端 P1+P2 已实现).

    suspend fun listUsers(q: String = "", page: Int = 1, pageSize: Int = 50): UserDirectoryResult {
        val qs = buildString {
            append("?page=").append(page).append("&pageSize=").append(pageSize)
            if (q.isNotBlank()) append("&q=").append(q.encodeURLParameter())
        }
        return client.get("$baseUrl/api/users$qs") { addAuth() }.body()
    }

    suspend fun listConversations(): List<ConversationSummary> {
        val obj = client.get("$baseUrl/api/conversations") { addAuth() }.body<JsonObject>()
        return parseConversationList(obj["conversations"] as? JsonArray)
    }

    suspend fun getConversation(id: String): ConversationDetail {
        val obj = client.get("$baseUrl/api/conversations/${id.encodeURLParameter()}") { addAuth() }.body<JsonObject>()
        return ConversationDetail(
            conversation = parseConversationSummary(obj["conversation"] as? JsonObject),
            members = parseConversationMembers(obj["members"] as? JsonArray),
        )
    }

    suspend fun createConversation(name: String, members: List<ConversationMemberInput>): ConversationSummary {
        val response = client.post("$baseUrl/api/conversations") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateConversationBody(name, members))
        }
        val raw = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (response.status.value !in 200..299) {
            error(obj?.string("error") ?: "建群失败: HTTP ${response.status.value}")
        }
        return ConversationSummary(id = obj?.string("id").orEmpty(), name = obj?.string("name").orEmpty())
    }

    suspend fun openDirectChat(memberId: String): ConversationSummary {
        val response = client.post("$baseUrl/api/conversations/direct") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(buildMap { put("member_id", memberId) })
        }
        val raw = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (response.status.value !in 200..299) {
            error(obj?.string("error") ?: "发起私聊失败: HTTP ${response.status.value}")
        }
        return ConversationSummary(id = obj?.string("id").orEmpty(), name = obj?.string("name").orEmpty())
    }

    suspend fun addConversationMember(conversationId: String, member: ConversationMemberInput) {
        client.post("$baseUrl/api/conversations/${conversationId.encodeURLParameter()}/members") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(member)
        }
    }

    suspend fun removeConversationMember(conversationId: String, type: String, memberId: String) {
        val response = client.delete("$baseUrl/api/conversations/${conversationId.encodeURLParameter()}/members/$type/${memberId.encodeURLParameter()}") { addAuth() }
        if (response.status.value !in 200..299) {
            val obj = runCatching { json.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
            error(obj?.string("error") ?: obj?.string("message") ?: "移除成员失败: HTTP ${response.status.value}")
        }
    }

    // 删除/退出会话: 群主=解散整群, 非群主=仅自己退出. 后端 DELETE /api/conversations/:id.
    suspend fun deleteConversation(conversationId: String) {
        val response = client.delete("$baseUrl/api/conversations/${conversationId.encodeURLParameter()}") { addAuth() }
        if (response.status.value !in 200..299) {
            val obj = runCatching { json.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
            error(obj?.string("error") ?: obj?.string("message") ?: "删除聊天失败: HTTP ${response.status.value}")
        }
    }

    suspend fun postConversationMessage(
        conversationId: String,
        content: String,
        mentions: List<ConversationMemberInput> = emptyList(),
    ): ConversationMessage? {
        val response = client.post("$baseUrl/api/conversations/${conversationId.encodeURLParameter()}/messages") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(PostConversationMessageBody(content, mentions))
        }
        val raw = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (response.status.value !in 200..299) {
            error(obj?.string("error") ?: "发送失败: HTTP ${response.status.value}")
        }
        return parseConversationMessage(obj?.get("message") as? JsonObject)
    }

    // 群消息 SSE: history(回灌) + message(增量) 帧. 照 streamSession 的 prepareGet+帧解析.
    suspend fun streamConversation(
        conversationId: String,
        onConnected: suspend () -> Unit,
        onHistory: suspend (List<ConversationMessage>) -> Unit,
        onMessage: suspend (ConversationMessage) -> Unit,
        onError: suspend (String) -> Unit,
    ) {
        var eventName = "message"
        val dataLines = mutableListOf<String>()
        try {
            client.prepareGet("$baseUrl/api/conversations/${conversationId.encodeURLParameter()}/events") {
                addAuth()
                header(HttpHeaders.Accept, "text/event-stream")
                header("Cache-Control", "no-cache")
                timeout {
                    requestTimeoutMillis = SSE_TIMEOUT_MILLIS
                    socketTimeoutMillis = SSE_TIMEOUT_MILLIS
                }
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    if (response.status == HttpStatusCode.Unauthorized) {
                        onUnauthorized()
                        throw CancellationException("unauthorized")
                    }
                    onError("群 SSE 连接失败: HTTP ${response.status.value}")
                    return@execute
                }
                onConnected()
                val channel = response.bodyAsChannel()
                while (true) {
                    // 空闲看门狗：90s 收不到任何字节（反向代理/移动网络静默掐断空闲长连接）
                    // 或连接关闭都返回 null，跳出后由上层重连 → onHistory 回灌群历史(含可能
                    // 在断连窗口期广播、但本连接错过的 agent @回复)。与单聊 streamSession 一致。
                    val line = withTimeoutOrNull(SSE_IDLE_TIMEOUT_MILLIS) { channel.readUTF8Line() }
                    if (line == null) break
                    when {
                        line.isBlank() -> {
                            val raw = dataLines.joinToString("\n")
                            if (raw.isNotBlank()) {
                                handleConvSseEvent(eventName, raw, onHistory, onMessage, onError)
                            }
                            eventName = "message"
                            dataLines.clear()
                        }
                        line.startsWith(":") -> Unit
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(sanitizeStreamError(e))
        }
    }

private suspend fun handleConvSseEvent(
        eventName: String,
        raw: String,
        onHistory: suspend (List<ConversationMessage>) -> Unit,
        onMessage: suspend (ConversationMessage) -> Unit,
        onError: suspend (String) -> Unit,
    ) {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        when (eventName) {
            "history" -> onHistory(parseConversationMessages(obj["messages"] as? JsonArray))
            // 群消息与 agent 回复：兼容事件名 message / agent_reply / agent_message，
            // 以及两种载荷形态(整对象即消息，或 {"message":{...}} 包裹)。仅当解析出的消息
            // 有实质内容(content/id)才回灌，避免把 keepalive 等噪声解析成空消息。
            "message", "agent_reply", "agent_message" -> {
                val msg = parseConversationMessage(obj)
                    ?: parseConversationMessage(obj["message"] as? JsonObject)
                if (msg != null && (msg.content.isNotBlank() || msg.id != 0L)) onMessage(msg)
            }
            "ready", "keepalive" -> Unit
            "server_error" -> onError(obj.string("message") ?: "服务端错误")
        }
    }

    private fun parseConversationSummary(obj: JsonObject?): ConversationSummary {
        obj ?: return ConversationSummary()
        return runCatching { json.decodeFromJsonElement(ConversationSummary.serializer(), obj) }.getOrNull()
            ?: ConversationSummary()
    }

    private fun parseConversationList(array: JsonArray?): List<ConversationSummary> {
        if (array == null) return emptyList()
        return array.mapNotNull { parseConversationSummary(it as? JsonObject).takeIf { c -> c.id.isNotBlank() } }
    }

    private fun parseConversationMembers(array: JsonArray?): List<ConversationMember> {
        if (array == null) return emptyList()
        return array.mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            runCatching { json.decodeFromJsonElement(ConversationMember.serializer(), o) }.getOrNull()
        }
    }

    private fun parseConversationMessage(obj: JsonObject?): ConversationMessage? {
        if (obj == null) return null
        return runCatching { json.decodeFromJsonElement(ConversationMessage.serializer(), obj) }.getOrNull()
    }

    private fun parseConversationMessages(array: JsonArray?): List<ConversationMessage> {
        if (array == null) return emptyList()
        return array.mapNotNull { parseConversationMessage(it as? JsonObject) }
    }

    suspend fun authConfig(): AuthConfig =
        client.get("$baseUrl/api/auth/config").body()

    suspend fun login(username: String, password: String = ""): LoginResult {
        val requestBody = buildMap {
            put("username", username)
            if (password.isNotBlank()) put("password", password)
        }
        val response = client.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (response.status.value !in 200..299) error(parseErrorMessage(response.bodyAsText()))
        val result = decodeLogin(response.body())
        storage.saveToken(result.token)
        storage.saveTokenMetadata(
            StoredTokenMetadata(
                baseUrl = baseUrl,
                savedAtEpochMillis = nowEpochMillis(),
                storageVersion = TOKEN_STORAGE_VERSION,
            ),
        )
        return result
    }

    suspend fun me() = client.get("$baseUrl/api/auth/me") { addAuth() }.body<com.mobius.momo.domain.User>()

    // ===== 设备推送令牌（聚合推送）=====
    // 登录成功后把 JPush RegistrationID 上报给后端，后端据此（经 JPush 及其聚合的厂商通道）
    // 在 App 被杀时仍能把消息推到设备。注销登录时调用 unregisterDeviceToken 解绑。
    // 后端链路经 Mobius 的 /api/ext 转发到本扩展 handler（backend/extension_backend_handler.js）：
    //   register_device / unregister_device 持久化到 ext_data_dir；notify_user 调 JPush REST 下发。
    // 扩展未注册/调用失败时由 runCatching 兜底，不影响主流程。
    suspend fun registerDeviceToken(token: String, platform: String = PUSH_PLATFORM_JPUSH) {
        if (token.isBlank()) return
        client.post("$baseUrl/api/ext") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(
                ExtCallRequest(
                    extensionName = MOBIUS_EXTENSION_NAME,
                    extMainPayload = linkedMapOf(
                        "action" to "register_device",
                        "token" to token,
                        "platform" to platform,
                    ),
                ),
            )
        }
    }

    suspend fun unregisterDeviceToken(token: String) {
        if (token.isBlank()) return
        client.post("$baseUrl/api/ext") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(
                ExtCallRequest(
                    extensionName = MOBIUS_EXTENSION_NAME,
                    extMainPayload = linkedMapOf(
                        "action" to "unregister_device",
                        "token" to token,
                    ),
                ),
            )
        }
    }

    suspend fun assistantWorkspace(): AssistantWorkspace =
        client.get("$baseUrl/api/assistant/workspace") { addAuth() }.body()

    suspend fun assistantSessions(limit: Int = 60): List<Session> {
        val obj = client.get("$baseUrl/api/assistant/sessions?limit=$limit") { addAuth() }.body<JsonObject>()
        return parseAssistantSnapshots(obj["sessions"] as? JsonArray).map { it.session }
    }

    suspend fun assistantSnapshots(limit: Int = 60): List<AssistantSnapshot> {
        val obj = client.get("$baseUrl/api/assistant/sessions?limit=$limit") { addAuth() }.body<JsonObject>()
        return parseAssistantSnapshots(obj["sessions"] as? JsonArray)
    }

    suspend fun assistantSnapshot(sessionId: String): AssistantSnapshot {
        val obj = client.get("$baseUrl/api/assistant/sessions/$sessionId") { addAuth() }.body<JsonObject>()
        return parseAssistantSnapshot(obj)
    }

    suspend fun issueSessions(issueId: String): List<Session> =
        client.get("$baseUrl/api/issues/$issueId/sessions/") { addAuth() }.body()

    suspend fun projects(): List<Project> =
        client.get("$baseUrl/api/projects/") { addAuth() }.body()

    // 创建项目: POST /api/projects (body {name, description, bindPath}); kind 默认 normal, 普通用户可建。
    // bindPath 必填(后端 resolveBindPath 空路径报错), 由调用方用 user.work_dir + slug 拼成(对齐网页端 randomProjectBindPath)。
    suspend fun createProject(name: String, description: String, bindPath: String): Project {
        val response = client.post("$baseUrl/api/projects/") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateProjectBody(name, description, bindPath))
        }
        val raw = response.bodyAsText()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        if (response.status.value !in 200..299) {
            val obj = element as? JsonObject
            error(obj?.string("error") ?: "创建项目失败: HTTP ${response.status.value}")
        }
        return json.decodeFromJsonElement(element ?: error("创建项目返回空响应"))
    }

    // 项目钻取第二层: 某项目下的 Issue 列表(后端 GET /api/projects/:projectId/issues)。
    suspend fun listIssues(projectId: String): List<Issue> =
        client.get("$baseUrl/api/projects/$projectId/issues") { addAuth() }.body()

    // 创建 Issue: POST /api/projects/:projectId/issues (body {title, description}, 均必填)。
    suspend fun createIssue(projectId: String, title: String, description: String): Issue {
        val response = client.post("$baseUrl/api/projects/$projectId/issues") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateIssueBody(title, description))
        }
        val raw = response.bodyAsText()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        if (response.status.value !in 200..299) {
            val obj = element as? JsonObject
            error(obj?.string("error") ?: "创建 Issue 失败: HTTP ${response.status.value}")
        }
        return json.decodeFromJsonElement(element ?: error("创建 Issue 返回空响应"))
    }

    suspend fun sessionModelOptions(): List<SessionModelOption> =
        client.get("$baseUrl/api/sessions/model-options") { addAuth() }.body()

    suspend fun fetchAssistantPreset(): AssistantPresetPayload =
        client.get("$baseUrl/api/assistant/preset") { addAuth() }.body()

    suspend fun saveAssistantPreset(
        preset: SessionPresetConfig,
        deleteCurrentSession: Boolean,
    ): AssistantPresetPayload {
        val response = client.post("$baseUrl/api/assistant/preset") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(AssistantPresetSaveRequest(preset, deleteCurrentSession))
        }
        val raw = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (response.status == HttpStatusCode.Conflict) {
            val currentSession = (obj?.get("current_session") as? JsonObject)
                ?.let { runCatching { json.decodeFromJsonElement(AssistantPresetSessionSummary.serializer(), it) }.getOrNull() }
            throw AssistantPresetRequiresSessionDeleteException(
                obj?.string("error") ?: "保存 Mobius 预设需要删除当前 Mobius Session",
                currentSession,
            )
        }
        if (response.status.value !in 200..299) {
            error(obj?.string("error") ?: obj?.string("message") ?: "保存 Mobius 预设失败")
        }
        val payload = obj ?: runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: error("保存 Mobius 预设失败")
        return json.decodeFromJsonElement(AssistantPresetPayload.serializer(), payload)
    }

    suspend fun uploadAttachment(file: PickedFile): UploadedAttachment {
        val safeName = safeDisplayFileName(file.name, "attachment")
        val response = client.post("$baseUrl/api/upload") {
            addAuth()
            setBody(
                multipartFileContent(
                    fieldName = "file",
                    fileName = safeName,
                    mimeType = file.mimeType,
                    bytes = file.bytes,
                    boundaryPrefix = "momo-mobile-upload",
                    fallbackName = "attachment",
                ),
            )
        }
        val raw = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (response.status.value !in 200..299) {
            error(obj?.string("error") ?: obj?.string("message") ?: "附件上传失败")
        }
        return UploadedAttachment(
            path = obj?.string("path").orEmpty(),
            name = obj?.string("name")?.ifBlank { safeName } ?: safeName,
            size = (obj?.get("size") as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: file.bytes.size.toLong(),
        ).also {
            if (it.path.isBlank()) error("服务端未返回附件路径")
        }
    }

    suspend fun sendAssistantMessage(
        content: String,
        attachments: List<AssistantPromptAttachment> = emptyList(),
        route: String = "/mobile",
    ): AssistantMessageResult {
        val attachmentJson = JsonArray(
            attachments.map { attachment ->
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(attachment.path),
                        "name" to JsonPrimitive(attachment.name),
                        "size" to JsonPrimitive(attachment.size),
                        "type" to JsonPrimitive(attachment.type),
                        "mime_type" to JsonPrimitive(attachment.mimeType),
                    ),
                )
            },
        )
        val body = client.post("$baseUrl/api/assistant/messages") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(
                JsonObject(
                    mapOf(
                        "content" to JsonPrimitive(content),
                        "input_text" to JsonPrimitive(content),
                        "attachments" to attachmentJson,
                        "route" to JsonPrimitive(route),
                        "client_context" to JsonObject(
                            mapOf(
                                "source" to JsonPrimitive("momo-mobile"),
                                "route" to JsonPrimitive(route),
                            ),
                        ),
                    ),
                ),
            )
        }.body<JsonObject>()
        return decodeAssistantMessageResult(body)
    }

    suspend fun transcribeAssistantAudio(bytes: ByteArray, mimeType: String, fileName: String): String {
        val safeFileName = safeDisplayFileName(fileName, "momo-voice.m4a")
        val response = client.post("$baseUrl/api/assistant/transcribe") {
            addAuth()
            setBody(
                multipartFileContent(
                    fieldName = "audio",
                    fileName = safeFileName,
                    mimeType = mimeType,
                    bytes = bytes,
                    boundaryPrefix = "momo-mobile-audio",
                    fallbackName = "momo-voice.m4a",
                ),
            )
        }
        val raw = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (response.status.value !in 200..299) {
            val backendErr = obj?.string("error") ?: obj?.string("message")
            // 附带 HTTP 状态码与音频字节数，用于区分「客户端录音为空」与「后端 ASR 失败」。
            error("${backendErr ?: "语音识别失败，请重新录制"} [HTTP ${response.status.value}, ${bytes.size}字节]")
        }
        return obj?.string("text")?.trim().orEmpty()
    }

    suspend fun fetchTtsVoices(): TtsVoicesResult {
        val response = client.get("$baseUrl/api/assistant/tts/voices") { addAuth() }
        if (response.status.value !in 200..299) {
            error("豆包音色列表拉取失败: HTTP ${response.status.value}")
        }
        val obj = runCatching { json.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
            ?: return TtsVoicesResult(emptyList(), configured = true)
        return parseTtsVoices(obj)
    }

    suspend fun fetchTtsAudio(text: String, voice: String?): ByteArray {
        val token = storage.getToken()
        return fetchDoubaoTtsAudio(client, baseUrl, token, text, voice)
    }

    suspend fun createClone(
        issueId: String,
        title: String,
        description: String,
        model: String,
        excludedSkillIds: List<String> = emptyList(),
        excludedMemoryIds: List<String> = emptyList(),
    ): Session {
        val response = client.post("$baseUrl/api/issues/$issueId/sessions/") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(
                CreateSessionRequest(
                    name = title,
                    description = description,
                    model = model,
                    excludedSkillIds = excludedSkillIds,
                    excludedMemoryIds = excludedMemoryIds,
                ),
            )
        }
        val raw = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (response.status.value !in 200..299) {
            error(obj?.string("error") ?: obj?.string("message") ?: "创建分身失败: HTTP ${response.status.value}")
        }
        val sessionObj = obj?.get("session") as? JsonObject ?: obj
        return sessionObj?.let { parseSession(it) }?.takeIf { it.sessionId.isNotBlank() }
            ?: runCatching { json.decodeFromString(Session.serializer(), raw) }.getOrNull()
            ?: error("服务端未返回可用的分身 Session")
    }

    // 项目级 skill / memory 列表(新建会话时选择; 后端 GET /api/projects/:projectId/skills|memories)。
    suspend fun listProjectSkills(projectId: String): List<ContextPickItem> {
        val arr = client.get("$baseUrl/api/projects/$projectId/skills") { addAuth() }.body<JsonArray>()
        return arr.mapNotNull { it as? JsonObject }.map { obj ->
            ContextPickItem(
                id = obj.string("id").orEmpty(),
                name = obj.string("name").orEmpty(),
                description = obj.string("description").orEmpty(),
                scope = obj.string("scope") ?: "project",
            )
        }.filter { it.id.isNotBlank() }
    }

    suspend fun listProjectMemories(projectId: String): List<ContextPickItem> {
        val arr = client.get("$baseUrl/api/projects/$projectId/memories") { addAuth() }.body<JsonArray>()
        return arr.mapNotNull { it as? JsonObject }.map { obj ->
            ContextPickItem(
                id = obj.string("id").orEmpty(),
                name = obj.string("name").orEmpty(),
                description = obj.string("description").orEmpty(),
                scope = obj.string("scope") ?: "project",
            )
        }.filter { it.id.isNotBlank() }
    }

    suspend fun startSession(sessionId: String, content: String) {
        client.post("$baseUrl/api/sessions/$sessionId/messages") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(SessionMessageRequest(content = content, inputText = content))
        }
    }

    suspend fun sendSessionMessage(
        sessionId: String,
        content: String,
        requestId: String = "momo-mobile-${nowShortTime()}-${content.hashCode()}",
        attachments: List<AssistantPromptAttachment> = emptyList(),
    ) {
        // 手动构造 JSON(与 sendAssistantMessage 一致): attachments 用 mime_type(snake_case),
        // 支持 分身/项目钻取 session 带附件(图片等)直接在该 session 内处理, 不再转主小莫。
        val attachmentJson = JsonArray(
            attachments.map { attachment ->
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(attachment.path),
                        "name" to JsonPrimitive(attachment.name),
                        "size" to JsonPrimitive(attachment.size),
                        "type" to JsonPrimitive(attachment.type),
                        "mime_type" to JsonPrimitive(attachment.mimeType),
                    ),
                )
            },
        )
        val response = client.post("$baseUrl/api/sessions/$sessionId/messages") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(
                JsonObject(
                    mapOf(
                        "content" to JsonPrimitive(content),
                        "input_text" to JsonPrimitive(content),
                        "request_id" to JsonPrimitive(requestId),
                        "attachments" to attachmentJson,
                    ),
                ),
            )
        }
        // 后端 runSessionMessage 启动 agent 失败会返回非 2xx; 这里必须抛出, 否则被静默吞掉
        // (表现: 有 loading 却一直收不到回复, 也无报错)。
        if (response.status.value !in 200..299) {
            val raw = response.bodyAsText()
            val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            error(obj?.string("error") ?: obj?.string("message") ?: "发送失败: HTTP ${response.status.value}")
        }
    }

    suspend fun terminate(sessionId: String) {
        client.post("$baseUrl/api/sessions/$sessionId/terminate") { addAuth() }
    }

    suspend fun stop(sessionId: String) {
        client.post("$baseUrl/api/sessions/$sessionId/stop") { addAuth() }
    }

    // 删除 Session(1v1 小莫/分身会话). 后端 DELETE /api/sessions/:id = 永久删除并关闭后台执行.
    suspend fun deleteSession(sessionId: String) {
        val response = client.delete("$baseUrl/api/sessions/${sessionId.encodeURLParameter()}") { addAuth() }
        if (response.status.value !in 200..299) {
            val obj = runCatching { json.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
            error(obj?.string("error") ?: obj?.string("message") ?: "删除会话失败: HTTP ${response.status.value}")
        }
    }

    suspend fun streamSession(
        sessionId: String,
        onConnected: suspend () -> Unit,
        onTyping: suspend (Boolean) -> Unit,
        onHistory: suspend (List<ChatMessage>) -> Unit,
        onJsonlHistory: suspend (List<StreamTextChunk>, Boolean, Boolean) -> Unit,
        onChunk: suspend (StreamTextChunk) -> Unit,
        onError: suspend (String) -> Unit,
        onProcess: suspend (String?) -> Unit = {},
    ) {
        var eventName = "message"
        val dataLines = mutableListOf<String>()
        try {
            client.prepareGet("$baseUrl/api/sessions/$sessionId/events") {
                addAuth()
                header(HttpHeaders.Accept, "text/event-stream")
                header("Cache-Control", "no-cache")
                timeout {
                    requestTimeoutMillis = SSE_TIMEOUT_MILLIS
                    socketTimeoutMillis = SSE_TIMEOUT_MILLIS
                }
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    if (response.status == HttpStatusCode.Unauthorized) {
                        onUnauthorized()
                        throw CancellationException("unauthorized")
                    }
                    onError("SSE 连接失败: HTTP ${response.status.value}")
                    return@execute
                }
                onConnected()
                val channel = response.bodyAsChannel()
                while (true) {
                    // 空闲看门狗：超时未收到数据（代理静默断连）或连接关闭都返回 null，跳出后由上层重连。
                    val line = withTimeoutOrNull(SSE_IDLE_TIMEOUT_MILLIS) { channel.readUTF8Line() }
                    if (line == null) break
                    when {
                        line.isBlank() -> {
                            val raw = dataLines.joinToString("\n")
                            if (raw.isNotBlank()) {
                                handleSseEvent(eventName, raw, onTyping, onHistory, onJsonlHistory, onChunk, onError, onProcess)
                            }
                            eventName = "message"
                            dataLines.clear()
                        }
                        line.startsWith(":") -> Unit
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(sanitizeStreamError(e))
        }
    }

    private fun sanitizeStreamError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("timed out", ignoreCase = true) || message.contains("timeout", ignoreCase = true) ->
                "网络连接超时，正在重连"
            message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("UnknownHost", ignoreCase = true) ||
                message.contains("nodename nor servname", ignoreCase = true) ->
                "无法解析服务器地址，请检查服务器地址"
            message.contains("Connection refused", ignoreCase = true) ->
                "服务器拒绝连接，请确认服务是否正常"
            message.contains("reset", ignoreCase = true) ||
                message.contains("broken pipe", ignoreCase = true) ||
                message.contains("closed", ignoreCase = true) ||
                message.contains("/api/sessions/", ignoreCase = true) ->
                "连接被中断，正在重连"
            else -> message.takeIf { it.isNotBlank() } ?: "连接中断，正在重连"
        }
    }

    private suspend fun handleSseEvent(
        eventName: String,
        raw: String,
        onTyping: suspend (Boolean) -> Unit,
        onHistory: suspend (List<ChatMessage>) -> Unit,
        onJsonlHistory: suspend (List<StreamTextChunk>, Boolean, Boolean) -> Unit,
        onChunk: suspend (StreamTextChunk) -> Unit,
        onError: suspend (String) -> Unit,
        onProcess: suspend (String?) -> Unit,
    ) {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return
        val obj = element as? JsonObject ?: return
        when (eventName) {
            "history" -> onHistory(parseMessages(obj["messages"] as? JsonArray))
            "jsonl_history" -> {
                val entries = obj["entries"] as? JsonArray
                val chunks = entries?.mapNotNull { parseAnyJsonlChunk(it) }.orEmpty()
                onJsonlHistory(
                    chunks,
                    obj["reset"]?.jsonPrimitive?.booleanOrNull == true,
                    obj["done"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
            "typing" -> onTyping(obj["active"]?.jsonPrimitive?.booleanOrNull == true)
            "jsonl_entry" -> {
                val entry = obj["entry"]
                val processLabel = extractProcessLabel(entry)
                if (processLabel != null) {
                    onProcess(processLabel)
                    // 过程条目也生成 ChatMessage, 携带 processType/processLabel,
                    // 在 UI 中渲染为可折叠卡片 (不再丢弃).
                    val processType = extractProcessType(entry)
                    val processText = extractProcessText(entry) ?: ""
                    // 跳过无实质内容的过程条目 (空壳)
                    if (processType != null || processText.isNotBlank()) {
                        val chunk = StreamTextChunk(
                            id = (entry as? JsonObject)?.string("id") ?: "process-${nowEpochMillis()}",
                            author = MessageAuthor.Momo,
                            text = processText,
                            time = nowShortTime(),
                            createdAtMillis = nowEpochMillis(),
                            processType = processType,
                            processLabel = processLabel,
                        )
                        onChunk(chunk)
                    }
                } else {
                    // 过滤无用条目: tool_result / computer_call_output / 空内容等
                    if (!isNoiseEntry(entry)) {
                        parseJsonlChunk(entry)?.let { onChunk(it) }
                    }
                }
            }
            "server_error" -> onError(obj.string("message") ?: "服务端错误")
        }
    }

    private fun extractProcessLabel(entry: JsonElement?): String? {
        val obj = entry as? JsonObject ?: return null
        val role = obj.string("role")?.lowercase()?.trim().orEmpty()
        val type = (obj.string("type") ?: obj.string("kind"))?.lowercase()?.trim().orEmpty()
        val message = obj["message"] as? JsonObject
        val messageType = (message?.string("type") ?: message?.string("kind"))?.lowercase()?.trim().orEmpty()
        val payload = obj["payload"] as? JsonObject
        val payloadType = (payload?.string("type") ?: payload?.string("kind"))?.lowercase()?.trim().orEmpty()
        val allTypes = listOf(type, messageType, payloadType)
        val toolName = listOf(obj, message, payload)
            .firstNotNullOfOrNull { it?.string("name") }
            ?.takeIf { name -> name.isNotBlank() }

        val thinkingSet = setOf("thinking", "thought", "reasoning", "reasoning_summary", "redacted_thinking")
        val toolCallSet = setOf(
            "tool_use", "tool_call", "function_call", "custom_tool_call", "mcp_call",
        )
        val toolResultSet = setOf("tool_result", "tool_output", "function_call_output")

        return when {
            allTypes.any { it in thinkingSet } -> "正在思考…"
            allTypes.any { it in toolCallSet } ->
                if (!toolName.isNullOrBlank()) "正在调用工具：$toolName" else "正在调用工具…"
            allTypes.any { it in setOf("web_search_call", "file_search_call") } -> "正在搜索…"
            allTypes.any { it in setOf("computer_call", "computer_call_output") } -> "正在操作…"
            allTypes.any { it == "image_generation_call" } -> "正在生成图像…"
            allTypes.any { it in setOf("code_interpreter_call", "local_shell_call") } -> "正在执行代码…"
            allTypes.any { it in toolResultSet } -> null
            role in setOf("tool", "function") ->
                if (!toolName.isNullOrBlank()) "正在调用工具：$toolName" else "正在处理…"
            else -> null
        }
    }

    /** 从 process 条目提取类型分类: "thinking" / "tool_call" / "tool_result" / "search" 等. */
    private fun extractProcessType(entry: JsonElement?): String? {
        val obj = entry as? JsonObject ?: return null
        val role = obj.string("role")?.lowercase()?.trim().orEmpty()
        val type = (obj.string("type") ?: obj.string("kind"))?.lowercase()?.trim().orEmpty()
        val message = obj["message"] as? JsonObject
        val messageType = (message?.string("type") ?: message?.string("kind"))?.lowercase()?.trim().orEmpty()
        val payload = obj["payload"] as? JsonObject
        val payloadType = (payload?.string("type") ?: payload?.string("kind"))?.lowercase()?.trim().orEmpty()
        val allTypes = listOf(type, messageType, payloadType)

        val thinkingSet = setOf("thinking", "thought", "reasoning", "reasoning_summary", "redacted_thinking")
        val toolCallSet = setOf("tool_use", "tool_call", "function_call", "custom_tool_call", "mcp_call")
        val toolResultSet = setOf("tool_result", "tool_output", "function_call_output")

        return when {
            allTypes.any { it in thinkingSet } -> "thinking"
            allTypes.any { it in toolCallSet } -> "tool_call"
            allTypes.any { it in toolResultSet } -> "tool_result"
            allTypes.any { it in setOf("web_search_call", "file_search_call") } -> "search"
            allTypes.any { it in setOf("computer_call", "computer_call_output") } -> "computer"
            allTypes.any { it == "image_generation_call" } -> "image"
            allTypes.any { it in setOf("code_interpreter_call", "local_shell_call") } -> "code"
            role in setOf("tool", "function") -> "tool_call"
            else -> null
        }
    }

    /** 从 process 条目提取展示文本 (工具名 / 输出摘要), 截断到 ~2000 字符避免渲染膨胀. */
    private fun extractProcessText(entry: JsonElement?): String? {
        val obj = entry as? JsonObject ?: return null
        val toolName = listOf(obj, obj["message"] as? JsonObject, obj["payload"] as? JsonObject)
            .firstNotNullOfOrNull { it?.string("name") }
            ?.takeIf { name -> name.isNotBlank() }
        val content = findText(obj) ?: (obj["payload"] as? JsonObject)?.let { findText(it) } ?: ""
        val text = when {
            !toolName.isNullOrBlank() && content.isNotBlank() -> "$toolName: $content"
            !toolName.isNullOrBlank() -> toolName
            content.isNotBlank() -> content
            else -> return null
        }
        return if (text.length > 2000) text.take(2000) + "…" else text
    }

    /** 判断是否为无用的噪音条目 (tool_result 输出 / 取消标记 / 空壳等). */
    private fun isNoiseEntry(entry: JsonElement?): Boolean {
        val obj = entry as? JsonObject ?: return true
        val type = (obj.string("type") ?: obj.string("kind"))?.lowercase()?.trim().orEmpty()
        val role = obj.string("role")?.lowercase()?.trim().orEmpty()
        // 工具结果 / 计算机输出: extractProcessLabel 返回 null, 但内容通常很大且无展示价值
        if (type in setOf("tool_result", "tool_output", "function_call_output", "computer_call_output")) return true
        if (role == "tool" && type.isEmpty()) return true
        // 检查 text/content 是否为纯噪音 (取消 / 空操作等 )
        val text = findText(obj)?.trim() ?: ""
        if (text.length <= 4 && text in setOf("已取消", "Cancelled", "canceled", "(已取消)", "(空)", "null", "{}", "[]")) return true
        // assistant 消息但 content 为空且无实质 payload: 大概率是事件壳
        if (role == "assistant" && type.isEmpty() && text.isBlank()) return true
        return false
    }

    private fun parseMessages(array: JsonArray?): List<ChatMessage> {
        if (array == null) return emptyList()
        return array.mapIndexedNotNull { index, item ->
            val obj = item as? JsonObject ?: return@mapIndexedNotNull null
            val content = obj.string("content") ?: obj.string("text") ?: return@mapIndexedNotNull null
            val role = obj.string("role") ?: obj.string("type") ?: ""
            val author = when {
                role.contains("user", ignoreCase = true) -> MessageAuthor.User
                role.contains("system", ignoreCase = true) -> MessageAuthor.System
                else -> MessageAuthor.Momo
            }
            if (isAssistantInternalUserMessage(author, content)) return@mapIndexedNotNull null
            // 运维通知及其 agent 转述("又来了 xxx 第N次")对用户无价值, 不进聊天流。
            // 注意: 不限 System — agent 常把提醒内容当普通回复转述(author=assistant)。
            if (isOperationalNotice(content)) return@mapIndexedNotNull null
            val createdAt = obj.string("created_at") ?: obj.string("timestamp")
            ChatMessage(
                id = obj.string("id") ?: "history-$index",
                author = author,
                text = content,
                time = formatBackendTime(createdAt).ifBlank { nowShortTime() },
                createdAtMillis = parseBackendTimeMillis(createdAt),
            )
        }
    }

    /**
     * 判断 role=system 的消息是否为服务端运维通知(不该出现在用户聊天流里):
     * - tmux 巡检清理: "后台巡检已自动清理…agent tmux window", 带 backend=/window=/pid=/cleanup_reason= 技术字段
     * - forgotten-flag 自动提醒: "[自动提醒] …running.flag…"
     * 特征匹配而非精确匹配, 后端文案微调不至于漏。
     */
    internal fun isOperationalNotice(content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return true
        if (text.startsWith("[自动提醒]")) return true
        if (text.contains("后台巡检已自动清理")) return true
        if (text.contains("自动清理不活跃")) return true
        if (text.contains("工作目录不可用")) return true
        // 运维提醒的"转述"(agent 收到 system 提醒后有时会复述其内容): 命中提醒核心特征词组也过滤。
        if (text.contains("running.flag") && (text.contains("停工") || text.contains("仍存在"))) return true
        // forgotten-flag 自动提醒的计数句式("…提醒…第 N 次"/"又来了…第 N 次"): 同源运维噪声。
        if (Regex("第\\s*\\d+\\s*次").containsMatchIn(text) &&
            (text.contains("提醒") || text.contains("又来") || text.contains("flag") ||
                text.contains("notify") || text.contains("自动") || text.contains("检测到"))
        ) return true
        // 含多个技术 KV 字段行(backend=/window=/pid=/cleanup_reason=)的系统消息 → 运维性质
        if (text.contains("cleanup_reason=") || text.contains("last_activity_source=")) return true
        return false
    }

    private fun parseAssistantSnapshots(array: JsonArray?): List<AssistantSnapshot> {
        if (array == null) return emptyList()
        return array.mapNotNull { item -> (item as? JsonObject)?.let { parseAssistantSnapshot(it) } }
    }

    private fun parseAssistantSnapshot(obj: JsonObject): AssistantSnapshot {
        val sessionObj = obj["session"] as? JsonObject
        val session = parseSession(sessionObj ?: obj)
        val messages = parseMessages(obj["messages"] as? JsonArray)
        val statusObj = obj["status"] as? JsonObject
        val status = AssistantSessionStatus(
            working = statusObj?.get("working")?.jsonPrimitive?.booleanOrNull == true,
            failed = statusObj?.get("failed")?.jsonPrimitive?.booleanOrNull == true,
            agentStatus = statusObj?.string("agent_status").orEmpty(),
        )
        return AssistantSnapshot(
            session = session.copy(
                agentStatus = status.agentStatus.ifBlank { session.agentStatus },
                jobFailed = session.jobFailed ?: status.failed,
            ),
            messages = messages,
            status = status,
        )
    }

    private fun parseSession(obj: JsonObject): Session =
        Session(
            sessionId = obj.string("session_id").orEmpty(),
            name = obj.string("name").orEmpty(),
            description = obj.string("description").orEmpty(),
            assistantRole = obj.string("assistant_role").orEmpty(),
            projectId = obj.string("project_id").orEmpty(),
            issueId = obj.string("issue_id").orEmpty(),
            model = obj.string("model").orEmpty(),
            agentStatus = obj.string("agent_status").orEmpty(),
            createdAt = obj.string("created_at").orEmpty(),
            lastActive = obj.string("last_active").orEmpty(),
            jobFailed = obj["job_failed"]?.jsonPrimitive?.booleanOrNull,
            jobAccomplished = obj["job_accomplished"]?.jsonPrimitive?.booleanOrNull,
        )

    internal fun parseJsonlChunk(entry: JsonElement?): StreamTextChunk? {
        val obj = entry as? JsonObject ?: return null
        val rawText = findText(obj)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val author = entryAuthor(obj) ?: return null
        if (isAssistantInternalUserMessage(author, rawText)) return null
        // 运维通知及 agent 转述不进流式消息流(不限 System, 与 parseMessages 一致)。
        if (isOperationalNotice(rawText)) return null
        val (cleanedText, voiceText) = extractVoiceMarker(rawText)
        if (author == MessageAuthor.Momo && cleanedText.isBlank() && voiceText.isNullOrBlank()) return null
        val createdAt = obj.string("timestamp") ?: obj.string("created_at") ?: ((obj["payload"] as? JsonObject)?.string("timestamp"))
        return StreamTextChunk(
            id = obj.string("id") ?: obj.string("uuid") ?: "event-${rawText.hashCode()}-${nowShortTime()}",
            author = author,
            text = if (author == MessageAuthor.User) rawText else cleanedText,
            voiceText = voiceText,
            time = formatBackendTime(createdAt).ifBlank { nowShortTime() },
            createdAtMillis = parseBackendTimeMillis(createdAt),
        )
    }

    /** 解析任意 jsonl entry (含 process 条目), 用于 history 回放不丢失过程卡片. */
    private fun parseAnyJsonlChunk(entry: JsonElement?): StreamTextChunk? {
        // 先走普通解析 (非 process)
        parseJsonlChunk(entry)?.let { return it }
        // 再尝试 process 条目
        val obj = entry as? JsonObject ?: return null
        val processLabel = extractProcessLabel(entry)
        val processType = extractProcessType(entry)
        if (processLabel == null && processType == null) return null
        val text = extractProcessText(entry) ?: ""
        return StreamTextChunk(
            id = obj.string("id") ?: obj.string("uuid") ?: "history-process-${nowEpochMillis()}",
            author = MessageAuthor.Momo,
            text = text,
            time = nowShortTime(),
            createdAtMillis = parseBackendTimeMillis(obj.string("timestamp") ?: obj.string("created_at")),
            processType = processType ?: processLabel,
            processLabel = processLabel,
        )
    }

    private fun entryAuthor(obj: JsonObject): MessageAuthor? {
        val role = obj.string("role")
        val type = obj.string("type") ?: obj.string("kind")
        if (role == "user" || type == "user" || type == "user_input") return MessageAuthor.User
        if (isProcessOnlyType(role, type)) return null
        if (role == "assistant" || type == "assistant") return MessageAuthor.Momo
        val message = obj["message"] as? JsonObject
        val messageRole = message?.string("role")
        val messageType = message?.string("type")
        if (messageRole == "assistant" && !isProcessOnlyType(messageRole, messageType)) return MessageAuthor.Momo
        if (messageRole == "user") return MessageAuthor.User
        val payload = obj["payload"] as? JsonObject
        val payloadRole = payload?.string("role")
        val payloadType = payload?.string("type")
        if (payloadRole == "assistant" && !isProcessOnlyType(payloadRole, payloadType)) return MessageAuthor.Momo
        if (payloadRole == "user") return MessageAuthor.User
        if (payloadType == "message" && payloadRole == "assistant") return MessageAuthor.Momo
        if (payloadType == "output_text" || payloadType == "text") return MessageAuthor.Momo
        return null
    }

    private fun isProcessOnlyType(role: String?, type: String?): Boolean {
        val t = type?.lowercase()?.trim().orEmpty()
        if (t in PROCESS_ONLY_TYPES) return true
        val r = role?.lowercase()?.trim().orEmpty()
        if (r in PROCESS_ONLY_ROLES) return true
        return false
    }

    private fun extractVoiceMarker(raw: String): Pair<String, String?> {
        val matches = VOICE_MARKER_REGEX.findAll(raw).toList()
        if (matches.isEmpty()) return raw to null
        val voiceText = matches.mapNotNull { it.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() } }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
        return stripVoiceMarkers(raw) to voiceText
    }

    private fun isAssistantInternalUserMessage(author: MessageAuthor, raw: String): Boolean {
        if (author != MessageAuthor.User) return false
        val content = raw.trim()
        if (content.isBlank()) return false
        return content.startsWith(ASSISTANT_INTERNAL_NOTIFICATION_PROMPT_PREFIX) ||
            LEGACY_ASSISTANT_INTERNAL_NOTIFICATION_MARKERS.any { marker -> content.contains(marker) }
    }

    private fun findText(obj: JsonObject): String? {
        val direct = obj.string("content") ?: obj.string("text") ?: obj.string("message")
        if (!direct.isNullOrBlank() && !direct.trim().startsWith("{")) return direct
        val msg = obj["message"] as? JsonObject
        val nested = msg?.string("content") ?: msg?.string("text") ?: contentBlocksText(msg?.get("content"))
        if (!nested.isNullOrBlank()) return nested
        val payload = obj["payload"] as? JsonObject
        val payloadText = payload?.string("text")
            ?: payload?.string("output_text")
            ?: payload?.string("content")
            ?: contentBlocksText(payload?.get("content"))
        if (!payloadText.isNullOrBlank()) return payloadText
        return null
    }

    private fun contentBlocksText(content: JsonElement?): String? {
        if (content == null) return null
        if (content is JsonPrimitive) return content.contentOrNull
        val array = content as? JsonArray ?: return null
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull (item as? JsonPrimitive)?.contentOrNull
            val type = obj.string("type")
            if (type != null && type != "text" && type != "output_text") return@mapNotNull null
            obj.string("text") ?: obj.string("output_text")
        }.filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun decodeLogin(obj: JsonObject): LoginResult =
        json.decodeFromJsonElement(LoginResult.serializer(), obj)

    private fun decodeAssistantMessageResult(obj: JsonObject): AssistantMessageResult {
        val direct = runCatching { json.decodeFromJsonElement(AssistantMessageResult.serializer(), obj) }
            .getOrDefault(AssistantMessageResult())
        val sessionObj = findObject(obj, "session")
        val projectObj = findObject(obj, "project")
        val issueObj = findObject(obj, "issue")
        val nestedSession = sessionObj?.let { runCatching { json.decodeFromJsonElement(Session.serializer(), it) }.getOrNull() }
        val nestedProject = projectObj?.let { runCatching { json.decodeFromJsonElement(Project.serializer(), it) }.getOrNull() }
        val nestedIssue = issueObj?.let { runCatching { json.decodeFromJsonElement(com.mobius.momo.domain.Issue.serializer(), it) }.getOrNull() }
        return direct.copy(
            sessionId = direct.sessionId.ifBlank { findString(obj, "session_id") },
            taskId = direct.taskId.ifBlank { findString(obj, "task_id") },
            session = direct.session ?: nestedSession,
            project = if (direct.project.id.isNotBlank()) direct.project else nestedProject ?: direct.project,
            issue = if (direct.issue.id.isNotBlank()) direct.issue else nestedIssue ?: direct.issue,
        )
    }

    private fun parseErrorMessage(raw: String): String {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        return obj?.string("error")
            ?: obj?.string("message")
            ?: "用户名或密码不正确"
    }

    private fun io.ktor.client.request.HttpRequestBuilder.addAuth() {
        storage.getToken()?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun findString(obj: JsonObject, key: String): String {
        obj.string(key)?.takeIf { it.isNotBlank() }?.let { return it }
        for (value in obj.values) {
            val child = value as? JsonObject ?: continue
            val found = findString(child, key)
            if (found.isNotBlank()) return found
        }
        return ""
    }

    private fun findObject(obj: JsonObject, key: String): JsonObject? {
        (obj[key] as? JsonObject)?.let { return it }
        for (value in obj.values) {
            val child = value as? JsonObject ?: continue
            val found = findObject(child, key)
            if (found != null) return found
        }
        return null
    }

}

private val VOICE_MARKER_REGEX = Regex("""PushVoiceToUser\s*\(\s*(["'])([\s\S]*?)(?<!\\)\1\s*\)""")
private val VOICE_MARKER_LINE_REGEX = Regex(
    """^[ \t]*PushVoiceToUser\s*\(\s*(["'])([\s\S]*?)(?<!\\)\1\s*\)[ \t]*;?[ \t]*$""",
    RegexOption.MULTILINE,
)
private val VOICE_EMPTY_MARKER_REGEX = Regex("""PushVoiceToUser\s*\(\s*["']\s*["']\s*\)\s*;?""")
private val VOICE_MARKER_BLANK_LINES_REGEX = Regex("""\n[ \t]*\n+""")
private val VOICE_TRAILING_WS_PER_LINE = Regex("""[ \t]+\n""")
private val VOICE_MULTI_SPACE = Regex("""[ \t]{2,}""")
private const val ASSISTANT_INTERNAL_NOTIFICATION_PROMPT_PREFIX = "[[mobius:assistant-internal-notification-prompt]]"
private val LEGACY_ASSISTANT_INTERNAL_NOTIFICATION_MARKERS = listOf(
    "请你撰写消息通知用户",
    "请你撰写消息通知当前管理员",
)
private const val SSE_TIMEOUT_MILLIS = 86_400_000L

// SSE 是长连接：在两次对话之间连接会长时间空闲。很多反向代理（nginx 默认 proxy_read_timeout
// 60s）/移动网络会静默掐断空闲连接，而服务端若不发 keepalive 心跳（": ping" 注释行），客户端
// 的 socket read 会一直挂起、察觉不到断开。这里给每次读取设一个空闲看门狗：超过该时长收不到
// 任何字节就主动结束本次连接，由上层静默重连，避免"假活"的僵死连接。
// 前提：服务端 SSE 应每 ~15s 发一行 keepalive，正常情况下此看门狗不会触发。
private const val SSE_IDLE_TIMEOUT_MILLIS = 90_000L

fun stripVoiceMarkers(raw: String): String =
    raw.replace(VOICE_MARKER_LINE_REGEX, "")
        .replace(VOICE_MARKER_REGEX, "")
        .replace(VOICE_EMPTY_MARKER_REGEX, "")
        .replace(VOICE_TRAILING_WS_PER_LINE, "\n")
        .replace(VOICE_MULTI_SPACE, " ")
        .replace(VOICE_MARKER_BLANK_LINES_REGEX, "\n")
        .trim()

internal fun multipartFileContent(
    fieldName: String,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
    boundaryPrefix: String,
    fallbackName: String,
): OutgoingContent.ByteArrayContent {
    // 手动构造标准 multipart/form-data body（ByteArrayContent：一次性写出、Content-Length 精确、
    // 格式 100% 符合 RFC），绕过 Ktor MultiPartFormDataContent(WriteChannelContent 流式) + OkHttp
    // engine 组合在部分后端(multer)上 body 被解析为空的兼容性问题——表现为「上传了 N 字节，后端
    // 却 ASR_AUDIO_EMPTY（multer.single 找不到字段）」。
    val boundary = multipartBoundary(boundaryPrefix, bytes)
    val headers = multipartFileHeaders(
        fieldName = fieldName,
        fileName = fileName,
        mimeType = mimeType,
        fallbackName = fallbackName,
    )
    val disposition = headers[HttpHeaders.ContentDisposition] ?: "form-data; name=\"$fieldName\""
    val partContentType = headers[HttpHeaders.ContentType] ?: mimeType
    val partHeader = "--$boundary\r\n${HttpHeaders.ContentDisposition}: $disposition\r\n${HttpHeaders.ContentType}: $partContentType\r\n\r\n"
    val closingBoundary = "\r\n--$boundary--\r\n"
    val body = partHeader.encodeToByteArray() + bytes + closingBoundary.encodeToByteArray()
    return object : OutgoingContent.ByteArrayContent() {
        override fun bytes(): ByteArray = body
        override val contentType: ContentType =
            ContentType.MultiPart.FormData.withParameter("boundary", boundary)
    }
}

internal fun multipartFileHeaders(
    fieldName: String,
    fileName: String,
    mimeType: String,
    fallbackName: String,
): Headers {
    val displayName = safeDisplayFileName(fileName, fallbackName)
    val asciiName = asciiFilenameFallback(displayName, fallbackFileNameForMime(mimeType, fallbackName))
    val disposition = buildString {
        append("form-data; name=\"")
        append(quotedHeaderValue(fieldName))
        append("\"; filename=\"")
        append(quotedHeaderValue(asciiName.ifBlank { fallbackName }))
        append("\"")
    }
    return Headers.build {
        append(HttpHeaders.ContentDisposition, disposition)
        append(HttpHeaders.ContentType, safeMimeType(mimeType))
    }
}

private fun multipartBoundary(prefix: String, bytes: ByteArray): String {
    val unsignedHash = bytes.contentHashCode().toLong() and 0xffffffffL
    return "$prefix-${nowEpochMillis()}-${unsignedHash.toString(16)}-${bytes.size}"
}

private fun safeDisplayFileName(value: String, fallback: String): String {
    val cleaned = value
        .replace("\r", "")
        .replace("\n", "")
        .replace("\"", "")
        .trim()
    return cleaned.ifBlank { fallback }
}

private fun asciiFilenameFallback(value: String, fallback: String): String {
    val ascii = buildString {
        value.forEach { char ->
            if (char.code in 0x20..0x7e && char != '"' && char != '\\' && char != ';') {
                append(char)
            } else {
                append('_')
            }
        }
    }.trim().trim('_').trim()
    if (ascii.isNotBlank() && !ascii.startsWith(".") && ascii.any { it.isLetterOrDigit() }) {
        return ascii
    }
    val extension = fileExtension(value)
    val fallbackBase = fallback.ifBlank { "file.bin" }
    return if (extension.isBlank() || fileExtension(fallbackBase).isNotBlank()) {
        fallbackBase
    } else {
        "${fallbackBase.substringBeforeLast('.')}.${extension}"
    }
}

private fun quotedHeaderValue(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun safeMimeType(value: String): String {
    val cleaned = value.trim()
    return if (cleaned.isNotEmpty() && '\r' !in cleaned && '\n' !in cleaned) {
        cleaned
    } else {
        "application/octet-stream"
    }
}

private fun fallbackFileNameForMime(mimeType: String, fallback: String): String {
    val existing = fallback.trim().ifBlank { "file.bin" }
    if (fileExtension(existing).isNotBlank()) return existing
    val extension = when (safeMimeType(mimeType).lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "application/pdf" -> "pdf"
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.ms-excel" -> "xls"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        else -> "bin"
    }
    val base = if (mimeType.trim().startsWith("image/")) "image" else existing
    return "$base.$extension"
}

private fun fileExtension(name: String): String {
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
    return ext.takeIf { it.isNotBlank() && it.length <= 12 && it.all { char -> char.isLetterOrDigit() } }.orEmpty()
}

private val PROCESS_ONLY_TYPES = setOf(
    "thinking",
    "thought",
    "reasoning",
    "reasoning_summary",
    "tool_use",
    "tool_call",
    "tool_result",
    "tool_output",
    "function_call",
    "function_call_output",
    "web_search_call",
    "file_search_call",
    "computer_call",
    "computer_call_output",
    "image_generation_call",
    "code_interpreter_call",
    "local_shell_call",
    "mcp_call",
    "custom_tool_call",
    "redacted_thinking",
)
private val PROCESS_ONLY_ROLES = setOf("tool", "function")
