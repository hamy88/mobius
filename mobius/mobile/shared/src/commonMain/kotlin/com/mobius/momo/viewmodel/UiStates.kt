package com.mobius.momo.viewmodel

import com.mobius.momo.data.ServerEntry
import com.mobius.momo.data.TtsPlaybackMode
import com.mobius.momo.data.Voice
import com.mobius.momo.domain.ChatMessage
import com.mobius.momo.domain.MessageAuthor
import com.mobius.momo.domain.Session
import com.mobius.momo.domain.Turn
import com.mobius.momo.domain.SessionModelOption
import com.mobius.momo.domain.SessionPersonalityOption
import com.mobius.momo.domain.User

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val turns: List<Turn> = emptyList(),
    val compactMode: Boolean = false,  // 项目钻取 session: 无头像, 用缩进区分
    val clones: List<Session> = emptyList(),
    val activeSessionId: String = "",
    val activeSessionTitle: String = "",
    val activeSessionStatus: String = "",
    val activeSessionModelLabel: String? = null,
    val typing: Boolean = false,
    val isStreaming: Boolean = false,
    val queuedUserMessages: List<ChatMessage> = emptyList(),
    val menuOpen: Boolean = false,
    val streamingProcess: String? = null,
    val ttsSpeakingMessageId: String? = null,
    val ttsFetchingMessageId: String? = null,
)

data class ComposerUiState(
    val input: String = "",
    val composerInputMode: ComposerInputMode = ComposerInputMode.Text,
    val attachments: List<PendingAttachment> = emptyList(),
    val sendingMessage: Boolean = false,
    val speechPermissionGranted: Boolean = false,
    val speechPermissionDenied: Boolean = false,
    val voiceRecording: Boolean = false,
    val voiceTranscribing: Boolean = false,
    val voiceCanceling: Boolean = false,
    val voiceTranscript: String = "",
    val voiceVolumeLevel: Int = 0,
)

data class AuthState(
    val screen: AppScreen = AppScreen.Login,
    val loginStep: LoginStep = LoginStep.Username,
    val username: String = "",
    val password: String = "",
    val user: User? = null,
    val loading: Boolean = false,
    val toast: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val themePalette: ThemePalette = ThemePalette.Default,
    val cloneSheetOpen: Boolean = false,
    val presetSheetOpen: Boolean = false,
    val passwordRequired: Boolean = false,
    val serverBaseUrl: String = "",
    val serverEntries: List<ServerEntry> = emptyList(),
    val pushEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val ttsPlaybackMode: TtsPlaybackMode = TtsPlaybackMode.All,
    val availableVoices: List<Voice> = emptyList(),
    val voicesLoading: Boolean = false,
    val voicesLoadFailed: Boolean = false,
    val selectedVoice: String = "",
    val ttsSpeakingMessageId: String? = null,
    val presetLoading: Boolean = false,
    val presetSaving: Boolean = false,
    val presetError: String = "",
    val presetConfirmDelete: Boolean = false,
    val presetConfirmSessionName: String = "",
    val presetPersonalityOptions: List<SessionPersonalityOption> = emptyList(),
    val cloneModelOptions: List<SessionModelOption> = emptyList(),
)

fun UiState.toChatState(): ChatState = ChatState(
    messages = messages,
    turns = buildTurns(messages, typing),
    compactMode = chatReturnTarget == AppScreen.IssueSessions,
    clones = clones,
    activeSessionId = activeSessionId,
    activeSessionTitle = activeSessionTitle,
    activeSessionStatus = activeSessionStatus,
    activeSessionModelLabel = activeSessionModelLabel,
    typing = typing,
    isStreaming = isStreaming,
    queuedUserMessages = queuedUserMessages,
    menuOpen = menuOpen,
    streamingProcess = streamingProcess,
    ttsSpeakingMessageId = ttsSpeakingMessageId,
    ttsFetchingMessageId = ttsFetchingMessageId,
)

