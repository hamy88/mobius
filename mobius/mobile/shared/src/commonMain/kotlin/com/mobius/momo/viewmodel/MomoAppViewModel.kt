package com.mobius.momo.viewmodel

import com.mobius.momo.data.FilePicker
import com.mobius.momo.data.AssistantPromptAttachment
import com.mobius.momo.data.AssistantPresetRequiresSessionDeleteException
import com.mobius.momo.data.DoubaoTtsEngine
import com.mobius.momo.data.MobiusApi
import com.mobius.momo.data.NotificationGateway
import com.mobius.momo.data.PickedFile
import com.mobius.momo.data.ReentrantLock
import com.mobius.momo.data.ServerAddressRepository
import com.mobius.momo.data.ServerEntry
import com.mobius.momo.data.PROJECTS_LITE_MODE_KEY
import com.mobius.momo.data.SERVER_BASE_URL_PREFERENCE
import com.mobius.momo.data.GROUP_RELAY_OVERLAY_KEY
import com.mobius.momo.data.SECURE_PREF_SELECTED_VOICE
import com.mobius.momo.data.SECURE_PREF_TTS_PLAYBACK_MODE
import com.mobius.momo.data.SECURE_PREF_TTS_ENABLED
import com.mobius.momo.data.PUSH_PLATFORM_JPUSH
import com.mobius.momo.data.PUSH_PLATFORM_HUAWEI
import com.mobius.momo.data.PushProvider
import com.mobius.momo.data.SECURE_PREF_PUSH_ENABLED
import com.mobius.momo.data.SECURE_PREF_PUSH_TOKEN
import com.mobius.momo.data.SECURE_PREF_PUSH_TOKEN_REGISTERED
import com.mobius.momo.data.SECURE_PREF_PUSH_HMS_TOKEN
import com.mobius.momo.data.SECURE_PREF_PUSH_HMS_REGISTERED
import com.mobius.momo.data.TAB_CACHE_CLONES
import com.mobius.momo.data.TAB_CACHE_LITE_SESSIONS
import com.mobius.momo.data.TAB_CACHE_CONTACTS
import com.mobius.momo.data.TAB_CACHE_CONVERSATIONS
import com.mobius.momo.data.TAB_CACHE_PROJECTS
import com.mobius.momo.data.SecureStorage
import com.mobius.momo.data.SpeechPermissionController
import com.mobius.momo.data.SpeechPermissionStatus
import com.mobius.momo.data.SpeechRecognitionEvent
import com.mobius.momo.data.SpeechRecognizer
import com.mobius.momo.data.StoredTokenMetadata
import com.mobius.momo.data.TTS_SYSTEM_VOICE_ID
import com.mobius.momo.data.TOKEN_MAX_AGE_MILLIS
import com.mobius.momo.data.TOKEN_STORAGE_VERSION
import com.mobius.momo.data.stripVoiceMarkers
import com.mobius.momo.data.TtsController
import com.mobius.momo.data.TtsEngine
import com.mobius.momo.data.TtsEvent
import com.mobius.momo.data.TtsPlaybackMode
import com.mobius.momo.data.Voice
import com.mobius.momo.data.createFilePicker
import com.mobius.momo.data.createSecureStorage
import com.mobius.momo.data.createSpeechPermissionController
import com.mobius.momo.data.createSpeechRecognizer
import com.mobius.momo.data.createSystemTtsEngine
import com.mobius.momo.data.createPushProvider
import com.mobius.momo.data.isAppInForeground
import com.mobius.momo.data.normalizeMobiusBaseUrl
import com.mobius.momo.data.nowEpochMillis
import com.mobius.momo.data.platformIsIOS
import com.mobius.momo.data.nowIsoTime
import com.mobius.momo.data.nowShortTime
import com.mobius.momo.data.parseBackendTimeMillis
import com.mobius.momo.data.platformBuildBaseUrl
import com.mobius.momo.data.resolveMobiusBaseUrl
import com.mobius.momo.domain.AssistantSnapshot
import com.mobius.momo.domain.AssistantWorkspace
import com.mobius.momo.domain.ChatMessage
import com.mobius.momo.domain.ContextPickItem
import com.mobius.momo.domain.ConversationDetail
import com.mobius.momo.domain.ConversationMemberInput
import com.mobius.momo.domain.ConversationMessage
import com.mobius.momo.domain.ConversationSummary
import com.mobius.momo.domain.Issue
import com.mobius.momo.domain.LiteSessionEntry
import com.mobius.momo.domain.MessageAuthor
import com.mobius.momo.domain.Project
import com.mobius.momo.domain.Session
import com.mobius.momo.domain.SessionModelOption
import com.mobius.momo.domain.SessionPersonalityOption
import com.mobius.momo.domain.SessionPresetConfig
import com.mobius.momo.domain.StreamTextChunk
import com.mobius.momo.domain.User
import com.mobius.momo.domain.UserDirectoryEntry
import com.mobius.momo.ui.ProjectListTab
import com.mobius.momo.ui.LiteListTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class AppScreen {
    Login,
    Home,
    Clones,
    Settings,
    Contacts,
    GroupChat,
    ChatList,
    Profile,
    CreateClone,
    CreateGroup,
    GroupInfo,
    // 项目钻取: 项目列表(tab) -> 项目下 Issue 列表 -> Issue 下 Session 列表 -> 进聊天页(Home)
    Projects,
    ProjectIssues,
    IssueSessions,
    // 扩展应用(App 内 WebView 打开 mobius 拓展前端 /extension/<name>/)
    ExtensionWeb,
    CreateProject,
    CreateIssue,
    CreateSession,
}

enum class LoginStep {
    Username,
    Password,
}

enum class ThemeMode {
    System,
    Light,
    Dark,
}

enum class ThemePalette(val label: String) {
    Default("默认"),
    Aurora("暖橙"),
    Mint("深绿"),
    Coral("玫红"),
    Gold("金色"),
    Nebula("科技"),
}

data class UiState(
    val screen: AppScreen = AppScreen.Login,
    val loginStep: LoginStep = LoginStep.Username,
    val username: String = "",
    val password: String = "",
    val user: User? = null,
    val workspace: AssistantWorkspace? = null,
    val messages: List<ChatMessage> = sampleMessages(),
    val clones: List<Session> = emptyList(),
    val projects: List<Project> = emptyList(),
    // ===== 项目钻取(项目 -> Issue -> Session -> 聊天) =====
    val projectsLoading: Boolean = false,
    // 下拉刷新专用: 与 projectsLoading 分开。刷新时不替换列表、不显示整屏 loading, 仅顶部 PullToRefresh 转圈。
    val projectsRefreshing: Boolean = false,
    // 项目列表顶部选中的 tab(提升到 UiState, 跨导航持久; 从扩展应用返回时仍停在「扩展」)。
    val projectsSelectedTab: ProjectListTab = ProjectListTab.All,
    // 精简模式: 项目页直接平铺所有 session(活跃/非活跃分组), 不走 项目→Issue→Session 钻取。
    val projectsLiteMode: Boolean = false,
    // 精简模式聚合的 session 列表(带所属项目/Issue 名)。
    val liteSessions: List<LiteSessionEntry> = emptyList(),
    val liteSessionsLoading: Boolean = false,
    // 精简模式列表 tab: 活跃 / 非活跃 (跨导航持久)。
    val liteSelectedTab: LiteListTab = LiteListTab.All,
    val projectsSearchText: String = "",
    val projectsActiveWindowDays: Long = 7L,
    val activeProject: Project? = null,
    // ===== 扩展应用(App 内 WebView) =====
    val activeExtensionTitle: String = "",
    val activeExtensionUrl: String = "",
    val activeExtensionToken: String = "",
    val projectIssues: List<Issue> = emptyList(),
    val projectIssuesLoading: Boolean = false,
    val activeIssue: Issue? = null,
    val issueSessionsList: List<Session> = emptyList(),
    val issueSessionsLoading: Boolean = false,
    // 聊天页返回目标: 从项目钻取进聊天时记 IssueSessions, 返回回钻取栈; 否则回 ChatList(原行为).
    val chatReturnTarget: AppScreen? = null,
    val activeSessionId: String = "",
    val activeSessionTitle: String = "我的主 Mobius",
    // 当前会话的 agent 运行状态(后端 agent_status: idle/running/waiting/completed/failed),
    // 聊天页顶栏显示"任务进行中/已完成/失败"进度胶囊。
    val activeSessionStatus: String = "",
    // 当前会话使用的模型名(后端 model_label, 如 "GLM-5.2"); 项目钻取 session 聊天页显示。
    val activeSessionModelLabel: String? = null,
    val input: String = "",
    val composerInputMode: ComposerInputMode = ComposerInputMode.Text,
    val attachments: List<PendingAttachment> = emptyList(),
    val loading: Boolean = false,
    val typing: Boolean = false,
    val menuOpen: Boolean = false,
    val cloneSheetOpen: Boolean = false,
    val presetSheetOpen: Boolean = false,
    val presetLoading: Boolean = false,
    val presetSaving: Boolean = false,
    val presetError: String = "",
    val presetConfirmDelete: Boolean = false,
    val presetConfirmSessionName: String = "",
    val presetDraft: SessionPresetConfig = SessionPresetConfig(),
    val presetPersonalityOptions: List<SessionPersonalityOption> = defaultAssistantPersonalityOptions(),
    val cloneTitle: String = "",
    val cloneDescription: String = "",
    // 创建项目表单
    val createProjectName: String = "",
    val createProjectDescription: String = "",
    val creatingProject: Boolean = false,
    // 创建 Issue 表单
    val createIssueTitle: String = "",
    val createIssueDescription: String = "",
    val creatingIssue: Boolean = false,
    val createIssueModel: String = "",
    // 创建 Session 表单
    val createSessionTitle: String = "",
    val createSessionDescription: String = "",
    val creatingSession: Boolean = false,
    // 新建会话的模型/skill/memory 选择(方案: 默认全启用, 排除未勾选项)。
    val createSessionModel: String = "",
    val sessionSkills: List<ContextPickItem> = emptyList(),
    val sessionMemories: List<ContextPickItem> = emptyList(),
    val sessionExcludedSkills: Set<String> = emptySet(),
    val sessionExcludedMemories: Set<String> = emptySet(),
    val contextLoading: Boolean = false,
    val cloneModel: String = "codex",
    val cloneModelOptions: List<SessionModelOption> = listOf(
        SessionModelOption("codex", "GPT-5.5 (Codex)", sub = "默认代码任务模型", backend = "tmux-codex"),
        SessionModelOption("opus", "Opus", sub = "Claude Code 高能力模型", backend = "tmux-claude-code"),
    ),
    val toast: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val themePalette: ThemePalette = ThemePalette.Default,
    val pushEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val ttsPlaybackMode: TtsPlaybackMode = TtsPlaybackMode.Selected,
    val availableVoices: List<Voice> = emptyList(),
    val ttsConfigured: Boolean = true,
    val voicesLoading: Boolean = false,
    val voicesLoadFailed: Boolean = false,
    val selectedVoice: String = TTS_SYSTEM_VOICE_ID,
    val sendingMessage: Boolean = false,
    val isStreaming: Boolean = false,
    val queuedUserMessages: List<ChatMessage> = emptyList(),
    val speechPermissionGranted: Boolean = false,
    val speechPermissionDenied: Boolean = false,
    val voiceRecording: Boolean = false,
    val voiceTranscribing: Boolean = false,
    val voiceCanceling: Boolean = false,
    val voiceTranscript: String = "",
    val voiceVolumeLevel: Int = 0,
    val ttsSpeakingMessageId: String? = null,
    // 该消息的语音正在「取音频/连接」(尚未出声): 播放按钮显示 loading 转圈, 出声后(onStart)清空。
    val ttsFetchingMessageId: String? = null,
    val passwordRequired: Boolean = false,
    val serverBaseUrl: String = "",
    // 登录页「服务器地址列表」: 最近使用倒序。空 = 首次使用, 登录页回退为纯输入框。
    val serverEntries: List<ServerEntry> = emptyList(),
    val streamingProcess: String? = null,
    // ===== 群聊 =====
    val conversations: List<ConversationSummary> = emptyList(),
    val contacts: List<UserDirectoryEntry> = emptyList(),
    val contactsSearch: String = "",
    val contactsLoading: Boolean = false,
    // 各 tab 首次数据是否已到位(网络或磁盘快照任一)：false=首次加载中(显示骨架屏),
    // true 后列表为空才是"真空"(显示空态文案)。区分"加载中"与"没有数据"。
    val conversationsLoaded: Boolean = false,
    val contactsLoaded: Boolean = false,
    val clonesLoaded: Boolean = false,
    // 下拉刷新专用标记(与 *Loading 分开)：刷新期间保留旧列表，仅顶部转圈。
    val conversationsRefreshing: Boolean = false,
    val contactsRefreshing: Boolean = false,
    val clonesRefreshing: Boolean = false,
    val activeConversation: ConversationDetail? = null,
    val groupMessages: List<ConversationMessage> = emptyList(),
    val groupConnected: Boolean = false,
    val showCreateGroup: Boolean = false,
    val createGroupName: String = "",
    val createGroupSelectedUserIds: Set<String> = emptySet(),
    val createGroupSelectedAgentSessions: Set<String> = emptySet(),
    val groupSending: Boolean = false,
    // 群头像用：缓存每个 conversation 的成员展示名（取首字符拼头像）。打开/创建群时填充。
    val conversationMemberNames: Map<String, List<String>> = emptyMap(),
    // 群聊 @agent 等待回复时的"正在输入"指示：convId → 正在思考的 agent 列表。
    val groupTypingAgents: Map<String, List<GroupTypingAgent>> = emptyMap(),
    // 聊天列表"左滑删除"引导 banner: 首次进入显示, 关闭后持久化不再打扰.
    val chatListHintDismissed: Boolean = false,
)

data class GroupTypingAgent(val sessionId: String, val name: String)

private data class QueuedOutboundMessage(
    val message: ChatMessage,
    val content: String,
    val attachments: List<PendingAttachment>,
    val requestedSessionId: String,
)

private fun sampleMessages(): List<ChatMessage> = listOf(
    ChatMessage("hello-momo", MessageAuthor.Momo, "你好呀，我是 Mobius。有什么可以帮你的吗？", "10:23"),
)

private fun defaultAssistantPersonalityOptions(): List<SessionPersonalityOption> = listOf(
    SessionPersonalityOption("balanced", "默认 Mobius", "友好、清楚、自然"),
    SessionPersonalityOption("serious", "严肃的 Mobius", "克制、准确、结构化"),
    SessionPersonalityOption("playful", "调皮的 Mobius", "轻快一点，关键操作仍严谨"),
    SessionPersonalityOption("proactive", "热情主动的 Mobius", "主动补全方案和下一步"),
    SessionPersonalityOption("gentle", "温和耐心的 Mobius", "解释充分，适合引导场景"),
    SessionPersonalityOption("concise", "干练的 Mobius", "结论先行，减少铺垫"),
)

/**
 * 按住说话的最小录音时长（毫秒）。低于该门槛视为误触/抖动，直接取消录音而非停止转写——
 * Android MediaRecorder、iOS AVAudioRecorder 在极短录音上 stop() 会失败并产出空文件，
 * 从而误报"录音内容为空，请重新录制"。详见各平台 *AudioRecorder.stop()。
 */
internal const val MIN_VOICE_INPUT_MS = 500L

/**
 * 是否属于"录音时间太短"：确实进入过录音（elapsedMs > 0）且小于 [MIN_VOICE_INPUT_MS]。
 * elapsedMs <= 0（未记录开始时间等异常）不判定为太短，交由平台层正常处理。
 */
internal fun isVoiceInputTooShort(elapsedMs: Long): Boolean = elapsedMs in 1 until MIN_VOICE_INPUT_MS

class MomoAppViewModel(
    private val storage: SecureStorage = createSecureStorage(),
    private val filePicker: FilePicker = createFilePicker(),
    private val speechPermissionController: SpeechPermissionController = createSpeechPermissionController(),
    private val speechRecognizer: SpeechRecognizer = createSpeechRecognizer(),
    private val systemTtsEngine: TtsEngine = createSystemTtsEngine(),
    private val pushProvider: PushProvider = createPushProvider(),
    private val buildBaseUrl: String = platformBuildBaseUrl(),
    private val serverAddressRepository: ServerAddressRepository = ServerAddressRepository(storage),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentBaseUrl = resolveMobiusBaseUrl(
        buildDefault = buildBaseUrl,
        savedValue = storage.getPreference(SERVER_BASE_URL_PREFERENCE),
    )
    private var api = createApi(currentBaseUrl)
    private var doubaoEngine: DoubaoTtsEngine = buildDoubaoEngine(api)
    private var ttsController: TtsController = buildTtsController()
    private var currentSpeakJob: Job? = null
    private var streamJob: Job? = null
    private var groupStreamJob: Job? = null
    private var contactsSearchJob: Job? = null
    private var toastJob: Job? = null
    private var snapshotPollJob: Job? = null
    // 群聊"覆盖层"：key=conversationId。SSE 收到的群消息(含小莫/分身回复)兜底缓存到这里 + 落盘,
    // 回到该群 / 重连时与 onHistory 合并显示——切群、重连都不丢回复。
    private val pendingRelayMessages = mutableMapOf<String, MutableList<ConversationMessage>>()
    private var voiceTimeoutJob: Job? = null
    private var voiceCommitJob: Job? = null
    private var sseHistoryMessages: List<ChatMessage> = emptyList()
    private var sseJsonlMessages: List<ChatMessage> = emptyList()
    private var pendingUserMessages: List<ChatMessage> = emptyList()
    private var queuedOutboundMessages: List<QueuedOutboundMessage> = emptyList()
    private var pendingMessageCounter = 0L
    private val pendingMessageLock = ReentrantLock()
    private var attachmentImagePreviews: Map<String, ByteArray> = emptyMap()
    private var snapshotLoadedForSessionId: String = ""
    private var awaitingAssistantReplySessionId: String = ""
    private var pendingVoiceCommit: Boolean = false
    private var pendingVoiceAudio: Boolean = false
    private var lastSpokenAssistantId: String = ""
    // 同一条 assistant 回复在「SSE 流」与「快照轮询」两条通路里 id 不一致(MobiusApi 解析 fallback 不同),
    // 单靠 lastSpokenAssistantId 会被快照刷新击穿 → 重复播报。这里额外记最近播报的文案做内容去重。
    private var lastSpokenAssistantText: String? = null
    // 进项目钻取 session 时置 true: 抑制一次自动播报(避免把历史最后一条 Momo 当新回复播报出去),
    // 只在用户发消息后的新回复才播报。
    private var suppressInitialAutoSpeech: Boolean = false
    // launchSpeak 入口对自动播报做"实际播报文本(cleaned)"去重: 覆盖 id 变/快照刷新/
    // 手动播后自动播同条 等所有重复场景(比 message.text 更准, 因 Selected 模式只播首句).
    // 手动播报不跳过, 但会更新此键以防紧接着的自动播报重播同一条.
    private var lastSpokenCleanedText: String = ""
    private var lastNotifiedAssistantId: String = ""
    private var pendingVoiceOnlyText: String? = null
    private var voiceInputStartMs: Long = 0L
    private var voiceTargetGroup: Boolean = false
    // 最近停留的 tab(聊天/通讯录/我), 子页面物理返回时回到它, 而非固定聊天 tab.
    private var lastTab: AppScreen = AppScreen.ChatList
    private var conversationClearCutoffs: Map<String, Long> = emptyMap()
    // 1v1 会话已删除(本地隐藏)的消息 id: sessionId -> ids. rebuildMessages 时过滤, 跨重连保持隐藏.
    private var deletedMessageIds: Map<String, Set<String>> = emptyMap()
    // 群聊已删除(本地隐藏)的消息 id: conversationId -> ids. SSE history/message 回灌时过滤.
    private var deletedGroupMessageIds: Map<String, Set<Long>> = emptyMap()
    // 群聊清空消息的时间截止点: conversationId -> cutoff. 早于该点的群消息不显示.
    private var groupClearCutoffs: Map<String, Long> = emptyMap()
    private val localJson = Json { ignoreUnknownKeys = true }
    private var streamUiFlushJob: Job? = null
    // 每条 jsonl_entry 都是后端透传的一条完整消息（非 token delta），
    // 这里缓存待渲染的完整消息，按 id 去重 upsert，避免多条消息被合并/丢弃。
    private var pendingStreamMessages: List<ChatMessage> = emptyList()
    private var pendingStreamProcessClear: Boolean = false
    private var lastStreamUiUpdateMillis: Long = 0L
    // ===== tab 列表磁盘快照缓存（秒开 + stale-while-revalidate）=====
    // 各 tab 上次成功拉取的时间戳(ms)：进入 tab 时若距上次拉取不足 TTL 且内存里已有数据,
    // 直接跳过网络请求(切 tab 秒切、无闪烁); 下拉刷新始终强制拉。
    private var conversationsLoadedAt: Long = 0L
    private var contactsLoadedAt: Long = 0L
    private var projectsLoadedAt: Long = 0L
    private var clonesLoadedAt: Long = 0L
    private val tabCacheJson = Json { ignoreUnknownKeys = true }
    // 快照缓存值带登录用户 id, 换账号后旧快照自动失效不会被误用。
    @Serializable
    private data class TabCacheEntry<T>(val userId: String, val payload: T)

    private inline fun <reified T> saveTabCacheValue(key: String, userId: String, payload: T) {
        runCatching {
            storage.savePreference(key, tabCacheJson.encodeToString(TabCacheEntry(userId, payload)))
        }
    }

    private inline fun <reified T> loadTabCacheValue(key: String, userId: String): T? {
        val raw = storage.getPreference(key)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val entry = tabCacheJson.decodeFromString<TabCacheEntry<T>>(raw)
            if (entry.userId == userId) entry.payload else null
        }.getOrNull()
    }

    private val _state = MutableStateFlow(
        UiState(
            serverBaseUrl = currentBaseUrl,
            serverEntries = serverAddressRepository.getAll(),
            selectedVoice = storage.getPreference(SECURE_PREF_SELECTED_VOICE) ?: TTS_SYSTEM_VOICE_ID,
            ttsPlaybackMode = TtsPlaybackMode.fromName(storage.getPreference(SECURE_PREF_TTS_PLAYBACK_MODE)),
            ttsEnabled = storage.getPreference(SECURE_PREF_TTS_ENABLED) != "0",
            chatListHintDismissed = storage.getPreference(CHAT_LIST_SWIPE_HINT_DISMISSED_KEY) == "1",
        projectsLiteMode = storage.getPreference(PROJECTS_LITE_MODE_KEY) == "1",
        ),
    )
    val state: StateFlow<UiState> = _state
    val chatState: StateFlow<ChatState> = _state
        .map { it.toChatState() }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.toChatState())
    val composerState: StateFlow<ComposerUiState> = _state
        .map { it.toComposerUiState() }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.toComposerUiState())
    val authState: StateFlow<AuthState> = _state
        .map { it.toAuthState() }
        .stateIn(scope, SharingStarted.Eagerly, _state.value.toAuthState())

    init {
        ttsController.setVoice(_state.value.selectedVoice)
        restoreThemePreferences()
        restorePushPreference()
        // 提前初始化聚合推送 SDK（Android=极光推送）。未配置 AppKey 时为空操作；幂等。
        // 越早 init 越快拿到 RegistrationID，登录后即可上报。
        runCatching { pushProvider.init() }
        refreshAuthConfig()
        restoreToken()
        loadRelayOverlay()
    }

    /** 从持久化偏好恢复「消息推送」总开关（默认开）。 */
    private fun restorePushPreference() {
        val saved = storage.getPreference(SECURE_PREF_PUSH_ENABLED)
        if (saved == "false") _state.update { it.copy(pushEnabled = false) }
    }

    private fun buildDoubaoEngine(source: MobiusApi): DoubaoTtsEngine =
        DoubaoTtsEngine(fetchAudio = { text, voice -> source.fetchTtsAudio(text, voice) })

    private fun buildTtsController(): TtsController = TtsController(
        systemEngine = systemTtsEngine,
        doubaoEngine = doubaoEngine,
        onEvent = { event -> handleTtsEvent(event) },
    )

    private fun handleTtsEvent(event: TtsEvent) {
        when (event) {
            is TtsEvent.Started -> Unit
            // fallback 是"静默降级"(系统语音↔豆包自动切换):引擎切换后用户仍能正常听到语音,
            // 无需用 toast 打扰;只有两个引擎都失败(Error)时才提示用户。
            is TtsEvent.Fallback -> Unit
            is TtsEvent.Error -> {
                _state.update { it.copy(ttsSpeakingMessageId = null, ttsFetchingMessageId = null) }
                if (event.message.isNotBlank()) showToast(event.message)
            }
        }
    }

    fun dispose() {
        streamJob?.cancel()
        groupStreamJob?.cancel()
        contactsSearchJob?.cancel()
        streamUiFlushJob?.cancel()
        toastJob?.cancel()
        snapshotPollJob?.cancel()
        voiceTimeoutJob?.cancel()
        voiceCommitJob?.cancel()
        stopSpeaking()
        speechRecognizer.dispose()
        ttsController.dispose()
        api.close()
        scope.cancel()
    }

    fun setUsername(value: String) = _state.update { it.copy(username = value, toast = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, toast = null) }

    fun setInput(value: String) = _state.update { it.copy(input = value) }

    fun setServerBaseUrl(value: String) = _state.update { it.copy(serverBaseUrl = value, toast = null) }

    fun saveServerBaseUrl() {
        if (applyServerBaseUrlInput()) showToast("服务器地址已保存")
    }

    // ===== 登录页「服务器地址列表」=====

    /** 从列表选中一个地址: 应用为当前服务器(与手输保存同路径, 含切服清态)。 */
    fun selectServerEntry(url: String) {
        // 直接写 state(MutableStateFlow.value 立即可见), 保证紧随的 applyServerBaseUrlInput
        // 读到的是新地址——setServerBaseUrl 走 _state.update 虽同为同步, 显式赋值意图更清晰。
        _state.update { it.copy(serverBaseUrl = url, toast = null) }
        if (applyServerBaseUrlInput()) serverAddressRepository.addOrTouch(url)
        _state.update { it.copy(serverEntries = serverAddressRepository.getAll()) }
    }

    fun removeServerEntry(url: String) {
        serverAddressRepository.remove(url)
        _state.update { it.copy(serverEntries = serverAddressRepository.getAll()) }
    }

    fun renameServerEntry(url: String, label: String?) {
        serverAddressRepository.rename(url, label)
        _state.update { it.copy(serverEntries = serverAddressRepository.getAll()) }
    }

    private fun applyServerBaseUrlInput(): Boolean {
        val normalized = runCatching { normalizeMobiusBaseUrl(state.value.serverBaseUrl) }
            .onFailure { showToast(it.message ?: "服务器地址无效") }
            .getOrNull() ?: return false
        if (normalized.isBlank()) {
            showToast("请输入 Mobius 服务器地址")
            return false
        }
        storage.savePreference(SERVER_BASE_URL_PREFERENCE, normalized)
        if (normalized != currentBaseUrl) {
            stopSpeaking()
            api.close()
            currentBaseUrl = normalized
            api = createApi(normalized)
            ttsController.dispose()
            doubaoEngine = buildDoubaoEngine(api)
            ttsController = buildTtsController()
            ttsController.setVoice(state.value.selectedVoice)
            storage.clear()
            storage.savePreference(SERVER_BASE_URL_PREFERENCE, normalized)
            clearStreamState()
        }
        _state.update {
            it.copy(
                serverBaseUrl = normalized,
                screen = AppScreen.Login,
                user = null,
                loading = false,
            )
        }
        refreshAuthConfig()
        return true
    }

    fun toggleComposerMode() {
        if (state.value.voiceRecording || state.value.voiceTranscribing) return
        _state.update { it.withToggledComposerMode() }
    }

    fun pickAttachments() {
        val remaining = 6 - state.value.attachments.size
        if (remaining <= 0) {
            showToast("最多添加 6 个附件")
            return
        }
        filePicker.pickFiles(
            maxFiles = remaining,
            onResult = { files -> files.take(remaining).forEach(::uploadPickedFile) },
            onError = { showToast(it) },
        )
    }

    fun removeAttachment(id: String) {
        _state.update { current -> current.copy(attachments = current.attachments.filterNot { it.id == id }) }
    }

    /**
     * Returns any image-thumbnail bytes cached for attachments mentioned in the message's
     * `附件：name` lines. Used to render previews under the user bubble.
     */
    fun imagePreviewsForMessage(message: ChatMessage): List<ByteArray> {
        if (message.author != MessageAuthor.User) return emptyList()
        if (attachmentImagePreviews.isEmpty()) return emptyList()
        val text = message.text
        val pattern = Regex("附件[：:]\\s*([^\\n]+)")
        return pattern.findAll(text)
            .mapNotNull { match -> attachmentImagePreviews[match.groupValues[1].trim()] }
            .toList()
    }

    private fun uploadPickedFile(file: PickedFile) {
        val id = "attachment-${file.name.hashCode()}-${nowShortTime()}-${state.value.attachments.size}"
        val previewBytes = if (
            file.mimeType.startsWith("image/") &&
            file.bytes.size <= MAX_INLINE_IMAGE_PREVIEW_BYTES
        ) {
            file.bytes
        } else {
            null
        }
        val pending = PendingAttachment(
            id = id,
            name = file.name.ifBlank { "未命名文件" },
            size = file.bytes.size.toLong(),
            mimeType = file.mimeType,
            status = AttachmentStatus.Uploading,
            previewBytes = previewBytes,
        )
        _state.update { current ->
            current.copy(attachments = (current.attachments + pending).take(6))
        }
        scope.launch {
            runCatching { api.uploadAttachment(file) }
                .onSuccess { uploaded ->
                    _state.update { current ->
                        current.copy(
                            attachments = current.attachments.map { item ->
                                if (item.id == id) {
                                    item.copy(
                                        name = uploaded.name,
                                        size = uploaded.size,
                                        status = AttachmentStatus.Done,
                                        path = uploaded.path,
                                        error = "",
                                    )
                                } else {
                                    item
                                }
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { current ->
                        current.copy(
                            attachments = current.attachments.map { item ->
                                if (item.id == id) {
                                    item.copy(status = AttachmentStatus.Error, error = error.message ?: "上传失败")
                                } else {
                                    item
                                }
                            },
                        )
                    }
                    showToast(error.message ?: "附件上传失败")
                }
        }
    }

    fun nextLoginStep() {
        if (!applyServerBaseUrlInput()) {
            return
        }
        val username = state.value.username.trim()
        if (username.isBlank()) {
            showToast("请输入用户名")
            return
        }
        if (state.value.passwordRequired) {
            _state.update { it.copy(loginStep = LoginStep.Password, toast = null) }
        } else {
            loginWith(username, "")
        }
    }

    fun backToUsername() = _state.update { it.copy(loginStep = LoginStep.Username, password = "", toast = null) }

    fun login() {
        if (!applyServerBaseUrlInput()) return
        val username = state.value.username.trim()
        val password = state.value.password
        if (username.isBlank() || (state.value.passwordRequired && password.isBlank())) {
            showToast("请输入用户名和密码")
            return
        }
        loginWith(username, password)
    }

    private fun loginWith(username: String, password: String) {
        scope.launch {
            _state.update { it.copy(loading = true, toast = null) }
            runCatching {
                val result = api.login(username, password)
                // 登录成功 → 自动把当前服务器地址记入「最近使用」列表(无需手动保存)。
                val touchedEntries = serverAddressRepository.addOrTouch(currentBaseUrl)
                _state.update {
                    it.copy(
                        user = result.user,
                        screen = AppScreen.ChatList,
                        loginStep = LoginStep.Password,
                        password = "",
                        loading = false,
                        serverEntries = touchedEntries,
                    )
                }
                ensureSpeechPermission()
                // 登录后请求通知权限(iOS 首次弹系统对话框; Android 已在 togglePush 里处理)。
                runCatching { NotificationGateway.requestPermission() }
                loadConversationClearCutoffs(result.user.id)
                loadDeletedState(result.user.id)
                loadWorkspaceAndClones()
                loadContacts(force = true)
                loadConversations(force = true)
                startGroupUnreadPolling()
                // 登录后若开启了推送(默认开)，启动前台 keepalive 服务——让进程在后台不被杀，
                // 30s 未读轮询与 assistant 回复通知才能在后台真正送达(状态栏后台推送)。
                if (state.value.pushEnabled) NotificationGateway.startForeground()
                // 聚合推送：把 JPush RegistrationID 上报给后端，让 App 被杀时也能收到推送。
                registerPushTokenIfNeeded()
            }.onFailure { e ->
                _state.update { it.copy(loading = false) }
                showToast(e.message ?: "登录失败")
            }
        }
    }

    // 关闭聊天列表"左滑删除"引导 banner, 持久化后不再显示.
    fun dismissChatListHint() {
        storage.savePreference(CHAT_LIST_SWIPE_HINT_DISMISSED_KEY, "1")
        _state.update { it.copy(chatListHintDismissed = true) }
    }

    fun navigate(screen: AppScreen) {
        if (screen == AppScreen.ChatList || screen == AppScreen.Contacts ||
            screen == AppScreen.Profile || screen == AppScreen.Projects
        ) {
            lastTab = screen
        }
        _state.update { it.copy(screen = screen, menuOpen = false, toast = null) }
        if (screen == AppScreen.Clones) refreshClones()
        if (screen == AppScreen.Contacts) {
            loadContacts()
        }
        if (screen == AppScreen.ChatList) {
            loadConversations()
            refreshClones()
        }
        if (screen == AppScreen.Profile) {
            refreshClones()
            loadContacts()
        }
        // 项目钻取: 进入各层时刷新数据(返回时也走 navigate, 保证数据新鲜)。
        if (screen == AppScreen.Projects) {
            loadProjects()
            if (state.value.projectsLiteMode && _state.value.liteSessions.isEmpty()) loadLiteSessions()
        }
        if (screen == AppScreen.ProjectIssues) {
            state.value.activeProject?.id?.let { loadProjectIssues(it) }
        }
        if (screen == AppScreen.IssueSessions) {
            state.value.activeIssue?.id?.let { loadIssueSessions(it) }
        }
        if (screen != AppScreen.GroupChat && screen != AppScreen.GroupInfo) {
            groupStreamJob?.cancel()
            _state.update { it.copy(groupConnected = false) }
        }
    }

    fun backToLastTab() = navigate(lastTab)

    fun toggleMenu() = _state.update { it.copy(menuOpen = !it.menuOpen) }

    // ===== 群聊 =====

    // tab 列表缓存 TTL: 60s 内重复切 tab 不再发请求, 配合磁盘快照实现"秒开"。
    private val tabCacheTtlMillis = 60_000L

    fun loadContacts(force: Boolean = false) {
        val userId = state.value.user?.id.orEmpty()
        // 冷启动先亮磁盘快照(若内存为空), 再决定是否要发网络请求。
        if (state.value.contacts.isEmpty() && userId.isNotBlank()) {
            loadTabCacheValue<List<UserDirectoryEntry>>(TAB_CACHE_CONTACTS, userId)?.let { cached ->
                _state.update { it.copy(contacts = cached, contactsLoaded = true) }
            }
        }
        if (!force && state.value.contacts.isNotEmpty() &&
            contactsLoadedAt > 0 && nowEpochMillis() - contactsLoadedAt < tabCacheTtlMillis
        ) return
        contactsSearchJob?.cancel()
        contactsSearchJob = scope.launch {
            _state.update { it.copy(contactsLoading = true) }
            runCatching {
                val result = api.listUsers(state.value.contactsSearch)
                contactsLoadedAt = nowEpochMillis()
                _state.update { it.copy(contacts = result.users, contactsLoading = false, contactsLoaded = true) }
                saveTabCacheValue(TAB_CACHE_CONTACTS, userId, result.users)
            }.onFailure { e ->
                _state.update { it.copy(contactsLoading = false, contactsLoaded = true) }
                showToast(e.message ?: "通讯录读取失败")
            }
        }
    }

    // 下拉刷新(通讯录): 独立 refreshing 标记, 保留旧列表, 最短转圈 800ms。
    fun refreshContacts() {
        if (_state.value.contactsRefreshing) return
        val minVisibleMs = 800L
        scope.launch {
            _state.update { it.copy(contactsRefreshing = true) }
            val startedAt = nowEpochMillis()
            runCatching {
                val result = api.listUsers(state.value.contactsSearch)
                contactsLoadedAt = nowEpochMillis()
                saveTabCacheValue(TAB_CACHE_CONTACTS, state.value.user?.id.orEmpty(), result.users)
                val elapsed = nowEpochMillis() - startedAt
                if (elapsed < minVisibleMs) delay(minVisibleMs - elapsed)
                _state.update { it.copy(contacts = result.users, contactsRefreshing = false) }
            }.onFailure { e ->
                _state.update { it.copy(contactsRefreshing = false) }
                showToast(e.message ?: "通讯录刷新失败")
            }
        }
    }

    fun onContactsSearchChange(value: String) {
        _state.update { it.copy(contactsSearch = value) }
        contactsSearchJob?.cancel()
        contactsSearchJob = scope.launch {
            delay(300L) // 防抖：用户连续输入时只在停顿后拉取
            runCatching {
                val result = api.listUsers(value)
                _state.update { it.copy(contacts = result.users) }
            }.onFailure { e ->
                showToast(e.message ?: "通讯录搜索失败")
            }
        }
    }

    fun loadConversations(force: Boolean = false) {
        val userId = state.value.user?.id.orEmpty()
        if (state.value.conversations.isEmpty() && userId.isNotBlank()) {
            loadTabCacheValue<List<ConversationSummary>>(TAB_CACHE_CONVERSATIONS, userId)?.let { cached ->
                _state.update { it.copy(conversations = cached, conversationsLoaded = true) }
            }
        }
        if (!force && state.value.conversations.isNotEmpty() &&
            conversationsLoadedAt > 0 && nowEpochMillis() - conversationsLoadedAt < tabCacheTtlMillis
        ) return
        scope.launch {
            runCatching {
                val list = api.listConversations()
                conversationsLoadedAt = nowEpochMillis()
                _state.update { it.copy(conversations = list, conversationsLoaded = true) }
                saveTabCacheValue(TAB_CACHE_CONVERSATIONS, userId, list)
                notifyNewGroupUnread(list)
                // 预取每个群的成员名用于群头像(取首字符拼网格)。后端列表接口不返回成员名,
                // 否则群头像首次进入列表时是兜底占位, 要点进去再返回才正常。并发拉取, 失败静默。
                prefetchConversationMemberNames(list)
            }.onFailure { e ->
                _state.update { it.copy(conversationsLoaded = true) }
                showToast(e.message ?: "群聊列表读取失败")
            }
        }
    }

    // 下拉刷新(聊天列表): 同时刷群聊列表 + 分身列表(该 tab 展示两段数据)。
    fun refreshChatList() {
        if (_state.value.conversationsRefreshing) return
        val minVisibleMs = 800L
        scope.launch {
            _state.update { it.copy(conversationsRefreshing = true) }
            val startedAt = nowEpochMillis()
            runCatching {
                val list = api.listConversations()
                conversationsLoadedAt = nowEpochMillis()
                saveTabCacheValue(TAB_CACHE_CONVERSATIONS, state.value.user?.id.orEmpty(), list)
                notifyNewGroupUnread(list)
                prefetchConversationMemberNames(list)
                val elapsed = nowEpochMillis() - startedAt
                if (elapsed < minVisibleMs) delay(minVisibleMs - elapsed)
                _state.update { it.copy(conversations = list, conversationsRefreshing = false) }
            }.onFailure { e ->
                _state.update { it.copy(conversationsRefreshing = false) }
                showToast(e.message ?: "聊天列表刷新失败")
            }
        }
        refreshClones(force = true, refreshingFlag = true)
    }

    private fun prefetchConversationMemberNames(list: List<ConversationSummary>) {
        val existing = state.value.conversationMemberNames
        val toFetch = list.map { it.id }.filter { it.isNotBlank() && it !in existing }
        if (toFetch.isEmpty()) return
        scope.launch {
            val fetched = toFetch.mapNotNull { id ->
                runCatching { id to api.getConversation(id).members.map { it.displayName } }.getOrNull()
            }.toMap()
            if (fetched.isNotEmpty()) {
                _state.update { it.copy(conversationMemberNames = it.conversationMemberNames + fetched) }
            }
        }
    }

    // 对比上次未读数, 新增未读且 app 在后台时发系统通知.
    private var lastUnreadByConv: Map<String, Int> = emptyMap()
    private var groupPollStarted = false
    private fun notifyNewGroupUnread(list: List<ConversationSummary>) {
        if (list.isEmpty()) return
        val newMap = list.associate { it.id to it.unread }
        val prev = lastUnreadByConv
        lastUnreadByConv = newMap
        if (prev.isEmpty()) return // 首次加载不算新
        list.forEach { conv ->
            val was = prev[conv.id] ?: 0
            if (conv.unread > was) {
                val body = conv.lastMessage?.takeIf { it.isNotBlank() } ?: "你有新的群聊消息"
                NotificationGateway.show(
                    title = conv.name.ifBlank { "群聊" },
                    body = body,
                    deepLink = conv.id.takeIf { it.isNotBlank() }?.let { "momo://group/$it" },
                )
            }
        }
    }

    // 登录后启动未读轮询(app 前台时每 30s 拉一次, 感知新消息发通知).
    fun startGroupUnreadPolling() {
        if (groupPollStarted) return
        groupPollStarted = true
        scope.launch {
            while (true) {
                delay(30_000L)
                if (state.value.user != null && !state.value.voiceRecording) {
                    runCatching { loadConversations() }
                }
            }
        }
    }

    fun openConversation(id: String) {
        if (id.isBlank()) return
        groupStreamJob?.cancel()
        // 切群：只取消当前群 SSE, 不清空覆盖层/typing——其它群缓存的回复与待回复状态保留,
        // 回到该群时由 onHistory 合并显示。
        // 点击进入聊天页才算「已读」: 立即清掉该会话的未读红点(乐观更新, 与服务端 getConversation
        // 的 markRead 一致)。否则列表红点要等下一次 30s 未读轮询才消失, 给人「没点进去就变了 /
        // 点进去红点不消失」的错乱感。
        _state.update {
            it.copy(
                conversations = it.conversations.map { c -> if (c.id == id) c.copy(unread = 0) else c },
                activeConversation = null,
                groupMessages = emptyList(),
                groupConnected = false,
                loading = true,
                screen = AppScreen.GroupChat,
                toast = null,
            )
        }
        scope.launch {
            runCatching {
                // 每次开群都从磁盘重新加载覆盖层(进程可能没被杀、init 不会再跑)，确保拿到最新
                // 的持久化回复。
                loadRelayOverlay()
                val detail = api.getConversation(id)
                _state.update {
                    // 先把覆盖层(历史 @agent 回复)亮出来，不依赖 SSE onHistory 是否及时到达——
                    // 即便群 SSE 连不上，之前缓存的 agent 回复也能立刻看到。onHistory 到了再合并真历史。
                    val pending = pendingRelayMessages[id].orEmpty()
                    it.copy(
                        activeConversation = detail,
                        loading = false,
                        groupMessages = pending,
                        conversationMemberNames = it.conversationMemberNames + (id to detail.members.map { m -> m.displayName }),
                    )
                }
                connectGroupStream(id)
                loadContacts()
                refreshClones()
            }.onFailure { e ->
                _state.update { it.copy(loading = false) }
                showToast(e.message ?: "打开群聊失败")
            }
        }
    }

    fun connectGroupStream(conversationId: String) {
        groupStreamJob?.cancel()
        groupStreamJob = scope.launch {
            var reconnectDelayMs = 1_000L
            while (
                state.value.screen != AppScreen.Login &&
                state.value.activeConversation?.conversation?.id == conversationId
            ) {
                var connected = false
                var emittedError = false
                try {
                    api.streamConversation(
                        conversationId = conversationId,
                        onConnected = {
                            connected = true
                            reconnectDelayMs = 1_000L
                            _state.update { it.copy(groupConnected = true) }
                        },
                        onHistory = { history ->
                            // 与中继覆盖层(pendingRelayMessages)合并：切群期间缓存的 agent 回复
                            // 在回到/重连该群时一并显示。用"按 id 且按内容"去重——后端已落库的回复
                            // 与覆盖层里的同内容回复只保留一条(history 优先)，既不丢也不重复。
                            _state.update {
                                val pending = pendingRelayMessages[conversationId].orEmpty()
                                it.copy(groupMessages = filterGroupMessages(conversationId, sortGroupMessages(mergeGroupHistory(history, pending))))
                            }
                        },
                        onMessage = { message ->
                            // 兜底持久化：把 SSE 收到的**所有**群消息(含小莫/分身回复)写进覆盖层+落盘。
                            // 不只限定 isAgent——后端给 agent 回复用的 sender_type 不一定是 "agent"，
                            // 按 isAgent 过滤会漏(曾导致重启后回复丢失)。这里全量缓存，重启后合并时
                            // 按 id+内容去重，不会和后端历史重复；agent 回复无论 sender_type 都能留。
                            if (message.content.isNotBlank()) {
                                val list = pendingRelayMessages.getOrPut(conversationId) { mutableListOf() }
                                if (list.none { it.content == message.content && it.senderId == message.senderId }) {
                                    list.add(message)
                                    persistRelayOverlay()
                                }
                            }
                            if (shouldShowGroupMessage(conversationId, message)) _state.update { current ->
                                // agent 回复到达 → 清掉该群"正在输入"指示(真实消息已顶上)。
                                val sorted = sortGroupMessages(appendGroupMessage(current.groupMessages, message))
                                if (message.isAgent) {
                                    current.copy(groupMessages = sorted, groupTypingAgents = current.groupTypingAgents - conversationId)
                                } else {
                                    current.copy(groupMessages = sorted)
                                }
                            }
                            // agent 回复到达 → 清了 typing → 尝试发送队列里下一条 @agent 消息(保证顺序)。
                            if (message.isAgent) {
                                drainGroupAgentQueue(conversationId)
                            }
                        },
                        onError = { message ->
                            emittedError = true
                            _state.update { it.copy(groupConnected = false) }
                            showToast(message)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emittedError = true
                    _state.update { it.copy(groupConnected = false) }
                    showToast(e.message ?: "群 SSE 连接中断")
                }

                if (state.value.screen == AppScreen.Login) break
                if (state.value.activeConversation?.conversation?.id != conversationId) break
                // 例行重连(看门狗超时/连接结束)静默处理，重连后 onHistory 会回灌含可能错过的
                // agent 回复；只有真实 HTTP/网络错误(emittedError)才弹 toast 提示。
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    // 群聊 @小莫/分身 串行队列: 同一时刻只让一个 @agent 请求在后端跑(临时分身),
    // 等其回复经 onMessage 回灌后再发下一条, 保证多条 @ 的回复顺序 = 发送顺序。
    private data class PendingGroupSend(
        val content: String,
        val mentions: List<ConversationMemberInput>,
        val optimisticDbId: Long,
    )
    private val groupAgentQueue = mutableMapOf<String, MutableList<PendingGroupSend>>()

    fun sendGroupMessage(content: String, mentions: List<ConversationMemberInput>) {
        val conversationId = state.value.activeConversation?.conversation?.id.orEmpty()
        if (conversationId.isBlank() || content.isBlank()) return
        val user = state.value.user
        // 乐观本地追加：避免等后端 SSE 回灌才有反馈；后端真消息进来时按 id 去重。
        val optimistic = ConversationMessage(
            id = -nowEpochMillis(),
            conversationId = conversationId,
            senderId = user?.id.orEmpty(),
            senderType = "user",
            senderName = user?.displayName?.ifBlank { user?.id }.orEmpty(),
            content = content,
            createdAt = nowIsoTime(),
        )
        val optimisticDbId = optimistic.hashCode().toLong() + Long.MAX_VALUE / 2
        _state.update {
            it.copy(groupMessages = appendGroupMessage(it.groupMessages, optimistic.copy(id = optimisticDbId)))
        }
        if (mentions.any { it.type == "agent" }) {
            // @小莫/分身: 入串行队列, 等前一个 agent 回复(onMessage 清 typing)后再发下一条, 保证回复顺序。
            groupAgentQueue.getOrPut(conversationId) { mutableListOf() }
                .add(PendingGroupSend(content, mentions, optimisticDbId))
            drainGroupAgentQueue(conversationId)
        } else {
            // 普通消息: 即时发送, 不排队(群聊里人与人的消息不受 @agent 队列影响)。
            postGroupMessageNow(conversationId, content, mentions, optimisticDbId, agentTargets = emptyList())
        }
    }

    private fun postGroupMessageNow(
        conversationId: String,
        content: String,
        mentions: List<ConversationMemberInput>,
        optimisticDbId: Long,
        agentTargets: List<Pair<String, String>>,
    ) {
        _state.update { it.copy(groupSending = true) }
        scope.launch {
            runCatching {
                api.postConversationMessage(conversationId, content, mentions)
            }.onSuccess { serverMessage ->
                _state.update { current ->
                    val withoutOptimistic = current.groupMessages.filterNot { m -> m.id == optimisticDbId }
                    val merged = if (serverMessage != null) appendGroupMessage(withoutOptimistic, serverMessage) else withoutOptimistic
                    current.copy(groupMessages = merged, groupSending = false)
                }
                // 标记被 @ 的 agent 为"正在输入"，待 SSE 推送该 agent 的回复(onMessage)时清除;
                // onMessage 收到 agent 回复后会 drainGroupAgentQueue 发送队列里下一条, 保证顺序。
                agentTargets.forEach { (sid, name) -> addTypingAgent(conversationId, sid, name) }
            }.onFailure { e ->
                // 发送失败也回灌到群聊(以系统错误消息形式), 而非仅 toast；用户能就地看到失败原因。
                val errMsg = ConversationMessage(
                    id = -nowEpochMillis(),
                    conversationId = conversationId,
                    senderId = "",
                    senderType = "system",
                    senderName = "系统",
                    content = "⚠️ 发送失败：${e.message ?: "消息发送失败"}",
                    createdAt = nowIsoTime(),
                )
                _state.update { current ->
                    current.copy(
                        groupSending = false,
                        groupMessages = appendGroupMessage(
                            current.groupMessages.filterNot { m -> m.id == optimisticDbId },
                            errMsg,
                        ),
                    )
                }
                showToast(e.message ?: "消息发送失败")
                // 这条(@agent 队列里的)失败了, 继续发下一条, 避免队列卡死。
                if (agentTargets.isNotEmpty()) drainGroupAgentQueue(conversationId)
            }
        }
    }

    // 取队首 @agent 消息发送; 若上一个 agent 还在回复(typing 非空)则等 onMessage 回复后再 drain。
    private fun drainGroupAgentQueue(conversationId: String) {
        if (state.value.groupTypingAgents[conversationId]?.isNotEmpty() == true) return
        val queue = groupAgentQueue[conversationId] ?: return
        val next = queue.removeFirstOrNull() ?: return
        val agentTargets = next.mentions
            .filter { it.type == "agent" }
            .mapNotNull { m ->
                val sid = m.agentSessionId?.takeIf { it.isNotBlank() } ?: m.id.takeIf { it.isNotBlank() }
                if (sid.isNullOrBlank()) null else sid to (m.displayName?.takeIf { it.isNotBlank() } ?: "小莫")
            }
            .distinctBy { it.first }
        postGroupMessageNow(conversationId, next.content, next.mentions, next.optimisticDbId, agentTargets)
    }

    private fun addTypingAgent(convId: String, sessionId: String, name: String) {
        _state.update {
            val list = it.groupTypingAgents[convId].orEmpty()
            if (list.any { a -> a.sessionId == sessionId }) return@update it
            it.copy(groupTypingAgents = it.groupTypingAgents + (convId to (list + GroupTypingAgent(sessionId, name))))
        }
        // 兜底：agent 长时间不回复(后端未回写 / 网络问题)时自动清除"正在输入", 避免一直转。
        scope.launch {
            delay(GROUP_TYPING_AGENT_TIMEOUT_MS)
            removeTypingAgent(convId, sessionId)
        }
    }

    private fun removeTypingAgent(convId: String, sessionId: String) {
        _state.update {
            val list = it.groupTypingAgents[convId].orEmpty().filterNot { a -> a.sessionId == sessionId }
            val map = if (list.isEmpty()) it.groupTypingAgents - convId else it.groupTypingAgents + (convId to list)
            it.copy(groupTypingAgents = map)
        }
    }

    // 覆盖层落盘：让群消息(含小莫/分身回复)在 App 重启后仍可见(客户端持久化兜底)。
    // 每群保留最近 40 条、整体最多 20 个群，避免无界增长。
    private val relayJson = Json { ignoreUnknownKeys = true }
    private val relayMapSerializer = MapSerializer(String.serializer(), ListSerializer(ConversationMessage.serializer()))

    private fun persistRelayOverlay() {
        runCatching {
            val capped = pendingRelayMessages.entries
                .sortedByDescending { it.value.lastOrNull()?.id ?: 0L }
                .take(20)
                .associate { (k, v) -> k to v.takeLast(40) }
            storage.savePreference(GROUP_RELAY_OVERLAY_KEY, relayJson.encodeToString(relayMapSerializer, capped))
        }
    }

    // 从磁盘加载覆盖层并**合并**进内存(不覆盖内存里已有的)，返回本次从磁盘读到的条数。
    // init 调一次；openConversation 每次开群也调一次——保证即便进程没被杀(init 不重跑)、
    // 或 init 时序问题，开群时也能拿到磁盘上最新的持久化回复。
    //
    // 防御要点(iOS TestFlight 0.1.0(9) 崩溃修复)：
    // NSUserDefaults 中可能残留旧版本写入的非法格式(或被 iOS 当 String 取出的二进制 blob)，
    // kotlinx-serialization 的 StringJsonLexer 在解析时会触发 native SEGV（地址 0x8，
    // 即 null + 8 字节偏移），runCatching 兜不住 native 信号。必须在调 decodeFromString 前
    // 做白名单校验，非法数据清掉避免下次启动重复触发崩溃。
    private fun loadRelayOverlay(): Int = runCatching {
        var loaded = 0
        val raw = storage.getPreference(GROUP_RELAY_OVERLAY_KEY) ?: return@runCatching loaded
        if (raw.isBlank()) return@runCatching loaded
        // 预校验：
        // ① 必须是 JSON object 格式({ ... })，长度 2..5MB
        // ② 内容必须是可打印文本(排除二进制 blob — iOS NSUserDefaults 可能把 Data 当 String 取出)
        val trimmed = raw.trim()
        val looksLikeJson = trimmed.length in 2..5_000_000 &&
                trimmed.firstOrNull() == '{' && trimmed.lastOrNull() == '}'
        val isPrintableText = looksLikeJson && trimmed.all { ch ->
            ch == '\n' || ch == '\r' || ch == '\t' || ch.code >= 0x20
        }
        if (!isPrintableText) {
            // 包含控制字符/二进制字节 → 大概率是 iOS 把 Data/二进制当 String 存入了 NSUserDefaults。
            // decodeFromString 会触发 native SEGV(runCatching 兜不住), 必须清掉。
            runCatching { storage.savePreference(GROUP_RELAY_OVERLAY_KEY, "") }
            return@runCatching loaded
        }
        // 格式合法 + 可打印文本 → decode 安全。schema 不兼容仍可能抛异常, try/catch 兜底。
        val decoded: Map<String, List<ConversationMessage>> = try {
            relayJson.decodeFromString(relayMapSerializer, raw)
        } catch (e: Throwable) {
            runCatching { storage.savePreference(GROUP_RELAY_OVERLAY_KEY, "") }
            return@runCatching loaded
        }
        decoded.forEach { (k, v) ->
            if (v.isNotEmpty()) {
                // 合并：内存已有 + 磁盘读到的，按 senderId+content 去重，避免互相覆盖丢数据。
                val merged = (pendingRelayMessages[k].orEmpty() + v)
                    .distinctBy { it.senderId to it.content }
                pendingRelayMessages[k] = merged.toMutableList()
                loaded += v.size
            }
        }
        loaded
    }.getOrDefault(0)

    fun createGroup() {
        val name = state.value.createGroupName.trim()
        if (name.isBlank()) {
            showToast("请填写群名称")
            return
        }
        val selectedUserIds = state.value.createGroupSelectedUserIds
        val selectedAgentSessions = state.value.createGroupSelectedAgentSessions
        val contacts = state.value.contacts
        val clones = state.value.clones
        val members = buildList {
            selectedUserIds.forEach { uid ->
                val display = contacts.firstOrNull { it.id == uid }?.displayName.orEmpty()
                add(ConversationMemberInput(type = "user", id = uid, displayName = display.ifBlank { null }))
            }
            selectedAgentSessions.forEach { sessionId ->
                val session = clones.firstOrNull { it.sessionId == sessionId }
                add(
                    ConversationMemberInput(
                        type = "agent",
                        id = sessionId,
                        displayName = session?.name?.takeIf { it.isNotBlank() },
                        agentSessionId = sessionId,
                    ),
                )
            }
        }
        if (members.isEmpty()) {
            showToast("请至少选择一个成员")
            return
        }
        scope.launch {
            _state.update { it.copy(loading = true, toast = null) }
            runCatching {
                val summary = api.createConversation(name, members)
                _state.update {
                    it.copy(
                        loading = false,
                        showCreateGroup = false,
                        createGroupName = "",
                        createGroupSelectedUserIds = emptySet(),
                        createGroupSelectedAgentSessions = emptySet(),
                    )
                }
                loadConversations(force = true)
                openConversation(summary.id)
            }.onFailure { e ->
                _state.update { it.copy(loading = false) }
                showToast(e.message ?: "建群失败")
            }
        }
    }

    fun openDirectChat(memberId: String) {
        scope.launch {
            try {
                val conv = api.openDirectChat(memberId)
                loadConversations()
                openConversation(conv.id)
            } catch (e: Exception) {
                showToast(e.message ?: "发起私聊失败")
            }
        }
    }

    fun addConversationMember(convId: String, member: ConversationMemberInput) {
        scope.launch {
            try {
                api.addConversationMember(convId, member)
                val detail = api.getConversation(convId)
                _state.update { it.copy(activeConversation = detail) }
            } catch (e: Exception) {
                showToast(e.message ?: "添加成员失败")
            }
        }
    }

    fun removeConversationMember(convId: String, type: String, memberId: String) {
        scope.launch {
            try {
                api.removeConversationMember(convId, type, memberId)
                val detail = api.getConversation(convId)
                _state.update { it.copy(activeConversation = detail) }
            } catch (e: Exception) {
                showToast(e.message ?: "移除成员失败")
            }
        }
    }

    fun leaveConversation(id: String) {
        val userId = state.value.user?.id.orEmpty()
        if (id.isBlank() || userId.isBlank()) return
        scope.launch {
            runCatching {
                api.removeConversationMember(id, "user", userId)
                groupStreamJob?.cancel()
                _state.update { it.copy(groupTypingAgents = emptyMap()) }
                _state.update {
                    it.copy(
                        activeConversation = null,
                        groupMessages = emptyList(),
                        groupConnected = false,
                        screen = AppScreen.Contacts,
                    )
                }
                loadConversations(force = true)
                showToast("已退出群聊")
            }.onFailure { e ->
                showToast(e.message ?: "退出群聊失败")
            }
        }
    }

    // 从聊天列表删除一条群聊: 后端无「删除会话」端点, 等价于退出群聊(把自己移出成员),
    // 并停留在当前列表页. 与 leaveConversation 的区别: 不跳到通讯录, 文案为「删除聊天」.
    fun deleteConversation(id: String) {
        val userId = state.value.user?.id.orEmpty()
        if (id.isBlank() || userId.isBlank()) return
        scope.launch {
            runCatching {
                // DELETE /api/conversations/:id: 群主=解散整群, 非群主=仅自己退出.
                // 不能用 removeConversationMember(self): 群主会被后端 400 拒绝(群主请先转让/解散),
                // 而旧客户端不检查状态码 → 假成功, 刷新后列表又把会话拉回.
                api.deleteConversation(id)
                if (state.value.activeConversation?.conversation?.id == id) {
                    groupStreamJob?.cancel()
                    _state.update {
                        it.copy(
                            groupTypingAgents = emptyMap(),
                            activeConversation = null,
                            groupMessages = emptyList(),
                            groupConnected = false,
                        )
                    }
                }
                _state.update { it.copy(conversations = it.conversations.filterNot { c -> c.id == id }) }
                loadConversations(force = true)
                showToast("已删除聊天")
            }.onFailure { e ->
                showToast(e.message ?: "删除聊天失败")
            }
        }
    }

    // 永久删除 1v1 小莫/分身会话: 后端 DELETE /api/sessions/:id 永久删除(含分身本身). 主小莫受保护不可删.
    // 注意: 聊天列表的「删除」现已改为 clearSessionMessages(只清消息记录, 保留分身); 本方法保留供未来
    // 「分身管理」等场景做永久删除使用, 当前无 UI 调用.
    fun deleteSession(sessionId: String) {
        if (sessionId.isBlank()) return
        val session = state.value.clones.firstOrNull { it.sessionId == sessionId }
        if (session != null && session.isMainAssistant()) {
            showToast("主小莫不可删除")
            return
        }
        scope.launch {
            runCatching {
                api.deleteSession(sessionId)
                if (state.value.activeSessionId == sessionId) {
                    streamJob?.cancel()
                    clearStreamState()
                    _state.update {
                        it.copy(
                            activeSessionId = "",
                            activeSessionTitle = "我的主小莫",
                            messages = sampleMessages(),
                            screen = AppScreen.ChatList,
                        )
                    }
                }
                _state.update { it.copy(clones = it.clones.filterNot { s -> s.sessionId == sessionId }) }
                refreshClones(force = true)
                showToast("已删除聊天")
            }.onFailure { e ->
                showToast(e.message ?: "删除聊天失败")
            }
        }
    }

    // 从聊天列表「删除」1v1 小莫/分身会话: 只清空消息记录, 保留分身本身(与 deleteSession 的永久删除不同).
    // - 恰好在看该会话: 复用 clearActiveConversation(设截止点 + 立即清空界面 + /compact 压缩上文).
    // - 非活动会话(常见, 从聊天列表触发): 仅设时间截止点, 下次打开时过滤掉旧消息; 不发 /compact 以免
    //   打扰可能在跑的后台分身. 主小莫也可清空(仅清消息, 不删主会话).
    fun clearSessionMessages(sessionId: String) {
        if (sessionId.isBlank()) return
        if (state.value.screen == AppScreen.Home && state.value.activeSessionId == sessionId) {
            clearActiveConversation()
            return
        }
        conversationClearCutoffs = conversationClearCutoffs + (sessionId to nowEpochMillis())
        saveConversationClearCutoffs()
        showToast("已清空聊天记录")
    }

    fun setShowCreateGroup(value: Boolean) {
        _state.update {
            it.copy(
                showCreateGroup = value,
                createGroupName = if (value) it.createGroupName else "",
                createGroupSelectedUserIds = if (value) it.createGroupSelectedUserIds else emptySet(),
                createGroupSelectedAgentSessions = if (value) it.createGroupSelectedAgentSessions else emptySet(),
            )
        }
    }

    fun setCreateGroupName(value: String) = _state.update { it.copy(createGroupName = value) }

    fun toggleCreateGroupUser(userId: String) {
        _state.update {
            val next = if (userId in it.createGroupSelectedUserIds) {
                it.createGroupSelectedUserIds - userId
            } else {
                it.createGroupSelectedUserIds + userId
            }
            it.copy(createGroupSelectedUserIds = next)
        }
    }

    fun toggleCreateGroupAgent(sessionId: String) {
        _state.update {
            val next = if (sessionId in it.createGroupSelectedAgentSessions) {
                it.createGroupSelectedAgentSessions - sessionId
            } else {
                it.createGroupSelectedAgentSessions + sessionId
            }
            it.copy(createGroupSelectedAgentSessions = next)
        }
    }

    fun clearActiveConversation() {
        val current = state.value
        val sessionId = current.activeSessionId
        stopSpeaking()
        if (current.sendingMessage || current.isStreaming) {
            streamJob?.cancel()
            snapshotPollJob?.cancel()
        }
        resetPendingStreamUi()
        pendingVoiceOnlyText = null
        awaitingAssistantReplySessionId = ""
        pendingUserMessages = emptyList()
        queuedOutboundMessages = emptyList()
        if (sessionId.isBlank()) {
            _state.update {
                it.copy(
                    messages = sampleMessages(),
                    sendingMessage = false,
                    isStreaming = false,
                    queuedUserMessages = emptyList(),
                    typing = false,
                    streamingProcess = null,
                    menuOpen = false,
                )
            }
            showToast("已清空本地对话（未连接服务器会话）")
            return
        }
        val cutoff = nowEpochMillis()
        conversationClearCutoffs = conversationClearCutoffs + (sessionId to cutoff)
        saveConversationClearCutoffs()
        rebuildMessages()
        _state.update {
            it.copy(
                menuOpen = false,
                sendingMessage = false,
                isStreaming = false,
                queuedUserMessages = emptyList(),
                typing = false,
                streamingProcess = null,
            )
        }
        showToast("已清空聊天记录")

        scope.launch {
            runCatching {
                sendCompactCommand(sessionId)
            }.onFailure { e ->
                showToast(e.message ?: "聊天记录已清空，上文压缩触发失败")
            }
        }
    }

    // 单条删除 1v1 消息: 后端无删除端点, 本地隐藏(按 id 过滤), 持久化跨重连保持隐藏.
    fun deleteMessage(messageId: String) {
        if (messageId.isBlank()) return
        val sessionId = state.value.activeSessionId
        if (sessionId.isBlank()) return
        if (state.value.ttsSpeakingMessageId == messageId) stopSpeaking()
        val current = deletedMessageIds[sessionId].orEmpty()
        deletedMessageIds = deletedMessageIds + (sessionId to (current + messageId))
        saveDeletedMessageIds()
        rebuildMessages()
        showToast("已删除消息")
    }

    // 单条删除群消息: 后端无删除端点, 本地隐藏(按 id 过滤), 持久化跨重连保持隐藏.
    fun deleteGroupMessage(messageId: Long) {
        val convId = state.value.activeConversation?.conversation?.id.orEmpty()
        if (convId.isBlank() || messageId == 0L) return
        val current = deletedGroupMessageIds[convId].orEmpty()
        deletedGroupMessageIds = deletedGroupMessageIds + (convId to (current + messageId))
        saveDeletedGroupMessageIds()
        _state.update { it.copy(groupMessages = it.groupMessages.filterNot { m -> m.id == messageId }) }
        showToast("已删除消息")
    }

    // 清空当前群聊消息: 设时间截止点, 早于该点的群消息不显示(SSE 回灌时过滤).
    fun clearActiveGroupMessages() {
        val convId = state.value.activeConversation?.conversation?.id.orEmpty()
        if (convId.isBlank()) {
            showToast("请先打开一个会话")
            return
        }
        groupClearCutoffs = groupClearCutoffs + (convId to nowEpochMillis())
        saveGroupClearCutoffs()
        _state.update { it.copy(groupMessages = emptyList()) }
        showToast("已清空群聊消息")
    }

    // 打开「创建分身」整页（取代旧弹窗）。预填名称/模型后导航到 CreateClone。
    fun openCloneEditor() {
        val nextNumber = (state.value.clones.count { it.name.startsWith("分身 Mobius") || it.name.startsWith("分身小莫") } + 1).coerceAtLeast(1)
        _state.update {
            it.copy(
                cloneTitle = "分身 Mobius #$nextNumber",
                cloneDescription = "",
                cloneModel = state.value.cloneModelOptions.firstOrNull()?.key ?: "codex",
                cloneSheetOpen = false,
            )
        }
        navigate(AppScreen.CreateClone)
    }

    // 打开「发起群聊」整页（取代旧弹窗）。重置选择后导航到 CreateGroup。
    fun openGroupEditor() {
        _state.update {
            it.copy(
                showCreateGroup = false,
                createGroupName = "",
                createGroupSelectedUserIds = emptySet(),
                createGroupSelectedAgentSessions = emptySet(),
            )
        }
        navigate(AppScreen.CreateGroup)
    }

    fun openProjectEditor() {
        _state.update {
            it.copy(createProjectName = "", createProjectDescription = "", creatingProject = false)
        }
        navigate(AppScreen.CreateProject)
    }

    fun closeCloneSheet() = _state.update { it.copy(cloneSheetOpen = false) }

    fun openPresetSheet() {
        _state.update {
            it.copy(
                presetSheetOpen = true,
                presetLoading = true,
                presetSaving = false,
                presetError = "",
                presetConfirmDelete = false,
                presetConfirmSessionName = "",
            )
        }
        scope.launch {
            runCatching {
                val payload = api.fetchAssistantPreset()
                val modelOptions = runCatching { api.sessionModelOptions() }
                    .getOrDefault(state.value.cloneModelOptions)
                    .filter { it.key.isNotBlank() }
                _state.update {
                    it.copy(
                        presetLoading = false,
                        presetDraft = payload.preset,
                        presetPersonalityOptions = payload.personalityOptions.ifEmpty { defaultAssistantPersonalityOptions() },
                        cloneModelOptions = modelOptions.ifEmpty { it.cloneModelOptions },
                        presetError = "",
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        presetLoading = false,
                        presetError = e.message ?: "读取 Mobius 预设失败",
                    )
                }
            }
        }
    }

    fun closePresetSheet() = _state.update {
        it.copy(
            presetSheetOpen = false,
            presetLoading = false,
            presetSaving = false,
            presetError = "",
            presetConfirmDelete = false,
            presetConfirmSessionName = "",
        )
    }

    fun setPresetPersonality(value: String) {
        _state.update { it.copy(presetDraft = it.presetDraft.copy(personality = value), presetError = "", presetConfirmDelete = false) }
    }

    fun setPresetModel(value: String) {
        _state.update { it.copy(presetDraft = it.presetDraft.copy(model = value), presetError = "", presetConfirmDelete = false) }
    }

    fun saveAssistantPreset(deleteCurrentSession: Boolean = false) {
        val draft = state.value.presetDraft
        scope.launch {
            _state.update { it.copy(presetSaving = true, presetError = "") }
            runCatching {
                api.saveAssistantPreset(draft, deleteCurrentSession)
            }.onSuccess { payload ->
                _state.update {
                    it.copy(
                        presetSheetOpen = false,
                        presetSaving = false,
                        presetConfirmDelete = false,
                        presetConfirmSessionName = "",
                        presetDraft = payload.preset,
                        presetPersonalityOptions = payload.personalityOptions.ifEmpty { defaultAssistantPersonalityOptions() },
                    )
                }
                loadWorkspaceAndClones()
                showToast("Mobius 预设已保存")
            }.onFailure { e ->
                if (e is AssistantPresetRequiresSessionDeleteException) {
                    _state.update {
                        it.copy(
                            presetSaving = false,
                            presetConfirmDelete = true,
                            presetConfirmSessionName = e.currentSession?.name?.takeIf { name -> name.isNotBlank() } ?: "当前 Mobius Session",
                            presetError = e.message ?: "保存 Mobius 预设需要删除当前 Mobius Session",
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            presetSaving = false,
                            presetError = e.message ?: "保存 Mobius 预设失败",
                        )
                    }
                }
            }
        }
    }

    fun setCreateSessionModel(value: String) = _state.update { it.copy(createSessionModel = value) }

    /** 勾选/取消一个 skill(排除法: 取消勾选进 excluded 集合)。 */
    fun toggleSessionSkill(id: String) = _state.update {
        it.copy(sessionExcludedSkills = if (id in it.sessionExcludedSkills) it.sessionExcludedSkills - id else it.sessionExcludedSkills + id)
    }

    /** 勾选/取消一个 memory。 */
    fun toggleSessionMemory(id: String) = _state.update {
        it.copy(sessionExcludedMemories = if (id in it.sessionExcludedMemories) it.sessionExcludedMemories - id else it.sessionExcludedMemories + id)
    }

    fun setCloneTitle(value: String) = _state.update { it.copy(cloneTitle = value) }

    fun setCloneDescription(value: String) = _state.update { it.copy(cloneDescription = value) }

    fun setCreateProjectName(value: String) = _state.update { it.copy(createProjectName = value) }
    fun setCreateProjectDescription(value: String) = _state.update { it.copy(createProjectDescription = value) }

    fun createProject() {
        val name = state.value.createProjectName.trim()
        if (name.isBlank()) {
            showToast("请填写项目名称")
            return
        }
        // 后端普通项目要求 bindPath(空路径报错); 用 user.work_dir + 随机 slug 自动生成(对齐网页端 randomProjectBindPath)。
        val workDir = state.value.user?.workDir?.trim()?.trimEnd('/').orEmpty()
        if (workDir.isBlank()) {
            showToast("未获取到工作目录，无法创建项目")
            return
        }
        val bindPath = "$workDir/proj-${nowEpochMillis().toString(36)}"
        if (state.value.creatingProject) return
        _state.update { it.copy(creatingProject = true) }
        scope.launch {
            runCatching { api.createProject(name, state.value.createProjectDescription.trim(), bindPath) }
                .onSuccess { project ->
                    _state.update { it.copy(creatingProject = false) }
                    loadProjects(force = true)
                    openProject(project) // 创建成功后直接进入该项目
                    showToast("项目已创建")
                }
                .onFailure { e ->
                    _state.update { it.copy(creatingProject = false) }
                    showToast(e.message ?: "创建项目失败")
                }
        }
    }

    // ===== 创建 Issue(项目下) =====
    fun openIssueEditor() {
        val options = state.value.cloneModelOptions
        // 默认优先 GLM(本服务器主模型), 找不到则取首个, 再不行 codex。
        val defaultModel = options.firstOrNull {
            it.key.contains("glm", ignoreCase = true) || it.label.contains("glm", ignoreCase = true)
        }?.key ?: options.firstOrNull()?.key ?: "codex"
        _state.update {
            it.copy(createIssueTitle = "", createIssueDescription = "", creatingIssue = false, createIssueModel = defaultModel)
        }
        navigate(AppScreen.CreateIssue)
    }

    fun setCreateIssueTitle(value: String) = _state.update { it.copy(createIssueTitle = value) }
    fun setCreateIssueDescription(value: String) = _state.update { it.copy(createIssueDescription = value) }
    fun setCreateIssueModel(value: String) = _state.update { it.copy(createIssueModel = value) }

    fun createIssue() {
        val projectId = state.value.activeProject?.id.orEmpty()
        val title = state.value.createIssueTitle.trim()
        if (projectId.isBlank()) { showToast("未选中项目"); return }
        if (title.isBlank()) { showToast("请填写 Issue 标题"); return }
        if (state.value.creatingIssue) return
        _state.update { it.copy(creatingIssue = true) }
        scope.launch {
            val issueResult = runCatching { api.createIssue(projectId, title, state.value.createIssueDescription.trim()) }
            if (issueResult.isFailure) {
                _state.update { it.copy(creatingIssue = false) }
                showToast(issueResult.exceptionOrNull()?.message ?: "创建 Issue 失败")
                return@launch
            }
            val issue = issueResult.getOrThrow()
            val desc = state.value.createIssueDescription.trim()
            val model = state.value.createIssueModel.ifBlank { state.value.cloneModelOptions.firstOrNull()?.key ?: "codex" }
            // 对齐网页端 createFirstSession + 立即执行: 自动建首个会话(用所选模型, 默认 GLM)。
            val session = runCatching { api.createClone(issue.id, title, desc, model) }.getOrNull()
            _state.update { it.copy(creatingIssue = false) }
            loadProjectIssues(projectId)
            if (session != null) {
                // 预载会话列表供返回 IssueSessions 时展示。
                loadIssueSessions(issue.id)
                // 不走 openSession: 它会异步拉空快照 applySnapshot, 即便 awaiting 已置, 仍可能与发送
                // 置的 typing 产生竞态导致等待气泡一闪而过。这里直接置"项目钻取"聊天态(不拉快照),
                // 由 sendTextMessage→sendQueuedMessage 负责发送 + connectStream + typing, 与平时聊天一致。
                // 保留出站队列(上一会话可能还有排队消息未发完), 新会话首条消息紧随其后。
                clearStreamState(keepQueuedMessages = true)
                suppressInitialAutoSpeech = true
                _state.update {
                    it.copy(
                        activeIssue = issue,
                        screen = AppScreen.Home,
                        menuOpen = false,
                        chatReturnTarget = AppScreen.IssueSessions,
                        activeSessionId = session.sessionId,
                        activeSessionTitle = session.name.ifBlank { "Mobius 会话" },
                        activeSessionModelLabel = session.modelLabel,
                        messages = emptyList(),
                        loading = false,
                        toast = null,
                    )
                }
                markAwaitingAssistantReply(session.sessionId)
                val startContent = listOf(title, desc).filter { it.isNotBlank() }.joinToString("\n\n")
                if (startContent.isNotBlank()) sendTextMessage(startContent)
                showToast("Issue 已创建，已开始执行")
            } else {
                openIssue(issue)
                showToast("Issue 已创建，但自动建会话失败")
            }
        }
    }

    // ===== 创建 Session(复用 createClone: POST /api/issues/:id/sessions) =====
    fun openSessionEditor() {
        _state.update {
            it.copy(
                createSessionTitle = "",
                createSessionDescription = "",
                creatingSession = false,
                createSessionModel = it.cloneModelOptions.firstOrNull()?.key.orEmpty(),
                sessionSkills = emptyList(),
                sessionMemories = emptyList(),
                sessionExcludedSkills = emptySet(),
                sessionExcludedMemories = emptySet(),
            )
        }
        navigate(AppScreen.CreateSession)
        // 异步加载当前项目的 skill/memory 列表(失败静默, 页面显示"暂无")。
        val projectId = state.value.activeProject?.id.orEmpty()
        if (projectId.isBlank()) return
        scope.launch {
            _state.update { it.copy(contextLoading = true) }
            val skills = runCatching { api.listProjectSkills(projectId) }.getOrDefault(emptyList())
            val memories = runCatching { api.listProjectMemories(projectId) }.getOrDefault(emptyList())
            _state.update { it.copy(sessionSkills = skills, sessionMemories = memories, contextLoading = false) }
        }
    }

    fun setCreateSessionTitle(value: String) = _state.update { it.copy(createSessionTitle = value) }
    fun setCreateSessionDescription(value: String) = _state.update { it.copy(createSessionDescription = value) }

    fun createSession() {
        val issueId = state.value.activeIssue?.id.orEmpty()
        val title = state.value.createSessionTitle.trim()
        if (issueId.isBlank()) { showToast("未选中 Issue"); return }
        if (title.isBlank()) { showToast("请填写会话名称"); return }
        if (state.value.creatingSession) return
        _state.update { it.copy(creatingSession = true) }
        scope.launch {
            val current = state.value
            val model = current.createSessionModel.takeIf { it.isNotBlank() } ?: "codex"
            runCatching {
                api.createClone(
                    issueId = issueId,
                    title = title,
                    description = current.createSessionDescription.trim(),
                    model = model,
                    excludedSkillIds = current.sessionExcludedSkills.toList(),
                    excludedMemoryIds = current.sessionExcludedMemories.toList(),
                )
            }
                .onSuccess {
                    _state.update { it.copy(creatingSession = false) }
                    loadIssueSessions(issueId)
                    navigate(AppScreen.IssueSessions)
                    showToast("会话已创建")
                }
                .onFailure { e ->
                    _state.update { it.copy(creatingSession = false) }
                    showToast(e.message ?: "创建会话失败")
                }
        }
    }

    fun setCloneModel(value: String) = _state.update { it.copy(cloneModel = value) }

    fun sendHomeMessage() {
        sendTextMessage(state.value.input.trim())
    }

    private fun sendTextMessage(content: String) {
        val current = state.value
        if (current.attachments.any { it.status == AttachmentStatus.Uploading }) {
            showToast("附件还在上传，请稍候")
            return
        }
        val completedAttachments = current.attachments.filter { it.status == AttachmentStatus.Done && it.path.isNotBlank() }
        if (content.isBlank() && completedAttachments.isEmpty()) return
        val newImagePreviews = completedAttachments
            .filter { it.mimeType.startsWith("image/") && it.previewBytes != null }
            .associate { it.name to it.previewBytes!! }
        if (newImagePreviews.isNotEmpty()) {
            attachmentImagePreviews = attachmentImagePreviews + newImagePreviews
        }
        val visibleContent = content.ifBlank { "请查看我上传的附件。" }
        val attachmentLines = completedAttachments.joinToString(separator = "\n", prefix = if (completedAttachments.isEmpty()) "" else "\n") {
            "附件：${it.name}"
        }
        val nowMillis = nowEpochMillis()
        val counter = pendingMessageLock.withLock { ++pendingMessageCounter }
        val userMessage = ChatMessage(
            "user-$nowMillis-$counter-${(visibleContent + attachmentLines).hashCode().toULong().toString(16)}",
            MessageAuthor.User,
            visibleContent + attachmentLines,
            nowShortTime(),
            createdAtMillis = nowMillis,
        )
        _state.update {
            it.copy(
                input = "",
                attachments = emptyList(),
                voiceTranscript = "",
                queuedUserMessages = appendMessage(it.queuedUserMessages, userMessage),
            )
        }
        pendingUserMessages = appendMessage(pendingUserMessages, userMessage)
        rebuildMessages()
        pushQueueImmediate(
            QueuedOutboundMessage(
                message = userMessage,
                content = visibleContent,
                attachments = completedAttachments,
                requestedSessionId = current.activeSessionId,
            ),
        )
    }

    private fun pushQueueImmediate(message: QueuedOutboundMessage) {
        queuedOutboundMessages = queuedOutboundMessages + message
        drainMessageQueueIfNeeded()
    }

    private fun drainMessageQueueIfNeeded() {
        val current = state.value
        // 只用 sendingMessage 串行化 HTTP 请求; 不再等上一条的轮次结束(isStreaming)。
        // 旧逻辑"等回复再发下一条"在快发多条时形成互锁: B 排队等 A 轮次结束, 而轮次结束的
        // typing=false 又被"最后一条 user(B, 尚未发出)无回复"顶住 → 队列永不排空;
        // 快照轮询(2 分钟)超时后 B 彻底滞留, 再导航/重进被 clearStreamState 清空 → "消息丢失"。
        // 服务端 noPauseCurrentAndQueueQueryAtSession 本就把新 prompt 排进 agent 队列
        // (与网页端一致): 立即发出, 顺序由服务端保证。
        if (current.sendingMessage) return
        val next = queuedOutboundMessages.firstOrNull() ?: return
        queuedOutboundMessages = queuedOutboundMessages.drop(1)
        _state.update {
            it.copy(
                queuedUserMessages = it.queuedUserMessages.filterNot { queued -> queued.id == next.message.id },
                sendingMessage = true,
                typing = true,
                isStreaming = true,
                streamingProcess = "Mobius 正在思考…",
            )
        }
        scope.launch {
            delay(15_000L)
            if (state.value.typing && state.value.streamingProcess == "Mobius 正在思考…") {
                _state.update { it.copy(streamingProcess = "Mobius 还在思考，请稍候…") }
            }
        }
        scope.launch {
            runCatching {
                sendQueuedMessage(next)
                _state.update { it.copy(sendingMessage = false) }
                // 发送成功即尝试发下一条(不等上一条的轮次/回复结束), 消除"多条消息排队丢消息"。
                drainMessageQueueIfNeeded()
            }.onFailure { e ->
                handleQueuedSendFailure(next, e)
            }
        }
    }

    private suspend fun sendQueuedMessage(queued: QueuedOutboundMessage) {
        val current = state.value
        val activeSession = current.clones.firstOrNull { it.sessionId == queued.requestedSessionId }
        val completedAttachments = queued.attachments
        // "具体 session"发送通道: 分身(非主小莫) 或 从项目钻取进来的 session(经 IssueSessions 进聊天页,
        // 该 session 不在 clones 列表)。两者都用 sendSessionMessage(sessionId) 在该 session 内执行任务,
        // 并停留在当前 session 页面——不切到主小莫、不更新 workspace(避免"发消息跳到主小莫页")。
        // 判定"具体 session"通道看 session 本身, 而非导航来源:
        // ① 分身(非主小莫) → 自身 sessionId;
        // ② 项目钻取(IssueSessions 进来) → requestedSessionId;
        // ③ **当前请求的 session 不是主小莫** — 覆盖精简模式从项目页直达 session、或任何
        //    clones/IssueSessions 之外打开的非主会话: 若按主小莫通道发送, sendAssistantMessage
        //    会把 activeSessionId 切回主小莫 → "发消息跳到主小莫聊天页"。
        val requestedIsMain = activeSession?.isMainAssistant() ?: false
        val sessionChannelId: String? = when {
            activeSession != null && !requestedIsMain -> activeSession.sessionId
            current.chatReturnTarget == AppScreen.IssueSessions && queued.requestedSessionId.isNotBlank() -> queued.requestedSessionId
            activeSession == null && queued.requestedSessionId.isNotBlank() &&
                queued.requestedSessionId != current.clones.firstOrNull { c -> c.isMainAssistant() }?.sessionId ->
                queued.requestedSessionId
            else -> null
        }
        markAwaitingAssistantReply(queued.requestedSessionId)
        if (sessionChannelId != null) {
            // 具体 session(分身非主小莫 / 项目钻取 session): 无附件或有附件都走 sendSessionMessage,
            // 在该 session 内执行任务并停留当前页(不切到主小莫)。带附件(图片等)由该 session 的 agent 处理。
            markAwaitingAssistantReply(sessionChannelId)
            // 发送结果校验: HTTP 非 2xx 会抛(上层 toast); 这里额外确认后端接受了 —
            // 部分"显示成功实际没发"是请求静默失败(弱网/代理掐断), 必须显式暴露。
            val sendResult = runCatching {
                api.sendSessionMessage(
                    sessionId = sessionChannelId,
                    content = queued.content,
                    attachments = completedAttachments.map {
                        AssistantPromptAttachment(
                            path = it.path,
                            name = it.name,
                            size = it.size,
                            type = if (it.mimeType.startsWith("image/")) "image" else "file",
                            mimeType = it.mimeType,
                        )
                    },
                )
            }
            if (sendResult.isFailure) {
                // 发送失败: 移除本地 pending 气泡并明确报错(不再假装成功)。
                pendingUserMessages = pendingUserMessages.filterNot { it.id == queued.message.id }
                _state.update {
                    it.copy(
                        queuedUserMessages = it.queuedUserMessages.filterNot { q -> q.id == queued.message.id },
                    )
                }
                rebuildMessages()
                throw sendResult.exceptionOrNull() ?: IllegalStateException("消息发送失败")
            }
            connectStream(sessionChannelId)
            startSnapshotPolling(sessionChannelId)
        } else {
            // 主小莫通道(sendAssistantMessage, 按 workspace 路由并更新 project/issue)。
            val result = api.sendAssistantMessage(
                content = queued.content,
                attachments = completedAttachments.map {
                    AssistantPromptAttachment(
                        path = it.path,
                        name = it.name,
                        size = it.size,
                        type = if (it.mimeType.startsWith("image/")) "image" else "file",
                        mimeType = it.mimeType,
                    )
                },
            )
            val sessionId = result.resolvedSessionId()
            if (sessionId.isNotBlank()) {
                markAwaitingAssistantReply(sessionId)
                _state.update {
                    it.copy(
                        activeSessionId = sessionId,
                        activeSessionTitle = result.session?.name?.takeIf { name -> name.isNotBlank() } ?: "我的主 Mobius",
                        workspace = AssistantWorkspace(result.project, result.issue),
                    )
                }
                connectStream(sessionId)
                startSnapshotPolling(sessionId)
            } else {
                error("服务端未返回可用的 Session ID")
            }
        }
    }

    private fun handleQueuedSendFailure(queued: QueuedOutboundMessage, error: Throwable) {
        resetPendingStreamUi()
        pendingVoiceOnlyText = null
        clearAwaitingAssistantReply()
        pendingUserMessages = pendingUserMessages.filterNot { it.id == queued.message.id }
        val systemMessage = ChatMessage(
            "send-error-${nowShortTime()}",
            MessageAuthor.System,
            "消息发送失败，请稍后重试。",
            nowShortTime(),
            createdAtMillis = nowEpochMillis(),
        )
        _state.update {
            it.copy(
                typing = false,
                sendingMessage = false,
                isStreaming = false,
                queuedUserMessages = it.queuedUserMessages.filterNot { pending -> pending.id == queued.message.id },
                messages = appendMessage(
                    it.messages.filterNot { message -> message.id == queued.message.id },
                    systemMessage,
                ),
            )
        }
        showToast(error.message ?: "消息发送失败")
        drainMessageQueueIfNeeded()
    }

    // ===== 项目钻取(项目 -> Issue -> Session -> 聊天) =====

    fun loadProjects(force: Boolean = false) {
        val userId = state.value.user?.id.orEmpty()
        if (state.value.projects.isEmpty() && userId.isNotBlank()) {
            loadTabCacheValue<List<Project>>(TAB_CACHE_PROJECTS, userId)?.let { cached ->
                _state.update { it.copy(projects = cached, projectsLoading = false) }
            }
        }
        if (!force && state.value.projects.isNotEmpty() &&
            projectsLoadedAt > 0 && nowEpochMillis() - projectsLoadedAt < tabCacheTtlMillis
        ) return
        scope.launch {
            _state.update { it.copy(projectsLoading = true) }
            runCatching {
                val list = api.projects()
                projectsLoadedAt = nowEpochMillis()
                saveTabCacheValue(TAB_CACHE_PROJECTS, userId, list)
                _state.update { it.copy(projects = list, projectsLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(projectsLoading = false) }
                showToast(e.message ?: "项目列表读取失败")
            }
        }
    }

    // 下拉刷新: 复用 projects() 拉取, 但用独立的 projectsRefreshing 标记, 刷新期间保留旧列表。
    fun refreshProjects() {
        if (_state.value.projectsRefreshing) return
        val minVisibleMs = 800L // 转圈最短展示时长, 网络太快时避免"一闪而过"
        scope.launch {
            _state.update { it.copy(projectsRefreshing = true) }
            val startedAt = nowEpochMillis()
            runCatching {
                val list = api.projects()
                projectsLoadedAt = nowEpochMillis()
                saveTabCacheValue(TAB_CACHE_PROJECTS, state.value.user?.id.orEmpty(), list)
                // 保证转圈至少展示 minVisibleMs: 数据秒回也补足到该时长再收。
                val elapsed = nowEpochMillis() - startedAt
                if (elapsed < minVisibleMs) delay(minVisibleMs - elapsed)
                _state.update { it.copy(projects = list, projectsRefreshing = false) }
            }.onFailure { e ->
                _state.update { it.copy(projectsRefreshing = false) }
                showToast(e.message ?: "项目列表刷新失败")
            }
        }
    }

    /** 切换经典/精简模式(持久化)。开启时聚合所有项目的 session。 */
    fun toggleProjectsLiteMode() {
        val next = !state.value.projectsLiteMode
        storage.savePreference(PROJECTS_LITE_MODE_KEY, if (next) "1" else "0")
        _state.update { it.copy(projectsLiteMode = next) }
        if (next) loadLiteSessions()
    }

    /** 精简模式: 拉所有项目 → 各项目 Issue → 各 Issue 的 session, 聚合成平铺列表。
     * 性能: 项目间并发(coroutine 限 6), 单项目内 issue 也并发; 先亮磁盘缓存立即渲染再后台刷新。 */
    fun loadLiteSessions() {
        val userId = state.value.user?.id.orEmpty()
        // 磁盘缓存秒开(冷启动/重进 tab): 上次聚合结果直接先显示。
        if (state.value.liteSessions.isEmpty() && userId.isNotBlank()) {
            loadTabCacheValue<List<LiteSessionEntry>>(TAB_CACHE_LITE_SESSIONS, userId)?.let { cached ->
                _state.update { it.copy(liteSessions = cached) }
            }
        }
        scope.launch {
            _state.update { it.copy(liteSessionsLoading = state.value.liteSessions.isEmpty()) }
            val projects = state.value.projects.ifEmpty {
                runCatching { api.projects() }.getOrDefault(emptyList()).also {
                    _state.update { s -> s.copy(projects = it) }
                }
            }
            // 并发拉取 + 全链路容错: 单项目/单 issue 失败不影响整体; iOS Darwin 引擎偶发
            // 连接失败时这里保证已有数据不丢、loading 一定收尾。
            val result = runCatching {
                kotlinx.coroutines.coroutineScope {
                    projects.filter { it.id.isNotBlank() }.map { project ->
                        async {
                            val issues = runCatching { api.listIssues(project.id) }.getOrDefault(emptyList())
                            issues.filter { it.id.isNotBlank() }.flatMap { issue ->
                                runCatching { api.issueSessions(issue.id) }.getOrDefault(emptyList())
                                    .filter { it.sessionId.isNotBlank() }
                                    .map { s -> LiteSessionEntry(s, project.name, issue.title) }
                            }
                        }
                    }.awaitAll().flatten()
                }
            }.getOrDefault(emptyList())
            _state.update { it.copy(liteSessions = result, liteSessionsLoading = false) }
            if (userId.isNotBlank()) saveTabCacheValue(TAB_CACHE_LITE_SESSIONS, userId, result)
        }
    }

    fun setProjectsTab(tab: ProjectListTab) {
        _state.update { it.copy(projectsSelectedTab = tab) }
    }

    fun setLiteTab(tab: LiteListTab) {
        _state.update { it.copy(liteSelectedTab = tab) }
    }

    fun setProjectsSearchText(value: String) {
        _state.update { it.copy(projectsSearchText = value) }
    }

    fun setProjectsActiveWindow(days: Long) {
        _state.update { it.copy(projectsActiveWindowDays = days) }
    }

    fun openProject(project: Project) {
        if (project.id.isBlank()) return
        _state.update { it.copy(activeProject = project, projectIssues = emptyList()) }
        navigate(AppScreen.ProjectIssues)
    }

    // 进入用户创建的扩展应用(App 内 WebView): 加载 <baseUrl>/extension/<extensionName>/,
    // 并把当前登录 token 带到 WebView 注入 localStorage['cc-token']，使扩展前端 extCall 可鉴权。
    fun openExtension(project: Project) {
        val name = project.extensionName?.takeIf { it.isNotBlank() }
        if (name.isNullOrBlank()) {
            showToast("该扩展缺少 extension_name")
            return
        }
        val token = storage.getToken()
        if (token.isNullOrBlank()) {
            showToast("未登录")
            return
        }
        val url = currentBaseUrl.trimEnd('/') + "/extension/" + name + "/"
        _state.update {
            it.copy(
                activeExtensionTitle = project.name.ifBlank { name },
                activeExtensionUrl = url,
                activeExtensionToken = token,
                projectsSelectedTab = ProjectListTab.Extension,
            )
        }
        navigate(AppScreen.ExtensionWeb)
    }

    fun loadProjectIssues(projectId: String) {
        if (projectId.isBlank()) return
        scope.launch {
            _state.update { it.copy(projectIssuesLoading = true) }
            runCatching {
                val list = api.listIssues(projectId)
                _state.update { it.copy(projectIssues = list, projectIssuesLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(projectIssuesLoading = false) }
                showToast(e.message ?: "Issue 列表读取失败")
            }
        }
    }

    fun openIssue(issue: Issue) {
        if (issue.id.isBlank()) return
        _state.update { it.copy(activeIssue = issue, issueSessionsList = emptyList()) }
        navigate(AppScreen.IssueSessions)
    }

    fun loadIssueSessions(issueId: String) {
        if (issueId.isBlank()) return
        scope.launch {
            _state.update { it.copy(issueSessionsLoading = true) }
            runCatching {
                val list = api.issueSessions(issueId)
                _state.update { it.copy(issueSessionsList = list, issueSessionsLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(issueSessionsLoading = false) }
                showToast(e.message ?: "会话列表读取失败")
            }
        }
    }

    fun refreshClones(force: Boolean = false, refreshingFlag: Boolean = false) {
        val userId = state.value.user?.id.orEmpty()
        if (state.value.clones.isEmpty() && userId.isNotBlank()) {
            loadTabCacheValue<List<Session>>(TAB_CACHE_CLONES, userId)?.let { cached ->
                _state.update { it.copy(clones = cached, clonesLoaded = true) }
            }
        }
        if (!force && state.value.clones.isNotEmpty() &&
            clonesLoadedAt > 0 && nowEpochMillis() - clonesLoadedAt < tabCacheTtlMillis
        ) return
        scope.launch {
            runCatching {
                val workspace = state.value.workspace ?: api.assistantWorkspace()
                val snapshots = api.assistantSnapshots()
                val sessions = snapshots.map { it.session }.filter { it.sessionId.isNotBlank() }
                clonesLoadedAt = nowEpochMillis()
                saveTabCacheValue(TAB_CACHE_CLONES, userId, sessions)
                _state.update {
                    it.copy(
                        workspace = workspace,
                        clones = sessions,
                        clonesLoaded = true,
                        clonesRefreshing = if (refreshingFlag) false else it.clonesRefreshing,
                    )
                }
            }.onFailure { e ->
                if (refreshingFlag) _state.update { it.copy(clonesRefreshing = false) }
                showToast(e.message ?: "分身列表读取失败")
            }
        }
    }

    // 下拉刷新(「我」页): 刷分身列表(该页主体数据)。
    fun refreshProfile() {
        if (_state.value.clonesRefreshing) return
        val minVisibleMs = 800L
        scope.launch {
            _state.update { it.copy(clonesRefreshing = true) }
            val startedAt = nowEpochMillis()
            runCatching {
                val workspace = state.value.workspace ?: api.assistantWorkspace()
                val snapshots = api.assistantSnapshots()
                val sessions = snapshots.map { it.session }.filter { it.sessionId.isNotBlank() }
                clonesLoadedAt = nowEpochMillis()
                saveTabCacheValue(TAB_CACHE_CLONES, state.value.user?.id.orEmpty(), sessions)
                val elapsed = nowEpochMillis() - startedAt
                if (elapsed < minVisibleMs) delay(minVisibleMs - elapsed)
                _state.update {
                    it.copy(workspace = workspace, clones = sessions, clonesRefreshing = false)
                }
            }.onFailure { e ->
                _state.update { it.copy(clonesRefreshing = false) }
                showToast(e.message ?: "分身列表刷新失败")
            }
        }
    }

    // ===== 通知 deepLink: 点击通知进入对应聊天 =====
    // momo://group/<conversationId> → 群聊; momo://chat/<sessionId> → 1v1 会话。
    private var pendingDeepLink: String? = null

    /** 由平台(Activity/通知点击)调用: 按 deepLink 进入对应聊天; 未登录则暂存, 登录后处理。 */
    fun handleDeepLink(deepLink: String?) {
        if (deepLink.isNullOrBlank()) return
        if (state.value.screen == AppScreen.Login) {
            pendingDeepLink = deepLink
        } else {
            applyDeepLink(deepLink)
        }
    }

    private fun applyDeepLink(deepLink: String) {
        when {
            deepLink.startsWith("momo://group/") -> {
                val id = deepLink.removePrefix("momo://group/").trim()
                if (id.isNotBlank()) openConversation(id)
            }
            deepLink.startsWith("momo://chat/") -> {
                val id = deepLink.removePrefix("momo://chat/").trim()
                if (id.isNotBlank()) openSession(Session(sessionId = id))
            }
        }
    }

    /** 登录成功后由 UI 触发: 处理登录前暂存的 deepLink。 */
    fun consumePendingDeepLink() {
        pendingDeepLink?.let { applyDeepLink(it) }
        pendingDeepLink = null
        // iOS: 本地通知点击带来的 deepLink(UNUserNotificationCenter delegate 暂存)。
        com.mobius.momo.data.consumePendingNotificationDeepLink()?.let { applyDeepLink(it) }
    }

    fun openSession(session: Session) {
        if (session.sessionId.isBlank()) return
        // 返回目标按来源: IssueSessions 钻取 → IssueSessions; 项目页(含精简模式直达) → Projects;
        // 其余 → ChatList。精简模式返回后仍停在项目页精简 tab(liteSelectedTab 跨导航持久)。
        val returnTarget = when (state.value.screen) {
            AppScreen.IssueSessions -> AppScreen.IssueSessions
            AppScreen.Projects -> AppScreen.Projects
            else -> AppScreen.ChatList
        }
        // 项目钻取 session: 进页时抑制一次自动播报(避免把历史最后一条 Momo 当新回复播报)。
        suppressInitialAutoSpeech = returnTarget == AppScreen.IssueSessions
        // 保留出站队列: 用户切走时可能还有消息排队未发, 回到/进入会话后继续按序发出(不丢消息)。
        clearStreamState(keepQueuedMessages = true)
        _state.update {
            it.copy(
                screen = AppScreen.Home,
                menuOpen = false,
                chatReturnTarget = returnTarget,
                activeSessionId = session.sessionId,
                activeSessionTitle = session.name.ifBlank { "Mobius 会话" },
                activeSessionStatus = session.agentStatus,
                activeSessionModelLabel = session.modelLabel,
                messages = sampleMessages(),
                loading = true,
                toast = null,
            )
        }
        scope.launch {
            runCatching {
                val snapshot = api.assistantSnapshot(session.sessionId)
                applySnapshot(snapshot, allowAutoSpeech = false)
                connectStream(session.sessionId)
            }.onFailure { e ->
                _state.update { it.copy(loading = false) }
                showToast(e.message ?: "打开 Mobius 会话失败")
                connectStream(session.sessionId)
            }
        }
    }

    // 聊天页返回: 从项目钻取进来的回 IssueSessions, 其余回 ChatList(原行为)。
    fun backFromHome() {
        val target = state.value.chatReturnTarget ?: AppScreen.ChatList
        _state.update { it.copy(chatReturnTarget = null) }
        navigate(target)
    }

    fun createClone() {
        val workspace = state.value.workspace
        val issueId = workspace?.issue?.id.orEmpty()
        val title = state.value.cloneTitle.trim()
        val description = state.value.cloneDescription.trim()
        if (issueId.isBlank()) {
            showToast("未找到 Mobius 任务单")
            return
        }
        if (title.isBlank() || description.isBlank()) {
            showToast("请填写分身名称和任务描述")
            return
        }
        scope.launch {
            _state.update { it.copy(loading = true, toast = null) }
            runCatching {
                val session = api.createClone(issueId, title, description, state.value.cloneModel)
                api.startSession(session.sessionId, description)
                val sessions = api.assistantSessions()
                _state.update {
                    it.copy(
                        loading = false,
                        cloneSheetOpen = false,
                        // 新分身可能尚未被列表接口收录（最终一致性，或后端尚未部署放宽后的过滤），
                        // 主动把它合并去重到列表头部；否则当拉到的列表非空但不含新分身时新分身会被
                        // 直接丢弃——表现为「显示创建成功却看不到」。即便后端已返回它也无妨（按 id 去重）。
                        clones = (listOf(session) + sessions).distinctBy { s -> s.sessionId },
                    )
                }
                showToast("分身已创建并启动")
                // 创建后直接进入该分身的聊天页，不再回到分身列表。
                openSession(session)
            }.onFailure { e ->
                _state.update { it.copy(loading = false) }
                showToast(e.message ?: "创建分身失败")
            }
        }
    }

    fun setThemeMode(value: ThemeMode) {
        storage.savePreference(THEME_MODE_KEY, value.name)
        _state.update { it.copy(themeMode = value) }
    }

    fun setThemePalette(value: ThemePalette) {
        storage.savePreference(THEME_PALETTE_KEY, value.name)
        _state.update { it.copy(themePalette = value) }
    }

    fun togglePush() {
        val shouldEnable = !state.value.pushEnabled
        // 持久化总开关，下次冷启动由 restorePushPreference 恢复。
        storage.savePreference(SECURE_PREF_PUSH_ENABLED, shouldEnable.toString())
        if (!shouldEnable) {
            NotificationGateway.stopForeground()
            runCatching { pushProvider.setEnabled(false) }
            unregisterPushToken()
            _state.update { it.copy(pushEnabled = false) }
            showToast("消息推送已关闭")
            return
        }
        scope.launch {
            val granted = runCatching { NotificationGateway.requestPermission() }.getOrDefault(false)
            if (!granted) {
                NotificationGateway.stopForeground()
                _state.update { it.copy(pushEnabled = false) }
                showToast("通知权限未开启，暂无法后台提醒")
                return@launch
            }
            _state.update { it.copy(pushEnabled = true) }
            NotificationGateway.startForeground()
            runCatching { pushProvider.setEnabled(true) }
            NotificationGateway.requestIgnoreBatteryOptimizations()
            // 开启推送后立即把设备令牌上报后端（首次或令牌变化时）。
            registerPushTokenIfNeeded()
            showToast("消息推送已开启")
        }
    }

    /**
     * 把当前设备的推送令牌（JPush RegistrationID）上报给后端。仅当：推送开启、已登录、
     * 且令牌非空且（未上报过 或 与上次不同）时真正发请求。后端 404（端点未实现）由 runCatching 兜底，
     * 不影响主流程。SDK 注册异步，[PushProvider.getRegistrationId] 内部已做有限轮询。
     */
    private fun registerPushTokenIfNeeded() {
        if (!state.value.pushEnabled) return
        if (state.value.user == null) return
        scope.launch {
            // 1) JPush RegistrationID (前台/后台保活时 JPush 自有通道送达)
            val rid = runCatching { pushProvider.getRegistrationId() }.getOrNull()?.takeIf { it.isNotBlank() }
            if (rid != null) {
                val lastToken = storage.getPreference(SECURE_PREF_PUSH_TOKEN)
                val registered = storage.getPreference(SECURE_PREF_PUSH_TOKEN_REGISTERED) == "true"
                if (!(registered && lastToken == rid)) {
                    runCatching { api.registerDeviceToken(rid, PUSH_PLATFORM_JPUSH) }
                        .onSuccess {
                            storage.savePreference(SECURE_PREF_PUSH_TOKEN, rid)
                            storage.savePreference(SECURE_PREF_PUSH_TOKEN_REGISTERED, "true")
                        }
                }
            }
            // 2) 华为 HMS 令牌 (App 被杀时华为直推; JPush 华为插件接不住, 由 MomoHmsPushService 捕获)
            val hms = runCatching { pushProvider.getHmsToken() }.getOrNull()?.takeIf { it.isNotBlank() }
            if (hms != null) {
                val lastHms = storage.getPreference(SECURE_PREF_PUSH_HMS_TOKEN)
                val hmsRegistered = storage.getPreference(SECURE_PREF_PUSH_HMS_REGISTERED) == "true"
                if (!(hmsRegistered && lastHms == hms)) {
                    runCatching { api.registerDeviceToken(hms, PUSH_PLATFORM_HUAWEI) }
                        .onSuccess {
                            storage.savePreference(SECURE_PREF_PUSH_HMS_TOKEN, hms)
                            storage.savePreference(SECURE_PREF_PUSH_HMS_REGISTERED, "true")
                        }
                }
            }
        }
    }

    /** 注销当前设备令牌与后端的绑定（关闭推送 / 登出时调用）。 */
    private fun unregisterPushToken() {
        val rid = storage.getPreference(SECURE_PREF_PUSH_TOKEN)?.takeIf { it.isNotBlank() }
        val hms = storage.getPreference(SECURE_PREF_PUSH_HMS_TOKEN)?.takeIf { it.isNotBlank() }
        if (rid == null && hms == null) return
        scope.launch {
            if (rid != null) runCatching { api.unregisterDeviceToken(rid) }
            if (hms != null) runCatching { api.unregisterDeviceToken(hms) }
            storage.savePreference(SECURE_PREF_PUSH_TOKEN_REGISTERED, "false")
            storage.savePreference(SECURE_PREF_PUSH_HMS_REGISTERED, "false")
        }
    }

    fun toggleTts() {
        val nextEnabled = !state.value.ttsEnabled
        storage.savePreference(SECURE_PREF_TTS_ENABLED, if (nextEnabled) "1" else "0")
        if (!nextEnabled) {
            stopSpeaking()
            _state.update { it.copy(ttsEnabled = false, ttsSpeakingMessageId = null) }
        } else {
            _state.update { it.copy(ttsEnabled = true) }
        }
    }

    fun setTtsPlaybackMode(mode: TtsPlaybackMode) {
        storage.savePreference(SECURE_PREF_TTS_PLAYBACK_MODE, mode.name)
        _state.update { it.copy(ttsPlaybackMode = mode) }
    }

    fun setSelectedVoice(voice: String) {
        storage.savePreference(SECURE_PREF_SELECTED_VOICE, voice)
        ttsController.setVoice(voice)
        _state.update { it.copy(selectedVoice = voice) }
        // 切换音色后立即预取当前最新助手消息在该音色下的音频, 消除随后播放/自动播报的网络往返滞后。
        prefetchLatestAssistantTts()
    }

    private fun prefetchLatestAssistantTts() {
        if (state.value.selectedVoice == TTS_SYSTEM_VOICE_ID) return
        val message = state.value.messages.lastOrNull {
            it.author == MessageAuthor.Momo && it.processType == null &&
                (it.text.isNotBlank() || !it.voiceText.isNullOrBlank())
        } ?: return
        val text = assistantSpeechText(message, state.value.ttsPlaybackMode)
        if (text.isBlank()) return
        scope.launch { runCatching { ttsController.prefetch(text) } }
    }

    fun refreshVoices() {
        if (state.value.voicesLoading) return
        _state.update { it.copy(voicesLoading = true, voicesLoadFailed = false) }
        scope.launch {
            runCatching { api.fetchTtsVoices() }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            availableVoices = result.voices,
                            ttsConfigured = result.configured,
                            voicesLoading = false,
                            voicesLoadFailed = false,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            availableVoices = emptyList(),
                            voicesLoading = false,
                            voicesLoadFailed = true,
                        )
                    }
                    showToast("豆包音色拉取失败,仅可使用系统默认")
                }
        }
    }

    fun beginVoiceInput(group: Boolean = false) {
        if (state.value.voiceRecording) return
        voiceTargetGroup = group
        if (speechPermissionController.hasPermission()) {
            startVoiceInput()
            return
        }
        scope.launch {
            val permissionStatus = runCatching { speechPermissionController.requestPermission() }
                .getOrDefault(SpeechPermissionStatus.Denied)
            if (permissionStatus == SpeechPermissionStatus.Granted) {
                startVoiceInput()
            } else {
                rejectVoiceInput()
            }
        }
    }

    private fun startVoiceInput() {
        if (state.value.voiceRecording) return
        _state.update { it.copy(speechPermissionGranted = true, speechPermissionDenied = false) }
        stopSpeaking()
        voiceTimeoutJob?.cancel()
        voiceCommitJob?.cancel()
        pendingVoiceCommit = false
        pendingVoiceAudio = false
        voiceInputStartMs = nowEpochMillis()
        _state.update {
            it.copy(
                voiceRecording = true,
                voiceTranscribing = false,
                voiceCanceling = false,
                voiceTranscript = "",
                voiceVolumeLevel = 1,
                ttsSpeakingMessageId = null,
                ttsFetchingMessageId = null,
                toast = null,
            )
        }
        speechRecognizer.start(languageTag = "zh-CN") { event -> handleSpeechEvent(event) }
        voiceTimeoutJob = scope.launch {
            delay(60_000L)
            if (state.value.voiceRecording) finishVoiceInput(forceCommit = true)
        }
    }

    private fun rejectVoiceInput() {
        pendingVoiceCommit = false
        pendingVoiceAudio = false
        voiceTimeoutJob?.cancel()
        voiceCommitJob?.cancel()
        _state.update {
            it.copy(
                speechPermissionGranted = false,
                speechPermissionDenied = true,
                voiceRecording = false,
                voiceTranscribing = false,
                voiceCanceling = false,
                voiceTranscript = "",
                voiceVolumeLevel = 0,
            )
        }
        showToast("未检测到麦克风或权限未开启，仍可文字输入")
    }

    fun updateVoiceDrag(totalDragY: Float) {
        if (!state.value.voiceRecording) return
        _state.update { it.copy(voiceCanceling = totalDragY < -72f) }
    }

    fun finishVoiceInput(forceCommit: Boolean = false) {
        if (!state.value.voiceRecording) return
        val shouldCancel = state.value.voiceCanceling && !forceCommit
        voiceTimeoutJob?.cancel()
        voiceCommitJob?.cancel()
        if (shouldCancel) {
            pendingVoiceCommit = false
            speechRecognizer.cancel()
            _state.update {
                it.copy(
                    voiceRecording = false,
                    voiceTranscribing = false,
                    voiceCanceling = false,
                    voiceTranscript = "",
                    voiceVolumeLevel = 0,
                )
            }
            showToast("已取消语音输入")
            return
        }

        // 误触保护：按住时间过短直接取消，避免 MediaRecorder/AVAudioRecorder 在极短录音上
        // stop() 失败、产出空文件而提示"录音内容为空"。强制提交（如 60s 超时）不受此限制。
        val voiceElapsedMs = nowEpochMillis() - voiceInputStartMs
        if (!forceCommit && isVoiceInputTooShort(voiceElapsedMs)) {
            pendingVoiceCommit = false
            pendingVoiceAudio = false
            speechRecognizer.cancel()
            _state.update {
                it.copy(
                    voiceRecording = false,
                    voiceTranscribing = false,
                    voiceCanceling = false,
                    voiceTranscript = "",
                    voiceVolumeLevel = 0,
                )
            }
            showToast("录音时间太短，请长按说话再松手")
            return
        }

        pendingVoiceCommit = true
        pendingVoiceAudio = false
        _state.update { it.copy(voiceTranscribing = true, voiceVolumeLevel = 0) }
        speechRecognizer.stop()
        voiceCommitJob = scope.launch {
            delay(2_500L)
            if (!pendingVoiceAudio) commitVoiceTranscriptIfPending()
        }
    }

    fun cancelVoiceInput() {
        if (!state.value.voiceRecording) return
        voiceTimeoutJob?.cancel()
        voiceCommitJob?.cancel()
        pendingVoiceCommit = false
        pendingVoiceAudio = false
        speechRecognizer.cancel()
        _state.update {
            it.copy(
                voiceRecording = false,
                voiceTranscribing = false,
                voiceCanceling = false,
                voiceTranscript = "",
                voiceVolumeLevel = 0,
            )
        }
    }

    fun replayAssistantMessage(message: ChatMessage) {
        if (message.author != MessageAuthor.Momo || (message.text.isBlank() && message.voiceText.isNullOrBlank())) return
        speakAssistant(message, markAsAutomatic = false)
    }

    // 朗读任意文本(长按消息"播放"用)。群聊/1v1 通用，不限定作者。
    fun speakMessageText(text: String, speakId: String) {
        val t = text.trim()
        if (t.isBlank()) return
        launchSpeak(t, markAsAutomaticAs = speakId, markAsAutomatic = false, speakingId = speakId)
    }

    fun speakChatMessage(message: ChatMessage) {
        speakMessageText(message.text.ifBlank { message.voiceText ?: "" }, message.id)
    }

    fun stopSpeaking() {
        currentSpeakJob?.cancel()
        currentSpeakJob = null
        ttsController.stop()
        _state.update { it.copy(ttsSpeakingMessageId = null, ttsFetchingMessageId = null) }
    }

    fun stopSpeakingAndDisableTts() {
        stopSpeaking()
        _state.update { it.copy(ttsEnabled = false, ttsSpeakingMessageId = null, ttsFetchingMessageId = null) }
        showToast("已停止并关闭自动播报")
    }

    fun logout() {
        scope.launch {
            streamJob?.cancel()
            snapshotPollJob?.cancel()
            voiceTimeoutJob?.cancel()
            voiceCommitJob?.cancel()
            stopSpeaking()
            pendingVoiceAudio = false
            speechRecognizer.cancel()
            // 聚合推送：登出前先用仍有效的 token 注销设备令牌绑定（须在 storage.clear 之前，
            // 否则 bearer token 被清，注销请求会 401）。
            val pushToken = storage.getPreference(SECURE_PREF_PUSH_TOKEN)?.takeIf { it.isNotBlank() }
            if (pushToken != null) runCatching { api.unregisterDeviceToken(pushToken) }
            val hmsToken = storage.getPreference(SECURE_PREF_PUSH_HMS_TOKEN)?.takeIf { it.isNotBlank() }
            if (hmsToken != null) runCatching { api.unregisterDeviceToken(hmsToken) }
            NotificationGateway.stopForeground()
            storage.clear()
            clearStreamState()
            conversationClearCutoffs = emptyMap()
            deletedMessageIds = emptyMap()
            deletedGroupMessageIds = emptyMap()
            groupClearCutoffs = emptyMap()
            _state.value = UiState(
                username = state.value.username,
                passwordRequired = state.value.passwordRequired,
                serverBaseUrl = currentBaseUrl,
                selectedVoice = state.value.selectedVoice,
                ttsEnabled = state.value.ttsEnabled,
                ttsPlaybackMode = state.value.ttsPlaybackMode,
                availableVoices = state.value.availableVoices,
            )
            ttsController.setVoice(state.value.selectedVoice)
        }
    }

    private fun restoreToken() {
        val token = storage.getToken()
        val metadata = storage.getTokenMetadata()
        if (!canAttemptTokenRestore(currentBaseUrl, token, metadata, nowEpochMillis())) {
            storage.clear()
            clearStreamState()
            _state.update {
                it.copy(
                    screen = AppScreen.Login,
                    user = null,
                    loading = false,
                )
            }
            return
        }
        scope.launch {
            _state.update { it.copy(loading = true) }
            runCatching {
                val user = api.me()
                _state.update { it.copy(user = user, screen = AppScreen.ChatList, loading = false) }
                loadConversationClearCutoffs(user.id)
                loadDeletedState(user.id)
                ensureSpeechPermission()
                runCatching { NotificationGateway.requestPermission() }
                loadWorkspaceAndClones()
                loadContacts(force = true)
                loadConversations(force = true)
                startGroupUnreadPolling()
                // 自动登录同样启动 keepalive 前台服务(默认开启推送)，保证后台推送。
                if (state.value.pushEnabled) NotificationGateway.startForeground()
                // 自动登录同样把设备令牌上报后端（聚合推送）。
                registerPushTokenIfNeeded()
            }.onFailure {
                storage.clear()
                conversationClearCutoffs = emptyMap()
                deletedMessageIds = emptyMap()
                deletedGroupMessageIds = emptyMap()
                groupClearCutoffs = emptyMap()
                _state.update { it.copy(loading = false, screen = AppScreen.Login) }
            }
        }
    }

    private fun restoreThemePreferences() {
        // 三态主题: System(跟随系统) / Light / Dark, 用户自选, 默认跟随系统。
        // Hypergrid 深色是设计主形态, 浅色为晨雾变体 — 两套都完整支持。
        val mode = storage.getPreference(THEME_MODE_KEY)
            ?.let { raw -> ThemeMode.entries.firstOrNull { it.name == raw } }
            ?: ThemeMode.System
        val palette = storage.getPreference(THEME_PALETTE_KEY)
            ?.let { raw -> ThemePalette.entries.firstOrNull { it.name == raw } }
            ?: ThemePalette.Default
        _state.update { it.copy(themeMode = mode, themePalette = palette) }
    }

    private fun refreshAuthConfig() {
        if (currentBaseUrl.isBlank()) return
        scope.launch {
            runCatching { api.authConfig() }
                .onSuccess { config -> _state.update { it.copy(passwordRequired = config.passwordRequired) } }
        }
    }

    private fun ensureSpeechPermission() {
        scope.launch {
            if (speechPermissionController.hasPermission()) {
                _state.update { it.copy(speechPermissionGranted = true, speechPermissionDenied = false) }
                return@launch
            }
            val status = runCatching { speechPermissionController.requestPermission() }
                .getOrDefault(SpeechPermissionStatus.Denied)
            _state.update {
                it.copy(
                    speechPermissionGranted = status == SpeechPermissionStatus.Granted,
                    speechPermissionDenied = status == SpeechPermissionStatus.Denied,
                )
            }
            if (status == SpeechPermissionStatus.Denied) showToast("未检测到麦克风或权限未开启，仍可文字输入")
        }
    }

    private suspend fun loadWorkspaceAndClones() {
        val workspace = runCatching { api.assistantWorkspace() }
            .onFailure { showToast(it.message ?: "读取 Mobius 工作区失败") }
            .getOrNull()
        val snapshots = api.assistantSnapshots()
        val clones = snapshots.map { it.session }.filter { it.sessionId.isNotBlank() }
        val projects = runCatching { api.projects() }.getOrDefault(emptyList())
        clonesLoadedAt = nowEpochMillis()
        projectsLoadedAt = nowEpochMillis()
        val cacheUserId = state.value.user?.id.orEmpty()
        if (cacheUserId.isNotBlank()) {
            saveTabCacheValue(TAB_CACHE_CLONES, cacheUserId, clones)
            saveTabCacheValue(TAB_CACHE_PROJECTS, cacheUserId, projects)
        }
        val modelOptions = runCatching { api.sessionModelOptions() }
            .getOrDefault(state.value.cloneModelOptions)
            .filter { it.key.isNotBlank() }
        val currentSnapshot = snapshots.firstOrNull { it.session.isMainAssistant() }
            ?: snapshots.firstOrNull { it.session.sessionId.isNotBlank() }
        val current = currentSnapshot?.session
        _state.update {
            it.copy(
                workspace = workspace,
                clones = clones,
                projects = projects,
                clonesLoaded = true,
                cloneModelOptions = modelOptions,
                cloneModel = if (modelOptions.any { option -> option.key == it.cloneModel }) {
                    it.cloneModel
                } else {
                    modelOptions.firstOrNull()?.key ?: "codex"
                },
                activeSessionId = current?.sessionId.orEmpty(),
                activeSessionTitle = current?.name?.ifBlank { "我的主 Mobius" } ?: "我的主 Mobius",
                loading = false,
            )
        }
        currentSnapshot?.let { applySnapshot(it, allowAutoSpeech = false) }
        current?.sessionId?.takeIf { it.isNotBlank() }?.let { connectStream(it) }
        refreshVoices()
    }

    private fun applySnapshot(snapshot: AssistantSnapshot, allowAutoSpeech: Boolean) {
        val session = snapshot.session
        val messages = snapshot.messages
        // 与 handleTyping 一致的"真正文本回复"判定(忽略 thinking/tool_call 过程条目 + 队列中未发出的消息):
        // 只有确实出现了回复文本才算这轮结束; agent 还在思考/调用工具时保持 loading。
        val hasRealReply = messages.hasAssistantAfterLatestUser(queuedUserMessageIds())
        if (messages.isNotEmpty()) {
            sseHistoryMessages = messages
            sseJsonlMessages = emptyList()
            val historicalIds = messages.map { it.id }.toHashSet()
            // 仅按 id 移除已被历史覆盖的 pending; 内容级去重(含附件消息保留 pending)统一交给
            // rebuildMessages 的 removeHistoricalCoveredByPending, 避免 pending(带附件行/图片)被这里误删。
            pendingUserMessages = pendingUserMessages.filterNot { it.id in historicalIds }
            snapshotLoadedForSessionId = session.sessionId
            if (hasRealReply) {
                clearAwaitingAssistantReply(session.sessionId)
                resetPendingStreamUi()
            }
        }
        val keepWaitingForAssistant = shouldKeepWaitingForAssistant(session.sessionId, messages)
        // 流式回复期间(最近收到 onChunk)保持 loading, 对所有 session 生效(含主小莫 1v1)。
        val recentlyStreamed = nowEpochMillis() - lastStreamUiUpdateMillis < PROJECT_STREAM_IDLE_TIMEOUT_MS
        // 用户已发消息但还没有任何真实回复文本 → 无论如何保持 loading(等待 agent 回复),
        // 否则快照轮询会在 agent 思考期(working=false, 只有过程条目)提前收掉 typing。
        // 注意排除仍在出站队列里的消息(尚未发出, 不可能算"在等回复"), 否则排队消息会把 loading 顶死。
        val unsentIds = queuedUserMessageIds()
        val awaitingReply = state.value.messages.any { it.author == MessageAuthor.User && it.id !in unsentIds } && !hasRealReply
        val nextStreaming = snapshot.status.working || keepWaitingForAssistant || recentlyStreamed || awaitingReply
        var shouldDrainQueue = false
        _state.update {
            shouldDrainQueue = it.isStreaming && !nextStreaming
            it.copy(
                loading = false,
                activeSessionId = session.sessionId.ifBlank { it.activeSessionId },
                activeSessionTitle = session.name.ifBlank { it.activeSessionTitle },
                activeSessionStatus = session.agentStatus,
                clones = upsertSession(it.clones, session).filter { item -> item.sessionId.isNotBlank() },
                typing = nextStreaming,
                isStreaming = nextStreaming,
                streamingProcess = if (nextStreaming) {
                    it.streamingProcess ?: "Mobius 正在思考…"
                } else {
                    null
                },
            )
        }
        rebuildMessages()
        // 预取最新助手消息的豆包音频: 进会话/快照刷新/新回复落定时尽早把音频备好并预热 HTTP 连接,
        // 这样随后点击播放命中缓存即播、自动播报复用已预热连接(豆包首次连接常卡到超时, 预热可省掉那段空白)。
        prefetchLatestAssistantTts()
        if (allowAutoSpeech) speakLatestSettledAssistantIfNeeded()
        if (shouldDrainQueue) drainMessageQueueIfNeeded()
    }

    private fun connectStream(sessionId: String) {
        streamJob?.cancel()
        streamJob = scope.launch {
            var reconnectDelayMs = 1_000L
            while (state.value.screen != AppScreen.Login && state.value.activeSessionId == sessionId) {
                var emittedError = false
                try {
                    api.streamSession(
                        sessionId = sessionId,
                        onConnected = {
                            reconnectDelayMs = 1_000L
                        },
                        onTyping = { active -> handleTyping(active) },
                        onHistory = { history ->
                            if (snapshotLoadedForSessionId != sessionId && history.isNotEmpty()) {
                                sseHistoryMessages = history
                                rebuildMessages()
                            }
                        },
                        onJsonlHistory = { chunks, reset, _ ->
                            if (snapshotLoadedForSessionId != sessionId) {
                                val messages = chunks
                                    .filter { it.author == MessageAuthor.Momo && (it.text.isNotBlank() || it.processType != null) }
                                    .map { chunkToMessage(it) }
                                if (reset) {
                                    sseHistoryMessages = sseHistoryMessages.filter { it.author != MessageAuthor.Momo }
                                }
                                sseJsonlMessages = if (reset) messages else mergeMessages(sseJsonlMessages, messages)
                                rebuildMessages()
                            }
                        },
                        onChunk = { chunk -> handleStreamChunk(chunk) },
                        onError = { message ->
                            emittedError = true
                            showToast(message)
                        },
                        onProcess = { label -> handleProcessLabel(label) },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emittedError = true
                    showToast(e.message ?: "SSE 连接中断")
                }

                if (state.value.screen == AppScreen.Login || state.value.activeSessionId != sessionId) break
                // 例行断连（服务端关闭 / 空闲看门狗触发 / 网络抖动）静默重连，避免频繁弹"链接失败"打扰；
                // 只有 streamSession 内部真正报错（onError / 异常）才会 showToast，让用户看到实质性问题。
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun logoutFrom401() {
        if (state.value.screen == AppScreen.Login) return
        streamJob?.cancel()
        snapshotPollJob?.cancel()
        storage.clear()
        clearStreamState()
        conversationClearCutoffs = emptyMap()
        deletedMessageIds = emptyMap()
        deletedGroupMessageIds = emptyMap()
        groupClearCutoffs = emptyMap()
        _state.value = UiState(
            username = state.value.username,
            passwordRequired = state.value.passwordRequired,
            serverBaseUrl = currentBaseUrl,
            toast = "登录已过期，请重新登录",
        )
        scheduleToastClear("登录已过期，请重新登录")
    }

    private fun handleTyping(active: Boolean) {
        // 队列中尚未发出的 user 消息不算"已发出的提问"(不可能已有回复), 否则排队消息会
        // 把 typing 顶成永久等待 → 队列互锁(见 drainMessageQueueIfNeeded 注释)。
        val unsentIds = queuedUserMessageIds()
        if (!active) {
            // 轮次结束: 先落盘节流队列里的完整消息, 再基于**最新 state** 重估"是否已有真实回复"。
            // (旧逻辑在 flush 前检查 hasRealReply — 节流消息未落盘时误判 false → typing 永久保持
            //  → loading 不消失 + 自动播报被 typing 守卫拦截, 即用户反馈的"loading 卡住+不播报"。)
            flushPendingStreamMessage()
            val hasReplyAfterFlush = state.value.messages.hasAssistantAfterLatestUser(unsentIds)
            if (hasReplyAfterFlush) {
                clearAwaitingAssistantReply()
                lastStreamUiUpdateMillis = 0L
            }
        }
        val hasRealReply = state.value.messages.hasAssistantAfterLatestUser(unsentIds)
        val keepWaitingForAssistant = !active && shouldKeepWaitingForAssistant(
            sessionId = state.value.activeSessionId,
            messages = state.value.messages,
        )
        // 流式回复期间(最近收到 onChunk)保持 loading —— 对所有 session 生效(含主小莫 1v1),
        // 不再仅限项目钻取; 否则首个 chunk 清掉 awaiting 后, 若 SSE 无 typing 事件/snapshot 非 working,
        // typing 会掉成 false, 气泡"闪一下就消失"。
        val recentlyStreamed = nowEpochMillis() - lastStreamUiUpdateMillis < PROJECT_STREAM_IDLE_TIMEOUT_MS
        val nextTyping = active || keepWaitingForAssistant || recentlyStreamed || (!active && !hasRealReply)
        var shouldDrainQueue = false
        _state.update {
            shouldDrainQueue = it.isStreaming && !nextTyping
            it.copy(
                typing = nextTyping,
                isStreaming = nextTyping,
                streamingProcess = if (nextTyping) {
                    it.streamingProcess ?: "Mobius 正在思考…"
                } else {
                    null
                },
            )
        }
        if (!active) {
            if (!keepWaitingForAssistant) {
                rebuildMessages()
                prefetchLatestAssistantTts()
                speakLatestSettledAssistantIfNeeded()
            }
            if (shouldDrainQueue) drainMessageQueueIfNeeded()
        }
    }

    private fun handleStreamChunk(chunk: StreamTextChunk) {
        if (chunk.author == MessageAuthor.User) return
        // 每条 jsonl_entry 都是后端透传的一条完整消息，而非 token 增量。
        // 直接按 chunk.id 作为一条独立消息 upsert，避免多条 assistant 消息被
        // 拼接进同一气泡（旧逻辑会把一轮内的多条回复合并/丢弃）。
        val cleanedText = chunk.text.trim()
        val chunkVoice = chunk.voiceText?.takeIf { it.isNotBlank() }
        val isProcess = chunk.processType != null

        // 过程条目: 即使 text 为空也创建消息 (渲染为可折叠卡片)
        if (isProcess) {
            // 跳过空壳过程条目
            if (chunk.processType == null && cleanedText.isEmpty()) return
            val message = ChatMessage(
                id = chunk.id,
                author = MessageAuthor.Momo,
                text = cleanedText,
                time = chunk.time,
                voiceText = chunkVoice,
                createdAtMillis = chunk.createdAtMillis ?: nowEpochMillis(),
                processType = chunk.processType,
                processLabel = chunk.processLabel,
            )
            scheduleStreamMessageUpdate(message, clearProcess = false)
            return
        }

        // 过滤噪音文本 (在 voice-only 检查之后, 确保语音数据不被误杀)
        if (cleanedText.isNotBlank() && isNoiseText(cleanedText)) return
        if (cleanedText.isBlank() && chunkVoice == null) return

        if (cleanedText.isBlank()) {
            // 仅含语音标记、无可显示文本的条目：收集用于 TTS，不渲染气泡（与网页端一致）。
            // 走到这里时 chunkVoice 必非空（上文已过滤两者皆空的情形）。
            chunkVoice?.let { pendingVoiceOnlyText = combineVoiceText(pendingVoiceOnlyText, it) }
            return
        }

        val message = ChatMessage(
            id = chunk.id,
            author = MessageAuthor.Momo,
            text = cleanedText,
            time = chunk.time,
            voiceText = chunkVoice,
            createdAtMillis = chunk.createdAtMillis ?: nowEpochMillis(),
        )
        scheduleStreamMessageUpdate(message, clearProcess = state.value.streamingProcess != null)
    }

    private fun scheduleStreamMessageUpdate(message: ChatMessage, clearProcess: Boolean) {
        pendingStreamMessages = upsertMessage(pendingStreamMessages, message)
        pendingStreamProcessClear = pendingStreamProcessClear || clearProcess
        if (streamUiFlushJob?.isActive == true) return
        val elapsed = nowEpochMillis() - lastStreamUiUpdateMillis
        val delayMs = (STREAM_UI_UPDATE_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        streamUiFlushJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            flushPendingStreamMessage(cancelScheduled = false)
        }
    }

    private fun flushPendingStreamMessage(cancelScheduled: Boolean = true) {
        if (cancelScheduled) streamUiFlushJob?.cancel()
        streamUiFlushJob = null
        val messages = pendingStreamMessages
        val clearProcess = pendingStreamProcessClear
        if (messages.isEmpty() && !clearProcess) return
        pendingStreamMessages = emptyList()
        pendingStreamProcessClear = false
        lastStreamUiUpdateMillis = nowEpochMillis()
        clearAwaitingAssistantReply(state.value.activeSessionId)
        if (messages.isNotEmpty()) {
            sseJsonlMessages = messages.fold(sseJsonlMessages) { acc, item -> upsertMessage(acc, item) }
        }
        if (clearProcess) _state.update { it.copy(streamingProcess = null) }
        rebuildMessages()
    }

    private fun resetPendingStreamUi() {
        streamUiFlushJob?.cancel()
        streamUiFlushJob = null
        pendingStreamMessages = emptyList()
        pendingStreamProcessClear = false
    }

    private fun handleProcessLabel(label: String?) {
        if (label.isNullOrBlank()) {
            if (state.value.streamingProcess != null) {
                _state.update { it.copy(streamingProcess = null) }
            }
            return
        }
        if (state.value.streamingProcess == label) return
        _state.update { it.copy(streamingProcess = label) }
    }

    private fun handleSpeechEvent(event: SpeechRecognitionEvent) {
        when (event) {
            SpeechRecognitionEvent.Ready -> {
                _state.update { it.copy(voiceVolumeLevel = it.voiceVolumeLevel.coerceAtLeast(1)) }
            }
            is SpeechRecognitionEvent.Partial -> {
                _state.update { it.copy(voiceTranscript = event.text) }
            }
            is SpeechRecognitionEvent.Final -> {
                _state.update { it.copy(voiceTranscript = event.text) }
                if (pendingVoiceCommit) commitVoiceTranscriptIfPending()
            }
            is SpeechRecognitionEvent.Audio -> {
                if (!pendingVoiceCommit) return
                pendingVoiceAudio = true
                voiceCommitJob?.cancel()
                _state.update {
                    it.copy(
                        voiceTranscribing = true,
                        voiceTranscript = "",
                        voiceVolumeLevel = 0,
                    )
                }
                voiceCommitJob = scope.launch {
                    delay(30_000L)
                    if (pendingVoiceCommit && pendingVoiceAudio) {
                        failVoiceInput("语音识别超时，请稍后重试")
                    }
                }
                scope.launch {
                    runCatching {
                        api.transcribeAssistantAudio(event.bytes, event.mimeType, event.fileName)
                    }.onSuccess { text ->
                        pendingVoiceAudio = false
                        _state.update { it.copy(voiceTranscript = text) }
                        if (pendingVoiceCommit) commitVoiceTranscriptIfPending()
                    }.onFailure { error ->
                        pendingVoiceAudio = false
                        failVoiceInput(error.message ?: "语音识别失败，请重新录制")
                    }
                }
            }
            is SpeechRecognitionEvent.Volume -> {
                if (!state.value.voiceTranscribing) {
                    _state.update { it.copy(voiceVolumeLevel = event.level.coerceIn(0, 5)) }
                }
            }
            is SpeechRecognitionEvent.Error -> {
                if (pendingVoiceCommit && state.value.voiceTranscript.isNotBlank()) {
                    commitVoiceTranscriptIfPending()
                    return
                }
                failVoiceInput(event.message)
            }
            SpeechRecognitionEvent.End -> {
                _state.update { it.copy(voiceVolumeLevel = 0) }
            }
        }
    }

    private fun commitVoiceTranscriptIfPending() {
        if (!pendingVoiceCommit) return
        pendingVoiceCommit = false
        pendingVoiceAudio = false
        voiceTimeoutJob?.cancel()
        voiceCommitJob?.cancel()
        val text = state.value.voiceTranscript.trim()
        _state.update {
            it.copy(
                voiceRecording = false,
                voiceTranscribing = false,
                voiceCanceling = false,
                voiceTranscript = "",
                voiceVolumeLevel = 0,
            )
        }
        if (text.isBlank()) {
            showToast("没有识别到语音")
        } else if (voiceTargetGroup) {
            sendGroupMessage(text, emptyList())
        } else {
            sendTextMessage(text)
        }
    }

    /** 过滤无用噪音消息: 已取消/空操作/纯工具输出壳等. */
    private fun isNoiseText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length <= 4 && t in setOf("已取消", "Cancelled", "canceled", "(已取消)", "(空)", "null", "{}", "[]")) return true
        // 工具输出/状态消息: 仅含 { } 或 [...] 无实际内容
        if (t.length <= 20 && (t.startsWith("{") && t.endsWith("}") || t.startsWith("[") && t.endsWith("]"))) {
            val inner = t.substring(1, t.length - 1).trim()
            if (inner.isEmpty()) return true
        }
        return false
    }

    private fun failVoiceInput(message: String) {
        pendingVoiceCommit = false
        pendingVoiceAudio = false
        voiceTimeoutJob?.cancel()
        voiceCommitJob?.cancel()
        _state.update {
            it.copy(
                voiceRecording = false,
                voiceTranscribing = false,
                voiceCanceling = false,
                voiceTranscript = "",
                voiceVolumeLevel = 0,
            )
        }
        showToast(message)
    }

    private fun rebuildMessages() {
        val historical = mergeMessages(sseHistoryMessages, sseJsonlMessages)
        // 附件消息去重: 本地 pending user 文本带"附件：name"行(图片预览关联用), 后端 historical user
        // 只有原文, 两者文本不同。**保留 pending**(带附件展示/图片), 从 historical 移除被 pending 覆盖的
        // 同一条 user(按原文+时间容差匹配, 见 normalizeUserSubmissionText 忽略附件行), 避免显示两条。
        val historicalFiltered = removeHistoricalCoveredByPending(historical, pendingUserMessages)
        val cutoff = conversationClearCutoffs[state.value.activeSessionId] ?: 0L
        val deleted = deletedMessageIds[state.value.activeSessionId].orEmpty()
        var messages = mergeMessages(historicalFiltered, pendingUserMessages)
            .filter { it.shouldShowAfterClearCutoff(cutoff) }
            .let { filterDeletedChatMessages(it, deleted) }
            .map { it.withVisibleVoiceMarkersStripped() }
        // 所有场景按时间稳定排序: user(pending, 本地 nowMillis)与 assistant(sseJsonl/snapshot 的后端
        // 时间)来自不同来源, mergeMessages 只按到达顺序拼接, 会出现 agent 回复排在 user 消息前(尤其
        // 附件消息去重后 pending user 被挪到末尾)。按 createdAtMillis 排序让 user/assistant 正确交错;
        // null 视为最大排末尾, 不干扰有序的历史消息。
        messages = messages.sortedBy { it.createdAtMillis ?: Long.MAX_VALUE }
        if (messages.isEmpty()) messages = sampleMessages()
        _state.update { it.copy(messages = messages.takeLast(120)) }
    }

    private fun speakLatestSettledAssistantIfNeeded() {
        notifyLatestSettledAssistantIfNeeded()
        // 进项目钻取 session 时抑制一次自动播报: 把当前最后一条 Momo 记为"已播报",
        // 避免进页就播报历史; 只在用户发消息后的新回复(id 不同)才播报。
        if (suppressInitialAutoSpeech) {
            suppressInitialAutoSpeech = false
            state.value.messages.lastOrNull {
                it.author == MessageAuthor.Momo && it.processType == null &&
                    (it.text.isNotBlank() || !it.voiceText.isNullOrBlank())
            }?.let {
                lastSpokenAssistantId = it.id
                lastSpokenAssistantText = it.text.takeIf { txt -> txt.isNotBlank() } ?: it.voiceText
            }
            return
        }
        if (!state.value.ttsEnabled || state.value.typing || state.value.voiceRecording) return
        // 仅当前停留在该聊天页面时才自动播报, 切到其他页面(通讯录/设置/分身等)不播报
        if (state.value.screen != AppScreen.Home) return
        val pendingVoice = pendingVoiceOnlyText
        if (!pendingVoice.isNullOrBlank()) {
            pendingVoiceOnlyText = null
            speakRawVoice(pendingVoice)
            return
        }
        val message = state.value.messages.lastOrNull {
            it.author == MessageAuthor.Momo && it.processType == null &&
                (it.text.isNotBlank() || !it.voiceText.isNullOrBlank())
        } ?: return
        if (message.id == lastSpokenAssistantId) return
        // 内容去重: 快照轮询会把 SSE 投递的消息整体替换成快照版本(同一条内容但 id 变),
        // 只看 id 会在 ≤2.5s 后的 applySnapshot 里再次触发播报。用文案做第二道屏障。
        val contentKey = message.text.takeIf { it.isNotBlank() } ?: message.voiceText
        if (!contentKey.isNullOrBlank() && contentKey == lastSpokenAssistantText) return
        lastSpokenAssistantText = contentKey
        speakAssistant(message, markAsAutomatic = true)
    }

    private fun notifyLatestSettledAssistantIfNeeded() {
        val current = state.value
        if (!current.pushEnabled || isAppInForeground()) return
        val message = current.messages.lastOrNull {
            it.author == MessageAuthor.Momo &&
                (it.text.isNotBlank() || !it.voiceText.isNullOrBlank())
        } ?: return
        if (message.id == lastNotifiedAssistantId) return
        val body = notificationBody(message)
        if (body.isBlank()) return
        lastNotifiedAssistantId = message.id
        NotificationGateway.show(
            title = "Mobius",
            body = body,
            deepLink = current.activeSessionId.takeIf { it.isNotBlank() }?.let { "momo://chat/$it" },
        )
    }

    private fun speakAssistant(message: ChatMessage, markAsAutomatic: Boolean) {
        val text = assistantSpeechText(message, state.value.ttsPlaybackMode).ifBlank { return }
        launchSpeak(text, markAsAutomaticAs = message.id, markAsAutomatic = markAsAutomatic)
    }

    private fun speakRawVoice(text: String) {
        if (text.isBlank()) return
        val syntheticId = "voice-only-${nowShortTime()}-${text.hashCode()}"
        launchSpeak(text, markAsAutomaticAs = syntheticId, markAsAutomatic = true, speakingId = syntheticId)
    }

    private fun launchSpeak(
        text: String,
        markAsAutomaticAs: String,
        markAsAutomatic: Boolean,
        speakingId: String? = markAsAutomaticAs,
    ) {
        // 统一在播报入口剥离 Markdown: 覆盖自动播报(speakAssistant)、手动点消息播报
        // (speakChatMessage/speakMessageText 用原始 message.text)、语音消息(speakRawVoice)等所有路径,
        // 不把 ** # []() | 等 Markdown 符号读出来。
        val cleaned = stripMarkdownForSpeech(text)
        if (cleaned.isBlank()) return
        // iOS 播报诊断: 自动播报触发时短暂提示(定位"没触发"vs"触发了没声音"); 稳定后移除。
        if (markAsAutomatic && platformIsIOS()) {
            showToast("TTS 触发: ${cleaned.take(12)}…")
        }
        // 自动播报同实际文本不重复触发; 手动播报允许重播但更新此键, 防手动播后自动播同条.
        if (markAsAutomatic && cleaned == lastSpokenCleanedText) return
        currentSpeakJob?.cancel()
        ttsController.stop()
        if (markAsAutomatic) lastSpokenAssistantId = markAsAutomaticAs
        lastSpokenCleanedText = cleaned
        // 点击即进入 loading(取音频/连接中); 引擎真正开始播放(onStart)时清 fetching、置 speaking;
        // 结束(成功/失败/取消)统一在 invokeOnCompletion 清掉本条 speaking/fetching。
        _state.update { it.copy(ttsFetchingMessageId = speakingId, ttsSpeakingMessageId = null) }
        currentSpeakJob = scope.launch {
            val result = ttsController.speak(cleaned, onStart = {
                _state.update {
                    if (it.ttsFetchingMessageId == speakingId)
                        it.copy(ttsFetchingMessageId = null, ttsSpeakingMessageId = speakingId) else it
                }
            })
            if (!result.isSuccess) {
                _state.update { it.copy(ttsSpeakingMessageId = null, ttsFetchingMessageId = null) }
            } else if (_state.value.ttsSpeakingMessageId == speakingId) {
                _state.update { it.copy(ttsSpeakingMessageId = null) }
            }
        }
        currentSpeakJob?.invokeOnCompletion {
            _state.update {
                if (it.ttsSpeakingMessageId == speakingId || it.ttsFetchingMessageId == speakingId)
                    it.copy(ttsSpeakingMessageId = null, ttsFetchingMessageId = null) else it
            }
        }
    }

    private fun clearStreamState(
        keepPendingUsers: Boolean = false,
        keepQueuedMessages: Boolean = false,
    ) {
        sseHistoryMessages = emptyList()
        sseJsonlMessages = emptyList()
        // 切会话/清空时重置播报去重, 避免上一会话的文本误杀新会话的同文回复.
        lastSpokenAssistantId = ""
        lastSpokenAssistantText = null
        lastSpokenCleanedText = ""
        if (!keepPendingUsers) pendingUserMessages = emptyList()
        // 默认清空出站队列(登出/401 等场景 token 已失效, 队列发不出去);
        // 仅切换会话/导航时保留(keepQueuedMessages) — 排队中的消息稍后仍会按序发出, 不能丢。
        val keptQueued = if (keepQueuedMessages) queuedOutboundMessages else emptyList()
        if (!keepQueuedMessages) queuedOutboundMessages = emptyList()
        awaitingAssistantReplySessionId = ""
        pendingVoiceOnlyText = null
        snapshotLoadedForSessionId = ""
        resetPendingStreamUi()
        _state.update {
            it.copy(
                sendingMessage = if (keepQueuedMessages) it.sendingMessage else false,
                isStreaming = false,
                queuedUserMessages = keptQueued.map { queued -> queued.message },
                typing = false,
                streamingProcess = null,
            )
        }
        // 保留队列时尝试继续排空(此前 sendingMessage 可能刚被清)。
        if (keepQueuedMessages && queuedOutboundMessages.isNotEmpty()) drainMessageQueueIfNeeded()
    }

    private fun markAwaitingAssistantReply(sessionId: String) {
        awaitingAssistantReplySessionId = sessionId.takeIf { it.isNotBlank() } ?: AWAITING_ASSISTANT_PENDING_SESSION
    }

    private fun clearAwaitingAssistantReply(sessionId: String? = null) {
        val awaited = awaitingAssistantReplySessionId
        if (
            sessionId == null ||
            awaited == AWAITING_ASSISTANT_PENDING_SESSION ||
            sessionId.isBlank() ||
            awaited == sessionId
        ) {
            awaitingAssistantReplySessionId = ""
        }
    }

    private fun shouldKeepWaitingForAssistant(sessionId: String, messages: List<ChatMessage>): Boolean {
        val awaited = awaitingAssistantReplySessionId
        if (awaited.isBlank()) return false
        val sessionMatches = awaited == AWAITING_ASSISTANT_PENDING_SESSION || (sessionId.isNotBlank() && awaited == sessionId)
        return sessionMatches && !messages.hasAssistantAfterLatestUser(queuedUserMessageIds())
    }

    /** 仍在出站队列(尚未真正发给服务端)的 user 消息 id 集合。 */
    private fun queuedUserMessageIds(): Set<String> =
        queuedOutboundMessages.map { it.message.id }.toSet()

    private fun startSnapshotPolling(sessionId: String) {
        snapshotPollJob?.cancel()
        snapshotPollJob = scope.launch {
            repeat(48) {
                delay(2_500L)
                if (state.value.screen == AppScreen.Login || state.value.activeSessionId != sessionId) return@launch
                val snapshot = runCatching { api.assistantSnapshot(sessionId) }.getOrNull() ?: return@repeat
                applySnapshot(snapshot, allowAutoSpeech = true)
                if (!snapshot.status.working &&
                    snapshot.messages.hasAssistantAfterLatestUser(queuedUserMessageIds())
                ) return@launch
            }
        }
    }

    private fun chunkToMessage(chunk: StreamTextChunk): ChatMessage =
        ChatMessage(
            id = chunk.id,
            author = chunk.author,
            text = chunk.text,
            time = chunk.time,
            voiceText = chunk.voiceText,
            createdAtMillis = chunk.createdAtMillis,
            processType = chunk.processType,
            processLabel = chunk.processLabel,
        )

    // 压缩上文: 发 /compact 指令(分身走 sendSessionMessage, 主小莫走 sendAssistantMessage)。
    // 触发 agent 压缩当前会话上下文。UI 层弹确认后调用。
    fun compactContext() {
        val sessionId = state.value.activeSessionId
        if (sessionId.isBlank()) {
            showToast("当前没有可压缩的会话")
            return
        }
        scope.launch {
            runCatching { sendCompactCommand(sessionId) }
                .onFailure { showToast(it.message ?: "压缩上文失败") }
        }
    }

    private suspend fun sendCompactCommand(sessionId: String) {
        val activeSession = state.value.clones.firstOrNull { it.sessionId == sessionId }
        if (activeSession != null && !activeSession.isMainAssistant()) {
            api.sendSessionMessage(
                sessionId = sessionId,
                content = "/compact",
                requestId = "momo-mobile-compact-${nowEpochMillis()}",
            )
        } else {
            api.sendAssistantMessage(content = "/compact", route = "/mobile")
        }
    }

    private fun loadConversationClearCutoffs(userId: String?) {
        val key = conversationClearCutoffStorageKey(userId)
        conversationClearCutoffs = storage.getPreference(key)
            ?.let { raw -> runCatching { localJson.decodeFromString<Map<String, Long>>(raw) }.getOrNull() }
            ?: emptyMap()
    }

    private fun saveConversationClearCutoffs() {
        val key = conversationClearCutoffStorageKey(state.value.user?.id)
        if (key.isBlank()) return
        storage.savePreference(key, localJson.encodeToString(conversationClearCutoffs))
    }

    private fun loadDeletedState(userId: String?) {
        deletedMessageIds = storage.getPreference(deletedMessageIdsStorageKey(userId))
            ?.let { raw -> runCatching { localJson.decodeFromString<Map<String, Set<String>>>(raw) }.getOrNull() }
            ?: emptyMap()
        deletedGroupMessageIds = storage.getPreference(deletedGroupMessageIdsStorageKey(userId))
            ?.let { raw -> runCatching { localJson.decodeFromString<Map<String, Set<Long>>>(raw) }.getOrNull() }
            ?: emptyMap()
        groupClearCutoffs = storage.getPreference(groupClearCutoffsStorageKey(userId))
            ?.let { raw -> runCatching { localJson.decodeFromString<Map<String, Long>>(raw) }.getOrNull() }
            ?: emptyMap()
    }

    private fun saveDeletedMessageIds() {
        val key = deletedMessageIdsStorageKey(state.value.user?.id)
        if (key.isBlank()) return
        storage.savePreference(key, localJson.encodeToString(deletedMessageIds))
    }

    private fun saveDeletedGroupMessageIds() {
        val key = deletedGroupMessageIdsStorageKey(state.value.user?.id)
        if (key.isBlank()) return
        storage.savePreference(key, localJson.encodeToString(deletedGroupMessageIds))
    }

    private fun saveGroupClearCutoffs() {
        val key = groupClearCutoffsStorageKey(state.value.user?.id)
        if (key.isBlank()) return
        storage.savePreference(key, localJson.encodeToString(groupClearCutoffs))
    }

    // 群消息按「已删除 id + 清空截止点」过滤: SSE history 回灌与增量 message 都走这里.
    private fun filterGroupMessages(
        conversationId: String,
        messages: List<ConversationMessage>,
    ): List<ConversationMessage> {
        val cutoff = groupClearCutoffs[conversationId] ?: 0L
        val deleted = deletedGroupMessageIds[conversationId].orEmpty()
        return messages.filter { shouldShowGroupMessage(cutoff, deleted, it) }
    }

    private fun shouldShowGroupMessage(conversationId: String, message: ConversationMessage): Boolean {
        val cutoff = groupClearCutoffs[conversationId] ?: 0L
        val deleted = deletedGroupMessageIds[conversationId].orEmpty()
        return shouldShowGroupMessage(cutoff, deleted, message)
    }

    private fun shouldShowGroupMessage(cutoff: Long, deleted: Set<Long>, message: ConversationMessage): Boolean =
        shouldShowGroupMessageLocally(cutoff, deleted, message)

    private fun showToast(message: String) {
        val text = humanizeToast(message) ?: return // 协程取消等噪声直接不弹
        _state.update { it.copy(toast = text) }
        scheduleToastClear(text)
    }

    private fun scheduleToastClear(message: String) {
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(3_000L)
            _state.update { if (it.toast == message) it.copy(toast = null) else it }
        }
    }

    private fun createApi(baseUrl: String): MobiusApi =
        MobiusApi(
            baseUrl = baseUrl,
            storage = storage,
            onUnauthorized = { logoutFrom401() },
        )
}

private const val THEME_MODE_KEY = "theme_mode"
private const val THEME_PALETTE_KEY = "theme_palette"
private const val CHAT_LIST_SWIPE_HINT_DISMISSED_KEY = "chat_list_swipe_hint_dismissed"
private const val ASSISTANT_CLEAR_STORAGE_PREFIX = "assistant-clear-cutoffs"
private const val ASSISTANT_DELETED_MSGS_STORAGE_PREFIX = "assistant-deleted-msgs"
private const val GROUP_DELETED_MSGS_STORAGE_PREFIX = "group-deleted-msgs"
private const val GROUP_CLEAR_CUTOFFS_STORAGE_PREFIX = "group-clear-cutoffs"
private const val STREAM_UI_UPDATE_INTERVAL_MS = 100L
// 项目钻取 session 无 assistantSnapshot 的 working 状态、SSE 也常无 typing 事件;
// 用"最近一次 onChunk 时间"判断流式是否仍在进行, 收到 onChunk 后这段时间内保持 loading,
// 直到流停顿(无新 onChunk)再让 loading 消失——避免多段回复时 loading 在首段后就提前消失。
private const val PROJECT_STREAM_IDLE_TIMEOUT_MS = 4000L
private const val MAX_INLINE_IMAGE_PREVIEW_BYTES = 4 * 1024 * 1024
private const val AWAITING_ASSISTANT_PENDING_SESSION = "__pending_assistant_session__"
private const val PENDING_USER_BACKDATE_TOLERANCE_MS = 30_000L
private const val PENDING_USER_FORWARD_TOLERANCE_MS = 5 * 60_000L

private fun conversationClearCutoffStorageKey(userId: String?): String =
    userId?.takeIf { it.isNotBlank() }?.let { "$ASSISTANT_CLEAR_STORAGE_PREFIX:$it" }.orEmpty()

private fun deletedMessageIdsStorageKey(userId: String?): String =
    userId?.takeIf { it.isNotBlank() }?.let { "$ASSISTANT_DELETED_MSGS_STORAGE_PREFIX:$it" }.orEmpty()

private fun deletedGroupMessageIdsStorageKey(userId: String?): String =
    userId?.takeIf { it.isNotBlank() }?.let { "$GROUP_DELETED_MSGS_STORAGE_PREFIX:$it" }.orEmpty()

private fun groupClearCutoffsStorageKey(userId: String?): String =
    userId?.takeIf { it.isNotBlank() }?.let { "$GROUP_CLEAR_CUTOFFS_STORAGE_PREFIX:$it" }.orEmpty()

private fun ChatMessage.withVisibleVoiceMarkersStripped(): ChatMessage {
    if (author != MessageAuthor.Momo) return this
    val visibleText = stripVoiceMarkers(text)
    return if (visibleText == text) this else copy(text = visibleText)
}

private fun appendMessage(messages: List<ChatMessage>, message: ChatMessage): List<ChatMessage> {
    if (message.text.isBlank()) return messages
    if (messages.any { it.id == message.id }) return messages
    return (messages + message).takeLast(120)
}

internal fun removePendingMessagesCoveredByHistory(
    pending: List<ChatMessage>,
    historical: List<ChatMessage>,
): List<ChatMessage> {
    if (pending.isEmpty() || historical.isEmpty()) return pending
    val matchedHistoricalIndexes = mutableSetOf<Int>()
    return pending.filter { pendingMessage ->
        val matchedIndex = historical.indexOfFirstUnmatched(matchedHistoricalIndexes) { historicalMessage ->
            messagesRepresentSameUserSubmission(historicalMessage, pendingMessage)
        }
        if (matchedIndex >= 0) {
            matchedHistoricalIndexes += matchedIndex
            false
        } else {
            true
        }
    }
}

// 反向去重: 保留 pending(带附件行+图片预览), 从 historical 移除被 pending 覆盖的同一条 user 消息。
// 用于附件消息——后端 historical user 文本只有原文, pending 额外带"附件：name"(图片关联), 文本不同;
// 按 normalizeUserSubmissionText(忽略附件行)+时间容差匹配, 移除 historical 避免显示两条, 同时保住 pending 的附件展示。
internal fun removeHistoricalCoveredByPending(
    historical: List<ChatMessage>,
    pending: List<ChatMessage>,
): List<ChatMessage> {
    if (pending.isEmpty() || historical.isEmpty()) return historical
    val matchedPendingIndexes = mutableSetOf<Int>()
    return historical.filter { historicalMessage ->
        if (historicalMessage.author != MessageAuthor.User) return@filter true
        val matchedIndex = pending.indexOfFirstUnmatched(matchedPendingIndexes) { pendingMessage ->
            messagesRepresentSameUserSubmission(historicalMessage, pendingMessage)
        }
        if (matchedIndex >= 0) {
            matchedPendingIndexes += matchedIndex
            false
        } else {
            true
        }
    }
}

private fun List<ChatMessage>.indexOfFirstUnmatched(
    matchedIndexes: Set<Int>,
    predicate: (ChatMessage) -> Boolean,
): Int {
    for (index in indices) {
        if (index !in matchedIndexes && predicate(this[index])) return index
    }
    return -1
}

private fun messagesRepresentSameUserSubmission(historical: ChatMessage, pending: ChatMessage): Boolean {
    if (historical.id.isNotBlank() && historical.id == pending.id) return true
    if (historical.author != MessageAuthor.User || pending.author != MessageAuthor.User) return false
    val historicalText = normalizeUserSubmissionText(historical.text)
    val pendingText = normalizeUserSubmissionText(pending.text)
    if (historicalText.isBlank() || historicalText != pendingText) return false

    val historicalMillis = historical.createdAtMillis
    val pendingMillis = pending.createdAtMillis
    if (historicalMillis != null && pendingMillis != null) {
        return historicalMillis >= pendingMillis - PENDING_USER_BACKDATE_TOLERANCE_MS &&
            historicalMillis <= pendingMillis + PENDING_USER_FORWARD_TOLERANCE_MS
    }

    val historicalTime = historical.time.trim()
    val pendingTime = pending.time.trim()
    return historicalTime.isNotBlank() && historicalTime == pendingTime
}

private fun normalizeUserSubmissionText(value: String): String =
    // 忽略客户端为展示/图片预览追加的"附件：xxx"行(后端 historical user 文本不带这行),
    // 只按用户实际输入的原文做去重比较, 否则附件消息会因文本不一致而去重失败、显示两条。
    value.lineSequence()
        .filterNot { it.trim().startsWith("附件：") || it.trim().startsWith("附件:") }
        .joinToString("\n")
        .trim()
        .replace(Regex("""\s+"""), " ")

private fun notificationBody(message: ChatMessage): String {
    val raw = message.voiceText?.takeIf { it.isNotBlank() } ?: message.text
    val normalized = stripVoiceMarkers(raw).replace(Regex("""\s+"""), " ").trim()
    return if (normalized.length <= 60) normalized else normalized.take(60) + "…"
}

private fun mergeMessages(prefix: List<ChatMessage>, history: List<ChatMessage>): List<ChatMessage> {
    return (prefix + history).fold(emptyList()) { acc, item -> appendMessage(acc, item) }
}

private fun upsertMessage(messages: List<ChatMessage>, message: ChatMessage): List<ChatMessage> {
    if (message.text.isBlank()) return messages
    val index = messages.indexOfFirst { it.id == message.id }
    return if (index >= 0) {
        messages.toMutableList().also { it[index] = message }
    } else {
        appendMessage(messages, message)
    }
}

internal fun canAttemptTokenRestore(
    baseUrl: String,
    token: String?,
    metadata: StoredTokenMetadata?,
    nowEpochMillis: Long,
): Boolean {
    if (baseUrl.isBlank() || token.isNullOrBlank()) return false
    val saved = metadata ?: return false
    if (saved.storageVersion != TOKEN_STORAGE_VERSION) return false
    if (!sameRestoreBaseUrl(baseUrl, saved.baseUrl)) return false
    val age = nowEpochMillis - saved.savedAtEpochMillis
    return age in 0L..TOKEN_MAX_AGE_MILLIS
}

private fun sameRestoreBaseUrl(current: String, saved: String): Boolean =
    current.trim().trimEnd('/') == saved.trim().trimEnd('/')

internal fun assistantSpeechText(message: ChatMessage, mode: TtsPlaybackMode): String {
    val visibleText = stripMarkdownForSpeech(stripVoiceMarkers(message.text))
    if (mode != TtsPlaybackMode.Selected) return visibleText
    return message.voiceText
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { stripMarkdownForSpeech(it) }
        ?: selectedTtsFallbackText(visibleText)
}

// 语音播报剥离 Markdown 标记: 只读纯文案, 不把 ** * ` # []() | 等 Markdown 符号读出来。
// (stripVoiceMarkers 只去 voice 标记; 行内 **加粗** 的星号等会被 TTS 读成"星号", 这里统一剥掉。)
private fun stripMarkdownForSpeech(text: String): String {
    var t = text
    // 代码块围栏 ```lang / ```
    t = t.replace(Regex("(?m)^```[^\\n]*$"), "")
    // 图片 ![alt](url) → alt; 链接 [文字](url) → 文字
    t = t.replace(Regex("""!\[([^\]]*)\]\([^)]*\)"""), "$1")
    t = t.replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")
    // 加粗 / 删除线 / 行内代码 的成对符号
    t = t.replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
    // 行首标记: 标题 / 无序列表 / 有序列表 / 引用 / 分隔线
    t = t.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
    t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
    t = t.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
    t = t.replace(Regex("(?m)^\\s*>+\\s*"), "")
    t = t.replace(Regex("(?m)^\\s*[-*_=]{3,}\\s*$"), "")
    // 剩余成对单星号/下划线(斜体): *x* / _x_
    t = t.replace(Regex("""\*([^*\n]+)\*"""), "$1")
    t = t.replace(Regex("""_([^_\n]+)_"""), "$1")
    // 表格竖线 → 空格
    t = t.replace("|", " ")
    // 压缩多余空白
    t = t.replace(Regex("[ \t]{2,}"), " ")
    return t.trim()
}

private fun selectedTtsFallbackText(visibleText: String): String {
    val paragraph = visibleText
        .split(Regex("""\n[ \t]*\n+"""))
        .firstOrNull { it.isNotBlank() }
        ?: visibleText
    val compact = paragraph
        .lines()
        .map { line ->
            line.trim()
                .trimStart('#', '-', '*', '+', '>', ' ', '\t')
                .trim()
        }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .trim()
    if (compact.isBlank()) return ""
    val sentenceEnd = compact.indexOfFirst { it in SELECTED_TTS_SENTENCE_ENDINGS }
    val firstSentence = if (sentenceEnd >= 0) compact.take(sentenceEnd + 1) else compact
    return firstSentence.trim().limitSelectedTtsLength()
}

private fun String.limitSelectedTtsLength(): String =
    if (length <= SELECTED_TTS_FALLBACK_MAX_CHARS) {
        this
    } else {
        take(SELECTED_TTS_FALLBACK_MAX_CHARS).trimEnd() + "..."
    }

// 1v1 消息清空截止点过滤: cutoff>0 时早于它的消息隐藏(无时间戳的也隐藏); /compact 指令始终隐藏.
// clearSessionMessages/clearActiveConversation 设截止点后, rebuildMessages 调用此函数过滤旧消息.
internal fun ChatMessage.shouldShowAfterClearCutoff(cutoff: Long): Boolean {
    if (author == MessageAuthor.User && text.trim().startsWith("/compact")) return false
    if (cutoff <= 0L) return true
    // 时间解析失败(createdAtMillis=null, 如 iOS 对非标准格式)的消息保守显示 —
    // 原来直接隐藏, 平台时间解析差异会造成"消息不显示"。
    return (createdAtMillis ?: return true) > cutoff
}

// 1v1 消息本地删除过滤: 命中已删除 id 的消息隐藏. rebuildMessages 调用, 跨重连保持隐藏.
internal fun filterDeletedChatMessages(
    messages: List<ChatMessage>,
    deletedIds: Set<String>,
): List<ChatMessage> {
    if (deletedIds.isEmpty()) return messages
    return messages.filterNot { it.id in deletedIds }
}

// 群消息本地可见性判定: 已删除 id 隐藏; 清空截止点之前(可解析 ISO 时间)隐藏;
// 不可解析时间(如本地乐观消息的 "HH:mm")一律保留, 避免新发的乐观消息被清空截止点误隐藏.
internal fun shouldShowGroupMessageLocally(
    cutoff: Long,
    deletedIds: Set<Long>,
    message: ConversationMessage,
): Boolean {
    if (message.id != 0L && message.id in deletedIds) return false
    if (cutoff <= 0L) return true
    val millis = parseBackendTimeMillis(message.createdAt) ?: return true
    return millis > cutoff
}

private fun combineVoiceText(current: String?, next: String): String? {    val piece = next.trim()
    if (piece.isBlank()) return current
    if (current.isNullOrBlank()) return piece
    if (piece == current || current.endsWith(piece)) return current
    if (current.startsWith(piece)) return current
    return current + "\n" + piece
}

internal fun List<ChatMessage>.hasAssistantAfterLatestUser(
    unsentUserIds: Set<String> = emptySet(),
): Boolean {
    // 计算"最后一条 user"时跳过仍在出站队列里的消息: 那些消息尚未真正发给服务端,
    // 不可能已有回复。否则排队中的 B 会被当成"最后一个提问者" → 误判 A 的轮次"没有回复"
    // → typing 永久保持 → 队列互锁(与 drainMessageQueueIfNeeded 的旧 isStreaming 守卫互为死锁环)。
    val latestUserIndex = indexOfLast { it.author == MessageAuthor.User && it.id !in unsentUserIds }
    // 只统计"真正的文本回复"，忽略过程条目(thinking/tool_call 等 processType!=null)。
    // 否则 agent 刚发出过程条目时就被误判为"已回复"，导致 typing/loading 指示器提前关闭。
    fun isRealAssistantReply(m: ChatMessage) = m.author == MessageAuthor.Momo && m.processType == null
    if (latestUserIndex < 0) return any { isRealAssistantReply(it) }
    return drop(latestUserIndex + 1).any { isRealAssistantReply(it) }
}

private fun upsertSession(sessions: List<Session>, session: Session): List<Session> {
    if (session.sessionId.isBlank()) return sessions
    val index = sessions.indexOfFirst { it.sessionId == session.sessionId }
    return if (index >= 0) {
        sessions.toMutableList().also { it[index] = session }
    } else {
        sessions + session
    }
}

private fun Session.isMainAssistant(): Boolean =
    assistantRole == "main" ||
        name == "我的主 Mobius" || name.contains("主 Mobius") ||
        name == "我的主小莫" || name.contains("主小莫")

// 群消息按 id 去重 upsert：id 为 0（后端未给 id）的视为不可去重，直接追加。
// 把英文异常文案翻译成中文 toast；协程取消等噪声返回 null（不弹）。
private fun humanizeToast(raw: String): String? {
    val m = raw.trim()
    if (m.isBlank()) return null
    val lower = m.lowercase()
    // 协程/任务取消属于正常情况，静默不弹。
    if (lower.contains("coroutine") && lower.contains("cancel")) return null
    if (lower.contains("job was cancelled") || lower.contains("cancellationexception")) return null
    if (lower.contains("standalonecoroutine")) return null
    if (lower == "null") return null
    return when {
        lower.contains("timeout") || lower.contains("timed out") -> "请求超时，请重试"
        lower.contains("unable to resolve host") || lower.contains("unknown host") -> "无法连接服务器，请检查网络"
        lower.contains("connection refused") -> "服务器拒绝连接"
        lower.contains("connection reset") || lower.contains("reset by peer") -> "网络连接中断，请重试"
        lower.contains("unauthorized") || lower.contains("status 401") || lower.endsWith("401") -> "登录已过期，请重新登录"
        lower.contains("forbidden") || lower.contains("status 403") -> "没有权限执行此操作"
        lower.contains("not found") || lower.contains("status 404") -> "请求的资源不存在"
        lower.contains("socket") -> "网络连接异常，请重试"
        // 已是中文则原样返回
        m.any { it.code in 0x4E00..0x9FFF } -> m
        // 未识别的英文异常(常见于后台切前台时的瞬时网络抖动/SSE 重连)没有可操作信息，静默不弹。
        else -> null
    }
}

private fun dedupMessages(messages: List<ConversationMessage>): List<ConversationMessage> {
    val seen = HashSet<Long>()
    val result = mutableListOf<ConversationMessage>()
    for (msg in messages) {
        if (msg.id == 0L || seen.add(msg.id)) result.add(msg)
    }
    return result
}

// 群历史 + 中继覆盖层合并：按 id 去重，且按 (senderId+content) 去重。后者用于把"后端已落库的
// agent 回复"与"覆盖层里同内容的回复"合并成一条(history 在前优先保留)，重启后既不丢也不重复。
private fun mergeGroupHistory(history: List<ConversationMessage>, pending: List<ConversationMessage>): List<ConversationMessage> {
    val byId = HashSet<Long>()
    val byContent = HashSet<String>()
    val out = mutableListOf<ConversationMessage>()
    for (m in history + pending) {
        val idOk = m.id == 0L || byId.add(m.id)
        val contentKey = m.senderId + " " + m.content
        val contentOk = m.content.isBlank() || byContent.add(contentKey)
        if (idOk && contentOk) out.add(m)
    }
    return out
}

private fun appendGroupMessage(
    messages: List<ConversationMessage>,
    message: ConversationMessage,
): List<ConversationMessage> {
    if (message.id != 0L && messages.any { it.id == message.id }) {
        return messages.map { if (it.id == message.id) message else it }
    }
    return messages + message
}

private const val GROUP_TYPING_AGENT_TIMEOUT_MS = 90_000L

// 群消息按 created_at(解析为毫秒)稳定排序; 解析失败的(空 / 旧 HH:mm 格式)保持原相对顺序排在最前。
// 修复"消息顺序不一致 / 时间倒置"——后端历史(ISO)与客户端乐观/错误消息(ISO)都按真实时间递增显示,
// 不再因 SSE / 重连 / 覆盖层的到达先后而错乱。
private fun sortGroupMessages(messages: List<ConversationMessage>): List<ConversationMessage> {
    return messages
        .mapIndexed { idx, m -> Triple(idx, m, parseBackendTimeMillis(m.createdAt)) }
        .sortedWith(compareBy({ it.third ?: Long.MIN_VALUE }, { it.first }))
        .map { it.second }
}

private const val SELECTED_TTS_FALLBACK_MAX_CHARS = 120
private const val SELECTED_TTS_SENTENCE_ENDINGS = "。！？!?；;"