/**
 * 从 flat 消息列表扫描 User 消息作为轮次边界, 派生出 Turn 列表供 UI 分组渲染.
 *
 * 配对规则: 一条 user 消息开启一个 turn, 其后到下一条 user 之前的所有 assistant 消息
 * (文本 + 过程条目)都归属该 turn —— 这正是"用户消息 ↔ agent 回复"的对应关系。
 * 连续多条 user 消息(用户没等回复就连发)不拆成两个 turn: 拆开会让第一条 user 的
 * turn 空 body(回复全跑到第二条), 折叠展开后"问题和回复对不上"。
 * 处理办法: 连续 user 归入同一 turn, header 取第一条(最早的提问), 其余作为
 * assistantItems 前置展示(保序不丢内容)。
 */
internal fun buildTurns(messages: List<ChatMessage>, isStreaming: Boolean): List<Turn> {
    if (messages.isEmpty()) return emptyList()
    val turns = mutableListOf<Turn>()
    var currentUser: ChatMessage? = null
    var pendingExtraUsers = mutableListOf<ChatMessage>() // 同 turn 内第 2+ 条 user, 前置进 body
    val currentItems = mutableListOf<ChatMessage>()

    fun flushTurn(streaming: Boolean = false) {
        if (currentUser == null && currentItems.isEmpty()) return
        // 连发补充的 user 消息排在回复前, 保持阅读顺序: 问题(们) → 回复
        turns.add(Turn(currentUser, pendingExtraUsers + currentItems, isStreaming = streaming))
        currentUser = null
        pendingExtraUsers = mutableListOf()
        currentItems.clear()
    }

    for (msg in messages) {
        if (msg.author == MessageAuthor.User) {
            if (currentUser == null) {
                // 本 turn 的首条 user → 作为 header
                currentUser = msg
            } else if (currentItems.isEmpty()) {
                // 还没出现任何回复, 又来一条 user → 视为同一轮的补充提问(不拆轮)
                pendingExtraUsers.add(msg)
            } else {
                // 已有回复后的新提问 → 新一轮
                flushTurn()
                currentUser = msg
            }
        } else {
            currentItems.add(msg)
        }
    }
    flushTurn(streaming = isStreaming)
    return turns
}

fun UiState.toComposerUiState(): ComposerUiState = ComposerUiState(
    input = input,
    composerInputMode = composerInputMode,
    attachments = attachments,
    sendingMessage = sendingMessage,
    speechPermissionGranted = speechPermissionGranted,
    speechPermissionDenied = speechPermissionDenied,
    voiceRecording = voiceRecording,
    voiceTranscribing = voiceTranscribing,
    voiceCanceling = voiceCanceling,
    voiceTranscript = voiceTranscript,
    voiceVolumeLevel = voiceVolumeLevel,
)

fun UiState.toAuthState(): AuthState = AuthState(
    screen = screen,
    loginStep = loginStep,
    username = username,
    password = password,
    user = user,
    loading = loading,
    toast = toast,
    themeMode = themeMode,
    themePalette = themePalette,
    cloneSheetOpen = cloneSheetOpen,
    presetSheetOpen = presetSheetOpen,
    passwordRequired = passwordRequired,
    serverBaseUrl = serverBaseUrl,
    serverEntries = serverEntries,
    pushEnabled = pushEnabled,
    ttsEnabled = ttsEnabled,
    ttsPlaybackMode = ttsPlaybackMode,
    availableVoices = availableVoices,
    voicesLoading = voicesLoading,
    voicesLoadFailed = voicesLoadFailed,
    selectedVoice = selectedVoice,
    ttsSpeakingMessageId = ttsSpeakingMessageId,
    presetLoading = presetLoading,
    presetSaving = presetSaving,
    presetError = presetError,
    presetConfirmDelete = presetConfirmDelete,
    presetConfirmSessionName = presetConfirmSessionName,
    presetPersonalityOptions = presetPersonalityOptions,
    cloneModelOptions = cloneModelOptions,
)
