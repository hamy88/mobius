@file:OptIn(
    org.jetbrains.compose.resources.ExperimentalResourceApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    // combinedClickable(0.2.0 聊天长按选取复制)属 ExperimentalFoundationApi。
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.mobius.momo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberSwipeToDismissBoxState
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.mobius.momo.data.TtsPlaybackMode
import com.mobius.momo.data.TTS_SYSTEM_VOICE_ID
import com.mobius.momo.data.RECOMMENDED_MOBIUS_BASE_URL
import com.mobius.momo.data.ServerEntry
import com.mobius.momo.data.Voice
import com.mobius.momo.domain.ChatMessage
import com.mobius.momo.data.formatBackendTime
import com.mobius.momo.data.nowEpochMillis
import com.mobius.momo.data.parseBackendTimeMillis
import com.mobius.momo.data.platformAppVersion
import com.mobius.momo.data.platformBottomTabPaddingDp
import com.mobius.momo.domain.ConversationDetail
import com.mobius.momo.domain.ConversationMember
import com.mobius.momo.domain.ConversationMemberInput
import com.mobius.momo.domain.ConversationMessage
import com.mobius.momo.domain.ConversationSummary
import com.mobius.momo.domain.Issue
import com.mobius.momo.domain.LiteSessionEntry
import com.mobius.momo.domain.MessageAuthor
import com.mobius.momo.domain.Project
import com.mobius.momo.domain.Session
import com.mobius.momo.domain.UserDirectoryEntry
import com.mobius.momo.viewmodel.AppScreen
import com.mobius.momo.viewmodel.AuthState
import com.mobius.momo.viewmodel.AttachmentStatus
import com.mobius.momo.viewmodel.ChatState
import com.mobius.momo.viewmodel.ComposerUiState
import com.mobius.momo.viewmodel.ComposerInputMode
import com.mobius.momo.viewmodel.MomoAppViewModel
import com.mobius.momo.viewmodel.ThemeMode
import com.mobius.momo.viewmodel.ThemePalette
import com.mobius.momo.viewmodel.UiState
import com.mobius.momo.viewmodel.canSendComposerMessage
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.painterResource
import momo_mobile.shared.generated.resources.Res
import momo_mobile.shared.generated.resources.ic_contacts

@Immutable
data class MomoTheme(
    val themeMode: ThemeMode,
    val palette: ThemePalette,
    val dark: Boolean,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val bgPrimary: Color,
    val bgSecondary: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val borderDefault: Color,
    val inputBg: Color,
    val bubbleBg: Color,
    val bubbleMomo: Color,
    val danger: Color,
    val success: Color,
)

private object MomoCorners {
    val card = 20.dp
    val medium = 12.dp
    val button = 14.dp
    val bubble = 18.dp
    val bubbleTail = 6.dp
    val chip = 6.dp
    val icon = 3.dp
    val pill = 24.dp
    val input = 10.dp
}

private object MomoSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

// 阴影层级: 用 elevation 半径区分视觉"浮起感"(浅色模式生效, 深色靠边框)。
private object MomoElevation {
    val card = 8.dp      // 卡片/列表行
    val menu = 16.dp     // 菜单/弹窗
    val sheet = 24.dp    // 底部弹窗
}

// 动效时长统一 (避免 150/200/220/250ms 碎片化)。
private object MomoMotion {
    val fast = 150      // 按压反馈
    val normal = 200    // 入场/过渡
    val slow = 250      // 折叠/展开
}

private object MomoTypography {
    val largeTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.02f).sp)
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.01f).sp)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.W600)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.W400, lineHeight = 22.sp)
    val subheadline = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W400)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W400, letterSpacing = 0.05f.sp)
    val sectionHeader = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W600, letterSpacing = 0.1f.sp)
}

// Hypergrid 等宽数据层: 时间戳/状态标签/统计数字用等宽小字(科技感排版核心)。
internal fun momoMonoStyle(size: Int = 10, color: Color, weight: FontWeight = FontWeight.Medium): TextStyle =
    TextStyle(
        fontSize = size.sp,
        lineHeight = (size + 4).sp,
        fontWeight = weight,
        fontFamily = FontFamily.Monospace,
        color = color,
    )

// Hypergrid 点阵背景纹理(极淡): Modifier 扩展, 挂在任意容器 background 之后。
internal fun Modifier.hypergridDots(theme: MomoTheme): Modifier = this.drawBehind {
    val step = 28.dp.toPx()
    val r = 0.7.dp.toPx()
    val alpha = if (theme.dark) 0.30f else 0.50f
    val dotColor = if (theme.dark) Color(0xFF3A3F52) else theme.borderDefault
    var yy = 0f
    while (yy < size.height) {
        var xx = 0f
        while (xx < size.width) {
            drawCircle(dotColor.copy(alpha = alpha), radius = r, center = Offset(xx, yy))
            xx += step
        }
        yy += step
    }
}

private fun momoTextStyle(style: TextStyle): TextStyle {
    val fontFamily = platformFontFamily()
    return if (fontFamily == null) style else style.copy(fontFamily = fontFamily)
}

@Composable
fun MomoApp(viewModel: MomoAppViewModel = remember { MomoAppViewModel() }) {
    val authState by viewModel.authState.collectAsState()
    val chatState by viewModel.chatState.collectAsState()
    val composerState by viewModel.composerState.collectAsState()
    // 登录成功后处理"点击通知时还未登录"而暂存的 deepLink, 进入对应聊天。
    LaunchedEffect(authState.screen) {
        if (authState.screen != AppScreen.Login) viewModel.consumePendingDeepLink()
    }
    val systemDark = isSystemInDarkTheme()
    val dark = when (authState.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val theme = momoTheme(authState.themeMode, authState.themePalette, dark)

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = theme.accentPrimary, background = theme.bgPrimary, surface = theme.bgSecondary)
        else lightColorScheme(primary = theme.accentPrimary, background = theme.bgPrimary, surface = theme.bgSecondary),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = theme.bgPrimary,
        ) {
            Box(Modifier.fillMaxSize()) {
                // 全局"点空白收键盘": 见上方 HideKeyboardOnTap 注释。覆盖未被下层消费的点击
                // (页面空白处/卡片容器等); 消息流内部被消费的点击由各处手势自行收键盘。
                HideKeyboardOnTap(Modifier.fillMaxSize()) {
                    val showTab = authState.screen == AppScreen.ChatList ||
                        authState.screen == AppScreen.Contacts ||
                        authState.screen == AppScreen.Profile ||
                        authState.screen == AppScreen.Projects
                    // Android 物理返回键: 群成员管理页返回群聊; 项目钻取逐级回退; 聊天页按来源返回;
                    // tab/Login 不拦截。
                    AppBackHandler(enabled = !showTab && authState.screen != AppScreen.Login) {
                        when (authState.screen) {
                            AppScreen.GroupInfo -> viewModel.navigate(AppScreen.GroupChat)
                            AppScreen.ProjectIssues -> viewModel.navigate(AppScreen.Projects)
                            AppScreen.IssueSessions -> viewModel.navigate(AppScreen.ProjectIssues)
                            AppScreen.ExtensionWeb -> viewModel.navigate(AppScreen.Projects)
                            AppScreen.CreateProject -> viewModel.navigate(AppScreen.Projects)
                            AppScreen.CreateIssue -> viewModel.navigate(AppScreen.ProjectIssues)
                            AppScreen.CreateSession -> viewModel.navigate(AppScreen.IssueSessions)
                            AppScreen.Home -> viewModel.backFromHome()
                            else -> viewModel.backToLastTab()
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (authState.screen) {
                                AppScreen.Login -> LoginScreen(authState, theme, viewModel)
                                AppScreen.Home -> HomeScreen(chatState, composerState, authState, theme, viewModel)
                                AppScreen.Clones -> {
                                    val state by viewModel.state.collectAsState()
                                    CloneListScreen(state, theme, viewModel)
                                }
                                AppScreen.Settings -> {
                                    val state by viewModel.state.collectAsState()
                                    SettingsScreen(state, theme, viewModel)
                                }
                                AppScreen.Contacts -> {
                                    val state by viewModel.state.collectAsState()
                                    ContactsScreen(state, theme, viewModel)
                                }
                                AppScreen.GroupChat -> {
                                    val state by viewModel.state.collectAsState()
                                    GroupChatScreen(state, theme, viewModel)
                                }
                                AppScreen.GroupInfo -> {
                                    val state by viewModel.state.collectAsState()
                                    GroupInfoScreen(state, theme, viewModel)
                                }
                                AppScreen.ChatList -> {
                                    val state by viewModel.state.collectAsState()
                                    ChatListScreen(state, theme, viewModel)
                                }
                                AppScreen.Profile -> {
                                    val state by viewModel.state.collectAsState()
                                    ProfileScreen(state, theme, viewModel)
                                }
                                AppScreen.CreateClone -> {
                                    val state by viewModel.state.collectAsState()
                                    CreateCloneScreen(state, theme, viewModel)
                                }
                                AppScreen.CreateGroup -> {
                                    val state by viewModel.state.collectAsState()
                                    CreateGroupScreen(state, theme, viewModel)
                                }
                                AppScreen.Projects -> {
                                    val state by viewModel.state.collectAsState()
                                    ProjectsScreen(state, theme, viewModel)
                                }
                                AppScreen.ProjectIssues -> {
                                    val state by viewModel.state.collectAsState()
                                    ProjectIssuesScreen(state, theme, viewModel)
                                }
                                AppScreen.IssueSessions -> {
                                    val state by viewModel.state.collectAsState()
                                    IssueSessionsScreen(state, theme, viewModel)
                                }
                                AppScreen.ExtensionWeb -> {
                                    val state by viewModel.state.collectAsState()
                                    ExtensionWebScreen(state, theme, viewModel)
                                }
                                AppScreen.CreateProject -> {
                                    val state by viewModel.state.collectAsState()
                                    CreateProjectScreen(state, theme, viewModel)
                                }
                                AppScreen.CreateIssue -> {
                                    val state by viewModel.state.collectAsState()
                                    CreateIssueScreen(state, theme, viewModel)
                                }
                                AppScreen.CreateSession -> {
                                    val state by viewModel.state.collectAsState()
                                    CreateSessionScreen(state, theme, viewModel)
                                }
                            }
                        }
                        if (showTab) {
                            BottomTabBar(authState.screen, theme, viewModel)
                        }
                    }
                }
                authState.toast?.let { ToastBubble(it, theme) }
                if (authState.cloneSheetOpen || authState.presetSheetOpen) {
                    val state by viewModel.state.collectAsState()
                    if (authState.cloneSheetOpen) CloneSheet(state, theme, viewModel)
                    if (authState.presetSheetOpen) PresetSheet(state, theme, viewModel)
                }
            }
        }
    }
}

/** 收起软键盘: clearFocus 让焦点离开文本框(键盘随之收起), hide() 兜底 iOS 上
 *  焦点已失但键盘残留的场景。返回的 lambda 可在任意手势回调(非组合上下文)里调用;
 *  focusManager/keyboardController 在组合期捕获, 手势触发时仍有效。 */
@Composable
fun rememberHideKeyboard(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
}

/** "点击输入框以外区域收起键盘"。
 *  Compose 的 tap 在 Main pass 自底向上分发: 任何子节点(消息气泡/列表容器/按钮)消费了点击,
 *  根部的全局手势就收不到 — 所以除根部兜底外, 消息流上已有的 tap 手势也要顺带收键盘。 */
@Composable
fun HideKeyboardOnTap(
    modifier: Modifier = Modifier,
    onTap: ((Offset) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val hideKeyboard = rememberHideKeyboard()
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { offset ->
                    hideKeyboard()
                    onTap?.invoke(offset)
                },
            )
        },
    ) {
        content()
    }
}

// 底部 Tab 栏: 聊天 / 通讯录 / 项目 / 我. 仅在 tab 页面(ChatList/Contacts/Projects/Profile)显示. 图标+文字+选中态.
@Composable
private fun BottomTabBar(current: AppScreen, theme: MomoTheme, vm: MomoAppViewModel) {
    Column(Modifier.fillMaxWidth().background(theme.bgSecondary)) {
        HorizontalDivider(color = theme.borderDefault, thickness = 0.5.dp)
        Row(
            Modifier.fillMaxWidth().then(
                if (platformBottomTabPaddingDp() > 0f) Modifier.padding(bottom = platformBottomTabPaddingDp().dp)
                else Modifier.navigationBarsPadding()
            ).height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tabs = listOf(
                "聊天" to AppScreen.ChatList,
                "通讯录" to AppScreen.Contacts,
                "项目" to AppScreen.Projects,
                "我" to AppScreen.Profile,
            )
            tabs.forEach { (label, screen) ->
                val selected = current == screen
                val tint = if (selected) theme.accentPrimary else theme.textMuted
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { vm.navigate(screen) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Hypergrid: 选中项顶部发光短线
                    Box(
                        Modifier
                            .width(28.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (selected) theme.accentPrimary else Color.Transparent),
                    )
                    Spacer(Modifier.height(6.dp))
                    when (screen) {
                        AppScreen.ChatList -> ChatTabIcon(tint)
                        AppScreen.Contacts -> ContactsTabIcon(tint)
                        AppScreen.Projects -> ProjectsTabIcon(tint)
                        AppScreen.Profile -> ProfileTabIcon(tint)
                        else -> {}
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        color = tint,
                        style = momoMonoStyle(size = 10, color = tint, weight = if (selected) FontWeight.Bold else FontWeight.Normal),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatTabIcon(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(tint, Offset(w * 0.15f, h * 0.18f), Size(w * 0.7f, h * 0.5f), CornerRadius(w * 0.12f, w * 0.12f))
        val p = Path().apply { moveTo(w * 0.3f, h * 0.6f); lineTo(w * 0.3f, h * 0.82f); lineTo(w * 0.48f, h * 0.6f); close() }
        drawPath(p, tint)
    }
}

// 菜单动作行(自绘弹窗用): 图标在浅色圆角底里 + 标签, 整行可点, 无默认 DropdownMenuItem 的紧凑感。
@Composable
private fun MenuActionRow(
    icon: @Composable () -> Unit,
    label: String,
    theme: MomoTheme,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(theme.accentPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            // 内层容器同样居中: 文字型 icon(典/简)才不会顶格偏左。
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.width(MomoSpacing.md))
        Text(
            label,
            color = theme.textPrimary,
            style = momoTextStyle(MomoTypography.body),
        )
    }
}

@Composable
private fun ContactsTabIcon(tint: Color) {
    // 通讯录图标：使用资源图片(ic_contacts.png, 透明背景), 按 tab 选中态着色。
    Image(
        painter = painterResource(Res.drawable.ic_contacts),
        contentDescription = "通讯录",
        modifier = Modifier.size(24.dp),
        colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
    )
}

@Composable
private fun ProfileTabIcon(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        drawCircle(tint, w * 0.16f, Offset(w * 0.5f, h * 0.3f))
        drawRoundRect(tint, Offset(w * 0.28f, h * 0.56f), Size(w * 0.44f, h * 0.36f), CornerRadius(w * 0.22f, w * 0.22f))
    }
}

@Composable
private fun ProjectsTabIcon(tint: Color) {
    // 项目(文件夹)图标: 顶部 tab(凸起) + 主体盒, 风格对齐 ChatTabIcon/ProfileTabIcon 的 Canvas 线条。
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        // 文件夹主体
        drawRoundRect(tint, Offset(w * 0.15f, h * 0.30f), Size(w * 0.70f, h * 0.50f), CornerRadius(w * 0.10f, w * 0.10f))
        // 顶部 tab 凸起
        val tab = Path().apply {
            moveTo(w * 0.15f, h * 0.30f)
            lineTo(w * 0.15f, h * 0.22f)
            lineTo(w * 0.42f, h * 0.22f)
            lineTo(w * 0.50f, h * 0.30f)
            close()
        }
        drawPath(tab, tint)
    }
}

// 顶部栏加号图标(建群)
@Composable
private fun IconAdd(tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width; val h = size.height
            drawLine(tint, Offset(w * 0.5f, h * 0.15f), Offset(w * 0.5f, h * 0.85f), strokeWidth = w * 0.1f)
            drawLine(tint, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.85f, h * 0.5f), strokeWidth = w * 0.1f)
        }
    }
}

// 顶部栏菜单图标(三横)
@Composable
private fun IconMenu(tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width; val h = size.height
            drawLine(tint, Offset(w * 0.2f, h * 0.35f), Offset(w * 0.8f, h * 0.35f), strokeWidth = w * 0.09f)
            drawLine(tint, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), strokeWidth = w * 0.09f)
            drawLine(tint, Offset(w * 0.2f, h * 0.65f), Offset(w * 0.8f, h * 0.65f), strokeWidth = w * 0.09f)
        }
    }
}

// "我" 页: 用户信息 + 分身列表 + 设置入口.
@Composable
private fun ProfileScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val listState = rememberLazyListState()
    val user = state.user
    val badges = remember(state.clones) { cloneBadges(state.clones) }
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        PullToRefreshBox(
            isRefreshing = state.clonesRefreshing,
            onRefresh = { vm.refreshProfile() },
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                Spacer(Modifier.height(MomoSpacing.sm))
                // 方案A: 渐变 banner 用户卡(靛蓝→紫, 白字, 右侧在线点)。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MomoSpacing.lg)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF6366F1), Color(0xFFA78BFA)),
                            ),
                        )
                        .padding(MomoSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.28f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            user?.displayName?.trim()?.firstOrNull()?.uppercase()?.toString() ?: "我",
                            color = Color.White,
                            style = momoTextStyle(MomoTypography.headline.copy(fontWeight = FontWeight.Bold)),
                        )
                    }
                    Spacer(Modifier.width(MomoSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            user?.displayName?.ifBlank { user?.id } ?: "未登录",
                            color = Color.White,
                            style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)),
                        )
                        if (!user?.role.isNullOrBlank()) {
                            Text(
                                if (user!!.role == "admin") "管理员 · 默认组" else "成员",
                                color = Color.White.copy(alpha = 0.85f),
                                style = momoTextStyle(MomoTypography.caption),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34D399)),
                    )
                }
            }
            item { Spacer(Modifier.height(MomoSpacing.md)) }
            item {
                // Hypergrid 统计三格(等宽数字) — 与项目精简模式同一数据源(liteSessions)与
                // 同一活跃判定(isActiveLite), 保证数字一致; 精简数据未加载时按需拉取。
                val runningCount = state.liteSessions.count { it.session.isActiveLite(state.projectsActiveWindowDays) }
                val stats = listOf(
                    Triple("SESSIONS", state.liteSessions.size.toString(), LiteListTab.All),
                    Triple("RUNNING", runningCount.toString(), LiteListTab.Running),
                    Triple("DONE", (state.liteSessions.size - runningCount).toString(), LiteListTab.Done),
                )
                LaunchedEffect(Unit) {
                    if (state.liteSessions.isEmpty()) vm.loadLiteSessions()
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.lg)) {
                    stats.forEachIndexed { i, (label, value, tab) ->
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(end = if (i < stats.lastIndex) MomoSpacing.sm else 0.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(theme.bgSecondary)
                                .border(1.dp, theme.borderDefault, RoundedCornerShape(14.dp))
                                .clickable {
                                    // 跳项目页并落在对应精简 tab(未开精简则自动开启)。
                                    if (!state.projectsLiteMode) vm.toggleProjectsLiteMode()
                                    vm.setLiteTab(tab)
                                    vm.navigate(AppScreen.Projects)
                                }
                                .padding(vertical = MomoSpacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CountUpNumber(value.toInt(), theme.textPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(label, color = theme.textMuted.copy(alpha = 0.7f), style = momoMonoStyle(size = 7, color = theme.textMuted.copy(alpha = 0.7f), weight = FontWeight.Bold))
                        }
                    }
                }
            }
            item {
                Text(
                    "我的小莫 / 分身",
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.sectionHeader),
                    modifier = Modifier.padding(start = MomoSpacing.lg, top = MomoSpacing.lg, bottom = MomoSpacing.sm),
                )
            }
            if (state.clones.isEmpty()) {
                item {
                    if (state.clonesLoaded) {
                        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                            Text("暂无小莫", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
                        }
                    } else {
                        // 首次加载(无缓存): 分身区骨架屏(用户卡由上方真实数据渲染, 已有 user)。
                        Column(Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.lg)) {
                            repeat(2) { i ->
                                Row(
                                    Modifier.fillMaxWidth().height(64.dp).padding(vertical = MomoSpacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SkeletonBlock(44.dp, 44.dp, theme, corner = MomoCorners.pill)
                                    Spacer(Modifier.width(MomoSpacing.md))
                                    Column {
                                        SkeletonBlock(104.dp + 26.dp * i, 14.dp, theme)
                                        Spacer(Modifier.height(MomoSpacing.xs))
                                        SkeletonBlock(150.dp, 10.dp, theme)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(state.clones, key = { _, s -> s.sessionId }) { prIdx, session ->
                    HypergridReveal(index = prIdx) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.xs)
                            .clip(RoundedCornerShape(16.dp))
                            .background(theme.bgSecondary)
                            .border(1.dp, theme.borderDefault, RoundedCornerShape(16.dp)),
                    ) {
                        CloneRow(session, badges[session.sessionId], theme) { vm.openSession(session) }
                    }
                    }
                }
            }
            item {
                Text(
                    "设置",
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.sectionHeader),
                    modifier = Modifier.padding(start = MomoSpacing.lg, top = MomoSpacing.xl, bottom = MomoSpacing.sm),
                )
            }
            item {
                // 方案A: 设置行卡片 + 彩色图标底 + 主题外观值。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MomoSpacing.lg)
                        .height(58.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(theme.bgSecondary)
                        .clickable { vm.navigate(AppScreen.Settings) }
                        .padding(horizontal = MomoSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(theme.accentPrimary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("设", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.SemiBold)))
                    }
                    Spacer(Modifier.width(MomoSpacing.md))
                    Text("通用设置", color = theme.textPrimary, style = momoTextStyle(MomoTypography.body), modifier = Modifier.weight(1f))
                    Text("›", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
                }
            }
            item { Spacer(Modifier.height(MomoSpacing.xxxl)) }
        }
        }
    }
}

@Composable
private fun LoginScreen(state: AuthState, theme: MomoTheme, vm: MomoAppViewModel) {
    val bg = if (theme.dark) theme.bgPrimary else theme.bgSecondary
    // 0.3.0: 服务器地址列表可能有多条, 整页改为可滚动(小屏/列表长时登录按钮不被挤出屏)。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MomoSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(MomoSpacing.xxxl + MomoSpacing.xxl + MomoSpacing.sm))
        MobiusBrandLogo(96.dp)
        Spacer(Modifier.height(MomoSpacing.xxl))
        Text("Mobius", color = theme.textPrimary, style = momoTextStyle(MomoTypography.largeTitle))
        Spacer(Modifier.height(MomoSpacing.md))
        Text("登录后开始与 Mobius 对话", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
        Spacer(Modifier.height(MomoSpacing.xxl))
        LoginServerBaseUrlField(state, theme, vm)
        Spacer(Modifier.height(MomoSpacing.xxl))
        // 用户名与密码同时展示，单页登录（IME「下一步」从用户名跳到密码）。
        val passwordFocus = remember { FocusRequester() }
        MomoInput(
            value = state.username,
            placeholder = "请输入用户名",
            theme = theme,
            imeAction = ImeAction.Next,
            onSubmit = { passwordFocus.requestFocus() },
            onChange = vm::setUsername,
        )
        Spacer(Modifier.height(MomoSpacing.lg))
        MomoInput(
            value = state.password,
            placeholder = if (state.passwordRequired) "请输入密码" else "密码（可选）",
            theme = theme,
            password = true,
            imeAction = ImeAction.Done,
            onSubmit = vm::login,
            onChange = vm::setPassword,
            focusRequester = passwordFocus,
        )
        Spacer(Modifier.height(MomoSpacing.xl))
        PrimaryButton("登 录", state.loading, theme, vm::login)
        Spacer(Modifier.height(MomoSpacing.xxl))
        Text("忘记密码？请联系管理员重置", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
        Spacer(Modifier.height(MomoSpacing.xxxl))
    }
}

@Composable
private fun LoginServerBaseUrlField(state: AuthState, theme: MomoTheme, vm: MomoAppViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "服务器地址",
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.SemiBold)),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "填入默认",
                color = theme.accentPrimary,
                style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.SemiBold)),
                modifier = Modifier
                    .clip(RoundedCornerShape(MomoCorners.chip))
                    .clickable { vm.setServerBaseUrl(RECOMMENDED_MOBIUS_BASE_URL) }
                    .padding(horizontal = MomoSpacing.sm, vertical = MomoSpacing.xs),
            )
        }
        Spacer(Modifier.height(MomoSpacing.sm))
        // 已保存过服务器(登录成功自动记录)时: 选择器在上, 下方保留可编辑输入框(选中即回填)。
        if (state.serverEntries.isNotEmpty()) {
            ServerAddressPicker(
                entries = state.serverEntries,
                currentUrl = state.serverBaseUrl,
                theme = theme,
                onSelect = vm::selectServerEntry,
                onRemove = vm::removeServerEntry,
                onRename = vm::renameServerEntry,
            )
            Spacer(Modifier.height(MomoSpacing.md))
        }
        MomoInput(
            value = state.serverBaseUrl,
            placeholder = RECOMMENDED_MOBIUS_BASE_URL,
            theme = theme,
            minHeight = 44.dp,
            imeAction = ImeAction.Next,
            keyboardType = KeyboardType.Uri,
            onSubmit = vm::saveServerBaseUrl,
            onChange = vm::setServerBaseUrl,
        )
        // 列表非空时给出提示: 输入框仍是权威入口(可改可存), 选择器只是快捷回填。
        if (state.serverEntries.isNotEmpty()) {
            Spacer(Modifier.height(MomoSpacing.xs))
            Text(
                "从上方列表选择, 或直接输入新地址",
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption),
            )
        }
    }
}

/**
 * 登录页服务器地址选择器(0.3.0): 最近使用倒序的卡片列表。
 * - 点击行: 选中并应用到输入框(走 vm.selectServerEntry, 与手输保存同路径)。
 * - 左滑(SwipeToDismissBox EndToStart): 删除该地址。
 * - 长按: 弹重命名对话框(label 备注名, 可空)。
 */
@Composable
private fun ServerAddressPicker(
    entries: List<ServerEntry>,
    currentUrl: String,
    theme: MomoTheme,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String, String?) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ServerEntry?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MomoCorners.medium))
            .background(theme.inputBg)
            .border(1.dp, theme.borderDefault, RoundedCornerShape(MomoCorners.medium)),
    ) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = theme.borderDefault, thickness = 0.5.dp)
            ServerAddressRow(
                entry = entry,
                selected = entry.url == currentUrl,
                theme = theme,
                onClick = { onSelect(entry.url) },
                onRemove = { onRemove(entry.url) },
                onRename = { renameTarget = entry },
            )
        }
    }
    renameTarget?.let { target ->
        ServerRenameDialog(
            entry = target,
            theme = theme,
            onConfirm = { label -> onRename(target.url, label) },
            onDismiss = { renameTarget = null },
        )
    }
}

// 单条服务器地址行: 左滑删除 + 长按重命名, 点击选择(与 DeletableChatRow 同一交互范式)。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerAddressRow(
    entry: ServerEntry,
    selected: Boolean,
    theme: MomoTheme,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeDeleteBackground(theme, "删除") },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) theme.accentPrimary.copy(alpha = 0.08f) else Color.Transparent)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRename()
                    },
                )
                .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (entry.label.isNotBlank()) {
                    Text(
                        entry.label,
                        color = theme.textPrimary,
                        style = momoTextStyle(MomoTypography.body.copy(fontWeight = FontWeight.SemiBold)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.url,
                        color = theme.textMuted,
                        style = momoTextStyle(MomoTypography.caption),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        entry.url,
                        color = if (selected) theme.accentPrimary else theme.textPrimary,
                        style = momoTextStyle(MomoTypography.body),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(MomoSpacing.sm))
                Text(
                    "当前",
                    color = theme.accentPrimary,
                    style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.SemiBold)),
                )
            }
        }
    }
}

// 服务器地址重命名对话框: label 备注名可空(清空即恢复只显 URL)。
@Composable
private fun ServerRenameDialog(
    entry: ServerEntry,
    theme: MomoTheme,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(entry) { mutableStateOf(entry.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("重命名服务器", color = theme.textPrimary, style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)))
        },
        text = {
            Column {
                Text(
                    entry.url,
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.caption),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(MomoSpacing.md))
                MomoInput(
                    value = label,
                    placeholder = "备注名(可空), 如: 公司测试机",
                    theme = theme,
                    minHeight = 44.dp,
                    imeAction = ImeAction.Done,
                    onSubmit = {
                        onConfirm(label.trim())
                        onDismiss()
                    },
                    onChange = { label = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(label.trim())
                onDismiss()
            }) {
                Text("保存", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.body.copy(fontWeight = FontWeight.Bold)))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
            }
        },
        containerColor = theme.bgSecondary,
        titleContentColor = theme.textPrimary,
        textContentColor = theme.textMuted,
    )
}

@Composable
private fun HomeScreen(
    chatState: ChatState,
    composerState: ComposerUiState,
    authState: AuthState,
    theme: MomoTheme,
    vm: MomoAppViewModel,
) {
    val listState = rememberLazyListState()
    val hideKeyboard = rememberHideKeyboard()
    val haptic = LocalHapticFeedback.current
    var showCompactConfirm by remember { mutableStateOf(false) }
    // 手风琴(项目 session): 同一时刻只展开一个 turn。
    // userExpandedTurnId 三态: null=无偏好(默认展开最后一轮) / ""=用户已全部收起 / turnId=展开该轮。
    // (旧实现两处 bug: ① 点已展开的最后一轮 → 置 null → 默认规则又展开最后一轮 → 永远收不起;
    //  ② 流式期间强制 lastTurnId 无视用户点击 → 其它轮打不开。三态哨兵 + 用户偏好优先修复。)
    // remember(lastTurnId): 新一轮开始(用户发新消息)时状态重置为无偏好 → 新回合自动展开,
    // 不依赖 LaunchedEffect(iOS 该模式偶发不执行)。
    val lastTurnId = chatState.turns.lastOrNull()
        ?.let { it.userMessage?.id ?: it.assistantItems.firstOrNull()?.id }
    val userExpandedTurnId = remember(lastTurnId) { mutableStateOf<String?>(null) }
    val anyTurnExpanded = userExpandedTurnId.value == null || userExpandedTurnId.value.orEmpty().isNotBlank()
    val expandedTurnId: String? = when (val pref = userExpandedTurnId.value) {
        null -> lastTurnId
        else -> pref.takeIf { it.isNotBlank() }
    }
    // 打开/切换会话即滚到最新消息(不等新消息到达)。
    LaunchedEffect(chatState.activeSessionId, chatState.messages.isNotEmpty()) {
        if (chatState.messages.isNotEmpty()) {
            // 稍等一帧让列表完成首帧组合, 避免 scrollToItem 在空布局上无效。
            androidx.compose.runtime.withFrameNanos {}
            listState.scrollToItem(chatState.messages.size + 2)
        }
    }
    LaunchedEffect(chatState.messages.lastOrNull()?.id, chatState.typing) {
        // 滚到最底部：列表有顶部 spacer + 可选 typing 行，用 size+2 超出最后一条，
        // scrollToItem 会自动钳制到最后一个 item，保证最新消息贴底显示。
        if (chatState.messages.isNotEmpty()) listState.scrollToItem(chatState.messages.size + 2)
    }
    LaunchedEffect(chatState.ttsSpeakingMessageId, chatState.messages) {
        val speakingId = chatState.ttsSpeakingMessageId ?: return@LaunchedEffect
        val index = chatState.messages.indexOfFirst { it.id == speakingId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = chatState.activeSessionTitle.ifBlank { "我的主 Mobius" },
            theme = theme,
            left = "‹",
            onLeft = vm::backFromHome,
            // 参考图: 顶栏极简, 右侧一个 ⋯ 更多按钮(点击弹「压缩上文」确认)。
            right = "⋯",
            onRight = { showCompactConfirm = true },
        )
        // 设计稿: 顶栏下 mono 模型/状态行(如 "GLM-5.2 · ONLINE")。
        val modelTag = chatState.activeSessionModelLabel?.takeIf { it.isNotBlank() }
        Row(
            Modifier
                .fillMaxWidth()
                .background(theme.bgSecondary)
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            val online = chatState.activeSessionStatus.equals("running", true) ||
                chatState.activeSessionStatus.equals("waiting", true) ||
                chatState.typing
            Text(
                buildString {
                    modelTag?.let { append(it) }
                    append(" · ")
                    append(if (online) "ONLINE" else "READY")
                },
                style = momoMonoStyle(
                    size = 8,
                    color = if (online) theme.success else theme.textMuted,
                    weight = FontWeight.Medium,
                ),
            )
        }
        // 任务进度胶囊: agent_status 为 running/waiting/completed/failed 时显示,
        // 让"收到, 后续会通知你"这类后台任务在聊天页有可见进度(idle 不显示, 不打扰)。
        AgentStatusStrip(chatState.activeSessionStatus, theme, typing = chatState.typing)
        // 项目钻取 session: "全部折叠/展开"切换(turn 手风琴的全局开关)。
        if (chatState.compactMode && chatState.turns.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(theme.bgSecondary)
                    .padding(horizontal = MomoSpacing.lg, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    if (anyTurnExpanded) "全部收起" else "全部展开",
                    color = theme.accentPrimary,
                    style = momoTextStyle(MomoTypography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(MomoCorners.pill))
                        .background(theme.accentPrimary.copy(alpha = 0.10f))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // 全部收起 = 空串哨兵; 全部展开 = 无偏好(默认展开最后一轮)。
                            userExpandedTurnId.value = if (anyTurnExpanded) "" else null
                        }
                        .padding(horizontal = MomoSpacing.md, vertical = 4.dp),
                )
            }
        }
        // 项目钻取 session: 顶栏下显示当前会话使用的模型名(model_label, 如 GLM-5.2)。
        // (仅项目钻取 openSession 时 session.modelLabel 非空; 主小莫/分身的 snapshot session 无此字段 -> 不显示)
        chatState.activeSessionModelLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                label,
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption.copy(fontSize = 11.sp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.bgSecondary)
                    .padding(vertical = 3.dp),
                textAlign = TextAlign.Center,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    // 点击空白区域/其他消息时: 收起键盘 + 停止正在播放的语音。
                    // (tap 会被本层消费, 根部全局收键盘手势收不到 → 这里必须自己收。)
                    detectTapGestures(onTap = {
                        hideKeyboard()
                        if (chatState.ttsSpeakingMessageId != null) vm.stopSpeaking()
                    })
                },
        ) {
            LazyColumn(
                // 水平边距由 MessageRow 自己管理(horizontalInset=10dp), 列表不加 padding。
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.Top,
            ) {
                item { Spacer(Modifier.height(MomoSpacing.xs)) }
                // compactMode (项目 issue session): 轮次折叠展开 + 无头像 + 缩进
                // 非 compactMode (主小莫/分身聊天): 保持原有扁平消息列表 + 头像
                if (chatState.compactMode) {
                items(chatState.turns, key = { turn ->
                    turn.userMessage?.id ?: turn.assistantItems.firstOrNull()?.id ?: "turn-empty"
                }) { turn ->
                    val turnId = turn.userMessage?.id ?: turn.assistantItems.firstOrNull()?.id ?: "turn-empty"
                    val expanded = expandedTurnId == turnId
                    // 手风琴切换: 点已展开的 → 全部收起(""); 点收起的 → 展开该轮(互斥)。
                    // 轻触 haptic 确认反馈(折叠/展开是即时可见的重要交互)。
                    val toggleTurn = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        userExpandedTurnId.value = if (expanded) "" else turnId
                    }

                    // === Turn header: 用户消息折叠栏(参考图胶囊样式) ===
                    // 收起时单行省略; 展开时完整显示用户消息(多行)。
                    // accent 实底胶囊 + 右对齐(对应用户消息的右对齐语义); 无用户消息的轮次灰边框弱化。
                    val headerText = turn.userMessage?.text?.trim()?.takeIf { it.isNotBlank() }
                        ?: turn.assistantItems.firstOrNull()?.text?.trim()?.take(80)
                        ?: "新会话"
                    val isUserTurn = turn.userMessage != null

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = MomoSpacing.md)
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = if (isUserTurn) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .widthIn(max = 340.dp)
                                .clip(RoundedCornerShape(MomoCorners.pill))
                                .background(if (isUserTurn) theme.accentPrimary.copy(alpha = 0.14f) else Color.Transparent)
                                .border(
                                    width = 1.dp,
                                    color = if (isUserTurn) theme.accentPrimary.copy(alpha = 0.45f) else theme.borderDefault,
                                    shape = RoundedCornerShape(MomoCorners.pill),
                                )
                                .clickable { toggleTurn() }
                                .padding(horizontal = MomoSpacing.lg, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                headerText,
                                color = if (isUserTurn) theme.accentPrimary else theme.textMuted,
                                style = momoTextStyle(MomoTypography.subheadline.copy(lineHeight = 19.sp)),
                                maxLines = if (expanded) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(MomoSpacing.sm))
                            Text(
                                if (expanded) "▾" else "▸",
                                color = if (isUserTurn) theme.accentPrimary else theme.textMuted,
                                style = momoTextStyle(MomoTypography.caption),
                            )
                        }
                    }

                    // === Turn body: agent 回复 + 过程卡片 (可折叠, MessageRow 自带白卡片样式) ===
                    if (expanded) {
                        Column(Modifier.fillMaxWidth()) {
                            val items = turn.assistantItems
                            var lastTextIndex = -1
                            items.forEachIndexed { idx, msg ->
                                val isProcess = msg.processType != null
                                if (isProcess) {
                                    ProcessEntryCard(msg, theme)
                                } else {
                                    val prevTextIdx = lastTextIndex
                                    val continuation = prevTextIdx >= 0 &&
                                        items[prevTextIdx].processType == null
                                    val isLastItem = idx == items.lastIndex
                                    val isStreaming = turn.isStreaming && isLastItem

                                    // 与主聊天一致的节奏: 轮次首条 16dp / 续条 4dp。(msgReveal 动画已移除, 同 iOS 可靠性)
                                    Spacer(Modifier.height(if (continuation) 4.dp else 10.dp))
                                    Box {
                                        MessageRow(
                                            message = msg,
                                            theme = theme,
                                            onReplay = vm::speakChatMessage,
                                            streaming = isStreaming,
                                            isSpeaking = chatState.ttsSpeakingMessageId == msg.id,
                                            speakingMessageId = chatState.ttsSpeakingMessageId,
                                            onStopSpeaking = vm::stopSpeaking,
                                            userDisplayName = authState.user?.displayName ?: authState.user?.id,
                                            imagePreviews = emptyList(),
                                            continuation = continuation,
                                            isFetching = chatState.ttsFetchingMessageId == msg.id,
                                            compact = chatState.compactMode,
                                            onDelete = chatState.activeSessionId.takeIf { it.isNotBlank() }
                                                ?.let { { m -> vm.deleteMessage(m.id) } },
                                        )
                                    }
                                    lastTextIndex = idx
                                }
                            }
                            Spacer(Modifier.height(MomoSpacing.sm))
                        }
                    }

                    // Turn separator
                    Spacer(Modifier.height(MomoSpacing.xs))
                }
                if (chatState.typing) {
                    item {
                        // 与主聊天一致: typing 直接用白卡片气泡(MessageRow 同款样式), 无外层色块。
                        Column(Modifier.padding(horizontal = 10.dp)) {
                            Spacer(Modifier.height(MomoSpacing.md))
                            Row(verticalAlignment = Alignment.Top) {
                                TypingBubble(theme)
                            }
                        }
                    }
                }
                } else {
                    // === 主聊天/分身: 原始扁平消息列表 (保留头像 + continuation) ===
                    itemsIndexed(chatState.messages, key = { _, msg -> msg.id }) { index, message ->
                        val isStreaming = chatState.typing &&
                            message.author == MessageAuthor.Momo &&
                            index == chatState.messages.lastIndex
                        val continuation = index > 0 &&
                            message.author == MessageAuthor.Momo &&
                            chatState.messages[index - 1].author == MessageAuthor.Momo
                        val isProcess = message.processType != null
                        if (isProcess) {
                            // 过程卡片与消息流同 10dp 边距(列表不再有全局 padding)。
                            Box(Modifier.padding(horizontal = 10.dp)) {
                                ProcessEntryCard(message, theme)
                            }
                        } else {
                            // iOS 可靠性: LazyColumn item 内 LaunchedEffect+animateFloat 驱动的
                            // alpha 入场在 iOS Skia 偶发不提交(alpha 卡 0 → 消息不可见) — 移除该动画,
                            // 消息直接渲染(列表卡入场动画 HypergridReveal 不涉及聊天流, 保留)。
                            Column {
                                // 参考图节奏: 轮次首条(用户提问/轮次首个回复)留大间距 16dp,
                                // 同轮续条(continuation)只留 4dp 紧贴上一段。
                                val isFirstOfTurn = !continuation &&
                                    (message.author == MessageAuthor.User || index == 0 ||
                                        chatState.messages[index - 1].author == MessageAuthor.User)
                                Spacer(Modifier.height(if (continuation) 4.dp else if (isFirstOfTurn) 16.dp else 10.dp))
                                Box {
                                    MessageRow(
                                        message = message,
                                        theme = theme,
                                        onReplay = vm::speakChatMessage,
                                        streaming = isStreaming,
                                        isSpeaking = chatState.ttsSpeakingMessageId == message.id,
                                        speakingMessageId = chatState.ttsSpeakingMessageId,
                                        onStopSpeaking = vm::stopSpeaking,
                                        userDisplayName = authState.user?.displayName ?: authState.user?.id,
                                        imagePreviews = vm.imagePreviewsForMessage(message),
                                        continuation = continuation,
                                        isFetching = chatState.ttsFetchingMessageId == message.id,
                                        compact = false,
                                        onDelete = chatState.activeSessionId.takeIf { it.isNotBlank() }
                                            ?.let { { msg -> vm.deleteMessage(msg.id) } },
                                    )
                                }
                            }
                        }
                    }
                    if (chatState.typing) {
                        item {
                            Column {
                                Spacer(Modifier.height(MomoSpacing.md))
                                Row(verticalAlignment = Alignment.Top) {
                                    TypingBubble(theme)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(MomoSpacing.md)) }
            }
            VerticalScrollbar(listState)
            MenuPanel(chatState.menuOpen, theme, vm)
        }
        ChatInputBar(composerState, chatState, theme, vm)
    }
    if (showCompactConfirm) {
        ConfirmDialog(
            title = "压缩上文",
            message = "将压缩当前会话的上文。压缩期间可继续发送指令，但响应会延后。",
            theme = theme,
            confirmLabel = "压缩",
            danger = false,
            onConfirm = {
                showCompactConfirm = false
                vm.compactContext()
            },
            onDismiss = { showCompactConfirm = false },
        )
    }
}

@Composable
private fun CloneListScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val cloneListState = rememberLazyListState()
    val badges = remember(state.clones) { cloneBadges(state.clones) }
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar("分身列表", theme, largeTitle = "分身列表", scrollState = cloneListState, left = "‹", onLeft = { vm.navigate(AppScreen.Profile) })
        Box(Modifier.weight(1f).background(theme.bgSecondary)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = cloneListState,
            ) {
                if (state.clones.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(160.dp).background(theme.bgSecondary), contentAlignment = Alignment.Center) {
                        Text("暂无 Mobius 会话", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
                        }
                    }
                } else {
                    items(state.clones) { session ->
                        CloneRow(session, badges[session.sessionId], theme) { vm.openSession(session) }
                        HorizontalDivider(color = theme.borderDefault, thickness = 0.6.dp)
                    }
                }
            }
            VerticalScrollbar(cloneListState)
        }
        Box(
            Modifier.fillMaxWidth().height(64.dp).background(theme.bgSecondary).clickable { vm.openCloneEditor() },
            contentAlignment = Alignment.Center,
        ) {
            Text("+  开分身", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.headline))
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val settingsListState = rememberLazyListState()
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar("设置", theme, largeTitle = "设置", scrollState = settingsListState, left = "‹", onLeft = { vm.navigate(AppScreen.Profile) })
        Box(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = settingsListState,
            ) {
                item { Spacer(Modifier.height(MomoSpacing.lg)) }
                item {
                    SettingsGroupCard("通 用", theme) {
                        // 主题外观三态: 跟随系统 → 浅色 → 深色 循环切换(点行), 当前值右侧显示。
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .background(Color.Transparent)
                                    .clickable {
                                        val next = when (state.themeMode) {
                                            ThemeMode.System -> ThemeMode.Light
                                            ThemeMode.Light -> ThemeMode.Dark
                                            ThemeMode.Dark -> ThemeMode.System
                                        }
                                        vm.setThemeMode(next)
                                    }
                                    .padding(horizontal = MomoSpacing.lg),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("主题外观", color = theme.textPrimary, style = momoTextStyle(MomoTypography.body))
                                Spacer(Modifier.weight(1f))
                                Text(
                                    when (state.themeMode) {
                                        ThemeMode.System -> "跟随系统"
                                        ThemeMode.Light -> "浅色"
                                        ThemeMode.Dark -> "深色"
                                    },
                                    color = theme.textMuted,
                                    style = momoTextStyle(MomoTypography.caption),
                                )
                            }
                        SettingSwitch("消息推送", state.pushEnabled, theme, showDivider = true, onClick = vm::togglePush)
                        SettingSwitch("自动播报", state.ttsEnabled, theme, showDivider = false, onClick = vm::toggleTts)
                    }
                }
                if (state.ttsEnabled) {
                    item { Spacer(Modifier.height(MomoSpacing.xxl)) }
                    item {
                        SettingsGroupCard("语 音 播 报", theme) {
                            TtsPlaybackModeSection(state.ttsPlaybackMode, theme, vm::setTtsPlaybackMode)
                            VoiceSelectionRow(
                                voices = state.availableVoices,
                                selectedVoice = state.selectedVoice,
                                loading = state.voicesLoading,
                                failed = state.voicesLoadFailed,
                                ttsConfigured = state.ttsConfigured,
                                theme = theme,
                                showDivider = false,
                                onSelect = vm::setSelectedVoice,
                                onRefresh = vm::refreshVoices,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(MomoSpacing.xxl)) }
                item {
                    SettingsGroupCard("连 接", theme) {
                        var serverField by remember { mutableStateOf(state.serverBaseUrl) }
                        var lastPushedServer by remember { mutableStateOf(state.serverBaseUrl) }
                        val externalServerUrl = state.serverBaseUrl
                        // 同 MomoInput：lastPushed 区分"自己回灌的回声"与"外部重置（填入默认等）"，
                        // 只有外部重置才回写本地，避免按键往返覆盖光标。
                        LaunchedEffect(externalServerUrl) {
                            if (externalServerUrl != lastPushedServer && externalServerUrl != serverField) {
                                serverField = externalServerUrl
                            }
                        }
                        BasicTextField(
                            value = serverField,
                            onValueChange = { newValue ->
                                serverField = newValue
                                if (newValue != lastPushedServer) {
                                    lastPushedServer = newValue
                                    vm.setServerBaseUrl(newValue)
                                }
                            },
                            singleLine = true,
                            textStyle = momoTextStyle(MomoTypography.body).copy(color = theme.textPrimary),
                            cursorBrush = SolidColor(theme.textPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.saveServerBaseUrl() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(start = MomoSpacing.lg, end = MomoSpacing.sm),
                            decorationBox = { inner ->
                                Row(
                                    Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
                                ) {
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                        if (serverField.isBlank()) {
                                            Text(
                                                "$RECOMMENDED_MOBIUS_BASE_URL（可修改为你自建的 Mobius 服务器）",
                                                color = theme.textMuted,
                                                style = momoTextStyle(MomoTypography.body),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        inner()
                                    }
                                    Box(
                                        Modifier
                                            .height(32.dp)
                                            .widthIn(min = 72.dp)
                                            .clip(RoundedCornerShape(MomoCorners.medium))
                                            .background(theme.accentPrimary.copy(alpha = 0.12f))
                                            .clickable { vm.setServerBaseUrl(RECOMMENDED_MOBIUS_BASE_URL) }
                                            .padding(horizontal = MomoSpacing.md),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("填入默认", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.subheadline.copy(fontWeight = FontWeight.Medium)))
                                    }
                                }
                            },
                        )
                        SettingsHairline(theme)
                        SettingActionRow("保存服务器地址", theme, showDivider = false, onClick = vm::saveServerBaseUrl)
                    }
                }
                item { Spacer(Modifier.height(MomoSpacing.xxl)) }
                item {
                    SettingsGroupCard("外 观", theme) {
                        ThemePaletteSelector(state.themePalette, theme, vm::setThemePalette)
                        SettingsHairline(theme)
                        ThemePalettePreview(theme)
                    }
                }
                item { Spacer(Modifier.height(MomoSpacing.xxl)) }
                item {
                    SettingsGroupCard("账 户", theme) {
                        SettingRow("账号", state.user?.displayName ?: state.user?.id.orEmpty(), theme, showDivider = true)
                        SettingRow("Mobius 预设", state.currentPresetLabel(), theme, showDivider = true, onClick = vm::openPresetSheet)
                        SettingRow("关于", "v${platformAppVersion()}", theme, showDivider = true)
                        SettingActionRow("退出登录", theme, danger = true, showDivider = false, onClick = vm::logout)
                    }
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = MomoSpacing.xl, bottom = MomoSpacing.xxxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MobiusBrandLogo(40.dp)
                        Spacer(Modifier.height(MomoSpacing.sm))
                        Text(
                            "Mobius · v${platformAppVersion()}",
                            color = theme.textMuted,
                            style = momoTextStyle(MomoTypography.subheadline),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            VerticalScrollbar(settingsListState)
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    theme: MomoTheme,
    largeTitle: String? = null,
    subtitle: String? = null,
    scrollState: LazyListState? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    left: String? = null,
    right: String? = null,
    rightContent: (@Composable () -> Unit)? = null,
    onLeft: () -> Unit = {},
    onRight: () -> Unit = {},
) {
    val collapsed = largeTitle == null ||
        scrollState == null ||
        scrollState.firstVisibleItemIndex > 0 ||
        scrollState.firstVisibleItemScrollOffset > 8
    val expanded = largeTitle != null && !collapsed
    val barHeight = if (expanded) (if (subtitle != null) 108.dp else 96.dp) else 56.dp
    val backgroundAlpha = if (expanded) 0.78f else 0.92f
    val hairline = if (theme.dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    Box(
        Modifier.fillMaxWidth().height(barHeight),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .momoBlur(20)
                .background(theme.bgPrimary.copy(alpha = backgroundAlpha)),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .align(Alignment.TopCenter),
        ) {
            when {
                left != null -> {
                    Text(
                        left,
                        color = theme.textPrimary,
                        style = momoTextStyle(MomoTypography.largeTitle),
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = MomoSpacing.xl).clickable { onLeft() },
                    )
                }
                leadingContent != null -> {
                    Box(
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = MomoSpacing.lg).height(38.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        leadingContent()
                    }
                }
            }
            if (collapsed) {
                Text(
                    title,
                    color = theme.textPrimary,
                    style = momoTextStyle(MomoTypography.headline),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = MomoSpacing.xxxl + MomoSpacing.xxl),
                )
            }
            if (rightContent != null) {
                Box(Modifier.align(Alignment.CenterEnd).padding(end = MomoSpacing.lg)) {
                    rightContent()
                }
            } else if (right != null) {
                Text(
                    right,
                    color = theme.textPrimary,
                    style = momoTextStyle(MomoTypography.title),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = MomoSpacing.xl).clickable { onRight() },
                )
            }
        }
        if (expanded) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = MomoSpacing.lg, end = MomoSpacing.lg, bottom = MomoSpacing.sm),
            ) {
                Text(
                    largeTitle.orEmpty(),
                    color = theme.textPrimary,
                    style = momoTextStyle(MomoTypography.largeTitle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = theme.textMuted.copy(alpha = 0.85f),
                        style = momoMonoStyle(size = 9, color = theme.textMuted.copy(alpha = 0.85f), weight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).align(Alignment.BottomCenter).background(hairline))
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    theme: MomoTheme,
    onReplay: (ChatMessage) -> Unit,
    streaming: Boolean = false,
    isSpeaking: Boolean = false,
    speakingMessageId: String? = null,
    onStopSpeaking: () -> Unit = {},
    userDisplayName: String? = null,
    imagePreviews: List<ByteArray> = emptyList(),
    continuation: Boolean = false,
    isFetching: Boolean = false,
    compact: Boolean = false,
    onDelete: ((ChatMessage) -> Unit)? = null,
) {
    val isUser = message.author == MessageAuthor.User
    var showActions by remember(message.id) { mutableStateOf(false) }
    val hideKeyboard = rememberHideKeyboard()
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val copyText = remember(message.text, message.voiceText) {
        message.text.ifBlank { message.voiceText.orEmpty() }
    }
    val speakingTransition = rememberInfiniteTransition(label = "speakingPulse")
    val speakingPulse by speakingTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speakingPulseValue",
    )
    val bubbleScale by animateFloatAsState(
        targetValue = if (isSpeaking) 1.015f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "speakingBubbleScale",
    )
    val bubbleBorderColor by animateColorAsState(
        targetValue = if (isSpeaking) theme.accentPrimary.copy(alpha = speakingPulse) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "speakingBubbleBorder",
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 参考图布局(1v1 与项目 session 统一): Mobius 回复=全宽卡片(两侧只留 10dp);
        // 用户消息=右对齐窄气泡(78%), 一宽一窄形成"卡片 vs 对话"的清晰层次。
        val horizontalInset = 10.dp
        val avail = maxWidth - horizontalInset * 2
        val contentMaxWidth = if (maxWidth < 520.dp) {
            if (isUser) avail * 0.86f else avail
        } else {
            (avail * if (isUser) 0.78f else 1f).coerceIn(240.dp, 900.dp)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalInset),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = contentMaxWidth)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bubbleShape = messageBubbleShape(isUser, continuation, compact)
                    // 消息框无 border(用户要求纯背景色); 仅语音播报时给 accent 高亮边。
                    val bubbleBorderWidth = if (isSpeaking) 1.5.dp else 0.dp
                    val bubbleBorder = if (isSpeaking) bubbleBorderColor else Color.Transparent
                    val bubbleInteraction = remember(message.id) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .scale(bubbleScale.coerceAtLeast(1f))
                            .border(bubbleBorderWidth, bubbleBorder, bubbleShape)
                            .clip(bubbleShape)
                            .background(
                                if (isUser) SolidColor(theme.accentPrimary.copy(alpha = 0.16f)) else SolidColor(theme.bubbleMomo),
                            )
                            .combinedClickable(
                                interactionSource = bubbleInteraction,
                                indication = null,
                                onClick = {
                                    // 点击气泡: 收起键盘 + 停止"其他"消息的语音(正在播放的本条不停止, 便于看内容)。
                                    hideKeyboard()
                                    if (speakingMessageId != null && speakingMessageId != message.id) onStopSpeaking()
                                    showActions = false
                                },
                                onLongClick = {
                                    // 长按在文本上由 SelectionContainer 接管, 弹出原生选区工具栏(选取复制);
                                    // 此处保留"复制/播放"操作菜单作为兜底(例如长按空白边距时)。
                                    showActions = !showActions
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                            )
                            .padding(horizontal = if (isUser) 14.dp else 16.dp, vertical = if (isUser) 10.dp else 12.dp),
                    ) {
                        if (!isUser) {
                            // Hypergrid context card: 左侧 accent 竖线作容器兄弟元素(不占内容行,
                            // 之前放在内容 Column 首行导致"竖线+空白行")。
                            Box(
                                Modifier
                                    .padding(top = 12.dp, bottom = 12.dp)
                                    .width(2.5.dp)
                                    .background(theme.accentPrimary, RoundedCornerShape(1.dp)),
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(MomoSpacing.sm)) {
                            // SelectionContainer 让长按文本触发原生选区工具栏(选取/全选/复制);
                            // 同时外部气泡的 onLongClick 仍保留"复制"按钮兜底(命中空白边距时)。
                            SelectionContainer {
                                if (isUser) {
                                    Text(
                                        message.text,
                                        color = theme.accentPrimary.copy(alpha = if (theme.dark) 0.92f else 1f),
                                        style = momoTextStyle(MomoTypography.body),
                                    )
                                    if (imagePreviews.isNotEmpty()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm)) {
                                            imagePreviews.forEachIndexed { _, bytes ->
                                                val bitmap = rememberDecodedImage(bytes)
                                                if (bitmap != null) {
                                                    Image(
                                                        bitmap = bitmap,
                                                        contentDescription = "图片附件",
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(96.dp)
                                                            .clip(RoundedCornerShape(MomoCorners.medium)),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    MarkdownMessageBody(
                                        content = message.text,
                                        theme = theme,
                                    )
                                }
                            }
                            // 续条: 时间戳放进气泡内(右下角小字), 段间不再有时间戳横条 → 整组读作一个整体。
                            if (!isUser && continuation) {
                                Text(
                                    message.time,
                                    color = if (theme.bubbleMomo.luminance() > 0.5f) Color(0xFF7A8095) else theme.textMuted,
                                    style = momoTextStyle(MomoTypography.caption.copy(fontSize = 10.sp)),
                                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                                )
                            }
                        }
                    }
                    if (isSpeaking) {
                        Spacer(Modifier.width(8.dp))
                        SpeakingInlineIndicator(
                            color = if (isUser) theme.bubbleBg else theme.accentPrimary,
                            alpha = speakingPulse,
                        )
                    }
                }
                if (isFetching) {
                    // 取音频/连接中: 与群聊一致的 loading 样式(气泡下方 转圈 + 文案), 操作菜单关闭后仍可见。
                    Row(
                        modifier = Modifier.align(if (isUser) Alignment.End else Alignment.Start).padding(top = MomoSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = theme.accentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("语音加载中…", color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
                    }
                }
                if (showActions && copyText.isNotBlank()) {
                    Spacer(Modifier.height(MomoSpacing.xs))
                    Row(
                        modifier = Modifier.align(if (isUser) Alignment.End else Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MomoSpacing.xs),
                    ) {
                        MessageActionPill(
                            label = "复制",
                            theme = theme,
                            onClick = {
                                clipboard.setText(AnnotatedString(copyText))
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showActions = false
                            },
                        )
                        MessagePlayIconButton(theme = theme, loading = isFetching) {
                            onReplay(message)
                            showActions = false
                        }
                    }
                }
                if (!continuation) {
                    Spacer(Modifier.height(MomoSpacing.xs))
                    Text(message.time, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
                }
            }
        }
    }
}

// 过程条目卡片: 可折叠的 thinking / tool_call / search 等过程信息。
// 在轮次内 agent 文本回复之间穿插显示, 初始折叠, 点击展开查看详情。
@Composable
private fun ProcessEntryCard(message: ChatMessage, theme: MomoTheme) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val label = message.processLabel ?: "Processing..."
    val icon = when (message.processType) {
        "thinking" -> "Thinking"     // "Thinking"
        "tool_call" -> "ToolCall"                    // "ToolCall"
        "tool_result" -> "ToolResult"                // "ToolResult"
        "search" -> "Search"         // "Search"
        "computer" -> "Computer"     // "Computer"
        "image" -> "Image"           // "Image"
        "code" -> "Code"             // "Code"
        else -> "Processing"         // "..."
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MomoCorners.chip))
                .background(theme.bgSecondary)
                .clickable { expanded = !expanded }
                .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                icon,
                color = theme.accentPrimary.copy(alpha = 0.85f),
                style = momoTextStyle(MomoTypography.caption),
            )
            Spacer(Modifier.width(MomoSpacing.sm))
            Text(
                label,
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (expanded) "▲" else "▼",   // up/down triangle
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption),
            )
        }

        AnimatedVisibility(visible = expanded) {
            if (message.text.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = MomoSpacing.xl, top = MomoSpacing.xs)
                        .clip(RoundedCornerShape(MomoCorners.chip))
                        .background(theme.bgSecondary.copy(alpha = 0.5f))
                        .padding(MomoSpacing.sm)
                ) {
                    MarkdownMessageBody(content = message.text, theme = theme)
                }
            }
        }
    }
}

@Composable
private fun MessageActionPill(label: String, theme: MomoTheme, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(MomoCorners.button))
            .background(theme.inputBg.copy(alpha = 0.92f))
            .border(0.5.dp, theme.borderDefault, RoundedCornerShape(MomoCorners.button))
            .clickable(onClick = onClick)
            .padding(horizontal = MomoSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (danger) theme.danger else theme.textMuted,
            style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.SemiBold)),
            maxLines = 1,
        )
    }
}

/**
 * 统一的消息语音播放图标按钮(1v1 聊天 / 群聊共用): 圆形描边底 + 喇叭图标,
 * 按下时缩放并切换为强调色, 与 [MessageActionPill] 同高(28dp)便于在操作行里并排。
 * 取代原先两处不一致的播放控件(1v1 内联图标盒 / 群聊「播放」文字药丸)。
 */
@Composable
private fun MessagePlayIconButton(
    theme: MomoTheme,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color by animateColorAsState(
        targetValue = if (pressed) primary else onSurfaceVariant,
        animationSpec = tween(durationMillis = 150),
        label = "playIconColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "playIconScale",
    )
    Box(
        Modifier
            .size(28.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(theme.inputBg.copy(alpha = 0.92f))
            .border(0.5.dp, theme.borderDefault, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // loading: 取音频/连接中显示转圈, 出声后回到喇叭图标。
        if (loading) {
            CircularProgressIndicator(
                color = primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        } else {
            SpeakerIcon(color)
        }
    }
}

private fun messageBubbleShape(isUser: Boolean, continuation: Boolean = false, compact: Boolean = false): RoundedCornerShape =
    if (isUser) {
        // 用户消息: 右对齐实色气泡, 右下小圆角(指向自己), 其余大圆角。(1v1 与项目 session 统一)
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomEnd = 6.dp,
            bottomStart = 18.dp,
        )
    } else {
        // Mobius 回复: 全宽卡片式(参考图), 统一 16dp 圆角, 无气泡尾巴;
        // 续条(同轮多段回复)顶部圆角改方角, 视觉上与上一段相连。
        val topCorner = if (continuation) 4.dp else 16.dp
        RoundedCornerShape(
            topStart = topCorner,
            topEnd = topCorner,
            bottomEnd = 16.dp,
            bottomStart = 16.dp,
        )
    }

@Composable
private fun TypingBubble(theme: MomoTheme) {
    Row(
        Modifier.clip(messageBubbleShape(isUser = false)).background(theme.bubbleMomo).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
    ) {
        repeat(3) { Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFD2D2D4))) }
    }
}

// 群聊里 @agent 等待回复时的"正在输入"行：左侧光球 + 名称 + 三点动画。
@Composable
private fun GroupTypingRow(name: String, theme: MomoTheme) {
    Row(horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
        MomoLogo(24.dp, animated = true, lite = true)
        Spacer(Modifier.width(MomoSpacing.sm))
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.widthIn(max = 280.dp)) {
            Text(
                name,
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = MomoSpacing.xs, start = MomoSpacing.xs),
            )
            TypingBubble(theme)
        }
    }
}

@Composable
private fun SpeakingControlBar(
    message: ChatMessage?,
    canJump: Boolean,
    theme: MomoTheme,
    onJump: () -> Unit,
    onStop: () -> Unit,
    onDisableAuto: () -> Unit,
) {
    val pulseAlpha = rememberInfiniteTransition(label = "speakingControlPulse").animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speakingControlPulseAlpha",
    ).value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.bgSecondary)
            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm)
            .clip(RoundedCornerShape(MomoCorners.medium))
            .background(theme.inputBg)
            .border(1.dp, theme.accentPrimary.copy(alpha = pulseAlpha), RoundedCornerShape(MomoCorners.medium))
            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MomoSpacing.md),
    ) {
        SpeakingInlineIndicator(theme.accentPrimary, pulseAlpha)
        Column(Modifier.weight(1f)) {
            Text(
                "正在播放…",
                color = theme.textPrimary,
                style = momoTextStyle(MomoTypography.subheadline.copy(fontWeight = FontWeight.SemiBold)),
                maxLines = 1,
            )
            Text(
                speakingSummary(message),
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SpeakingActionButton(
            label = "跳到",
            enabled = canJump,
            theme = theme,
            onClick = onJump,
        )
        SpeakingActionButton(
            label = "暂停",
            enabled = true,
            danger = true,
            theme = theme,
            onClick = onStop,
        )
        SpeakingActionButton(
            label = "关闭",
            enabled = true,
            theme = theme,
            onClick = onDisableAuto,
        )
    }
}

@Composable
private fun SpeakingActionButton(
    label: String,
    enabled: Boolean,
    theme: MomoTheme,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val foreground = when {
        !enabled -> theme.textMuted.copy(alpha = 0.6f)
        danger -> theme.danger
        else -> theme.accentPrimary
    }
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(MomoCorners.button))
            .background(foreground.copy(alpha = if (enabled) 0.12f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = MomoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MomoSpacing.xs),
    ) {
        if (danger) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(MomoCorners.icon)).background(foreground))
        }
        Text(label, color = foreground, style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.SemiBold)), maxLines = 1)
    }
}

@Composable
private fun SpeakingInlineIndicator(color: Color, alpha: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MomoSpacing.xs),
    ) {
        SpeakerIcon(color.copy(alpha = alpha))
        repeat(3) { index ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((7 + index * 3).dp)
                    .clip(RoundedCornerShape(MomoCorners.icon))
                    .background(color.copy(alpha = (alpha - index * 0.14f).coerceIn(0.28f, 1f))),
            )
        }
    }
}

private fun speakingSummary(message: ChatMessage?): String {
    val raw = message?.voiceText?.takeIf { it.isNotBlank() } ?: message?.text.orEmpty()
    val normalized = raw.replace(Regex("""\s+"""), " ").trim()
    if (normalized.isBlank()) return "语音消息"
    return if (normalized.length <= 20) normalized else normalized.take(20) + "…"
}

@Composable
private fun MarkdownMessageBody(
    content: String,
    theme: MomoTheme,
) {
    // AI 卡面深色模式下为浅色卡 → 文字自动切深色保证可读。
    val bubbleBg = theme.bubbleMomo
    val lightCard = bubbleBg.luminance() > 0.5f
    val textColor = if (lightCard) Color(0xFF15161C) else theme.textPrimary
    val codeBackground = remember(bubbleBg) {
        if (bubbleBg.alpha == 0f) Color(0x14000000) else bubbleBg.copy(alpha = (bubbleBg.alpha * 0.6f).coerceAtLeast(0.1f))
    }
    val inlineCodeBackground = theme.accentPrimary.copy(alpha = if (theme.dark) 0.18f else 0.10f)
    val body = momoTextStyle(MomoTypography.body).copy(
        color = textColor,
    )
    val mono = body.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)
    val tableText = body.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val quoteStyle = body.copy(color = theme.textMuted, fontStyle = FontStyle.Italic)
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val hasCodeBlock = remember(content) { content.contains("```") }
    // GFM 要求表格块前有空行; LLM 输出常省略 → 渲染前补齐, 否则表格会被当成普通文本。
    val rendered = remember(content) { ensureBlankLineBeforeGfmTable(content) }
    val codeFenceBackground = remember(theme.dark, lightCard) {
        when {
            lightCard -> Color(0xFFE4E8F0)
            theme.dark -> Color(0xFF161A22)
            else -> Color(0xFFF6F8FA)
        }
    }
    val tableHeaderBackground = remember(theme.dark, lightCard) {
        when {
            lightCard -> Color(0xFFE0E5EE)
            theme.dark -> Color(0xFF1F2530)
            else -> Color(0xFFEEF1F5)
        }
    }
    // 排版层级(参考网页端 agent 回复卡片): 标题字号递减 + 全 Bold, 列表项间留呼吸感,
    // 块间距加大让"标题→列表→正文"分区更清晰。
    val blockPadding = markdownPadding(block = 10.dp, list = 6.dp, listItemBottom = 5.dp, indentList = 7.dp)
    Box(modifier = Modifier.fillMaxWidth()) {
        key(rendered, theme.themeMode, theme.palette) {
            Markdown(
                content = rendered,
                modifier = Modifier.fillMaxWidth(),
                padding = blockPadding,
                colors = markdownColor(
                    text = textColor,
                    codeText = textColor,
                    inlineCodeText = theme.accentPrimary,
                    linkText = theme.accentPrimary,
                    codeBackground = codeFenceBackground,
                    inlineCodeBackground = inlineCodeBackground,
                    dividerColor = theme.borderDefault,
                    tableText = textColor,
                    tableBackground = theme.bgSecondary,
                ),
                typography = markdownTypography(
                    text = body,
                    code = mono,
                    inlineCode = mono.copy(color = theme.accentPrimary),
                    h1 = momoTextStyle(MomoTypography.largeTitle).copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = textColor),
                    h2 = momoTextStyle(MomoTypography.headline).copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = textColor),
                    h3 = momoTextStyle(MomoTypography.body).copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = textColor),
                    h4 = momoTextStyle(MomoTypography.body).copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = textColor),
                    h5 = body.copy(fontWeight = FontWeight.SemiBold),
                    h6 = body.copy(fontWeight = FontWeight.SemiBold, color = theme.textMuted),
                    quote = quoteStyle,
                    paragraph = body,
                    ordered = body,
                    bullet = body,
                    list = body,
                    link = body.copy(
                        color = theme.accentPrimary,
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
                components = markdownComponents(
                    codeFence = { model ->
                        var expanded by remember(model.content) { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MomoSpacing.xs)
                                .clip(RoundedCornerShape(MomoCorners.medium))
                                .background(codeFenceBackground)
                                .border(1.dp, theme.borderDefault, RoundedCornerShape(MomoCorners.medium)),
                        ) {
                            MarkdownCodeFence(
                                content = model.content,
                                node = model.node,
                                block = { code, language ->
                                    val codeText = code.trimEnd()
                                    val lineCount = codeText.lineSequence().count()
                                    val collapsible = lineCount > 15
                                    val showBody = expanded || !collapsible
                                    // 语言标签 header: 语言名 + 行数 + 折叠/展开
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = collapsible) { expanded = !expanded }
                                            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.xs),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            language?.takeIf { it.isNotBlank() } ?: "代码",
                                            color = theme.textMuted,
                                            style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.Medium)),
                                        )
                                        Spacer(Modifier.width(MomoSpacing.sm))
                                        Text(
                                            "$lineCount 行",
                                            color = theme.textMuted.copy(alpha = 0.7f),
                                            style = momoTextStyle(MomoTypography.caption.copy(fontSize = 10.sp)),
                                        )
                                        Spacer(Modifier.weight(1f))
                                        if (collapsible) {
                                            Text(
                                                if (expanded) "收起 ▲" else "展开 ▼",
                                                color = theme.accentPrimary,
                                                style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.Medium)),
                                            )
                                        }
                                    }
                                    if (showBody) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.xs)
                                                .horizontalScroll(rememberScrollState()),
                                        ) {
                                            Text(
                                                text = codeText,
                                                style = mono,
                                                color = textColor,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    },
                    blockQuote = { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MomoSpacing.xs),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .padding(end = 0.dp)
                                    .background(theme.accentPrimary),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = MomoSpacing.md, end = MomoSpacing.xs),
                            ) {
                                MarkdownBlockQuote(
                                    content = model.content,
                                    node = model.node,
                                    style = quoteStyle,
                                )
                            }
                        }
                    },
                    table = { model ->
                        // 表格不加外边框: 仅浅色底 + 圆角, 行分隔线由 MarkdownTable 自绘, 更轻。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MomoSpacing.xs)
                                .clip(RoundedCornerShape(MomoCorners.medium))
                                .background(tableHeaderBackground.copy(alpha = 0.35f)),
                        ) {
                            Box(Modifier.horizontalScroll(rememberScrollState())) {
                                MarkdownTable(
                                    content = model.content,
                                    node = model.node,
                                    style = tableText,
                                )
                            }
                        }
                    },
                ),
            )
        }
        if (hasCodeBlock) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .clip(RoundedCornerShape(MomoCorners.medium))
                    .background(theme.inputBg.copy(alpha = 0.92f))
                    .border(1.dp, theme.borderDefault, RoundedCornerShape(MomoCorners.medium))
                    .clickable {
                        clipboard.setText(AnnotatedString(content))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    .padding(horizontal = MomoSpacing.sm, vertical = MomoSpacing.xs),
            ) {
                Text(
                    "复制",
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.Medium)),
                )
            }
        }
    }
}

@Composable
private fun rememberDecodedImage(bytes: ByteArray?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, bytes) {
        value = null
        value = withContext(Dispatchers.Default) {
            bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
        }
    }
    return bitmap
}

@Composable
private fun ChatInputBar(
    state: ComposerUiState,
    chatState: ChatState,
    theme: MomoTheme,
    vm: MomoAppViewModel,
) {
    val canSend = state.canSendComposerMessage()
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.bgSecondary)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = MomoSpacing.sm, vertical = MomoSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
    ) {
        if (state.attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
            ) {
                state.attachments.forEach { attachment ->
                    Row(
                        modifier = Modifier
                            .width(186.dp)
                            .clip(RoundedCornerShape(MomoCorners.button))
                            .background(theme.inputBg)
                            .border(
                                1.dp,
                                if (attachment.status == AttachmentStatus.Error) theme.danger else theme.borderDefault,
                                RoundedCornerShape(MomoCorners.button),
                            )
                            .padding(MomoSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(MomoCorners.medium)).background(theme.inputBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            val bitmap = rememberDecodedImage(attachment.previewBytes)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = attachment.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    attachment.name.substringAfterLast('.', "文件").take(4).uppercase(),
                                    color = theme.textMuted,
                                    style = momoTextStyle(MomoTypography.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold)),
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(attachment.name, color = theme.textPrimary, style = momoTextStyle(MomoTypography.caption), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (attachment.status) {
                                    AttachmentStatus.Uploading -> "上传中..."
                                    AttachmentStatus.Done -> formatAttachmentSize(attachment.size)
                                    AttachmentStatus.Error -> attachment.error.ifBlank { "上传失败" }
                                },
                                color = if (attachment.status == AttachmentStatus.Error) theme.danger else theme.textMuted,
                                style = momoTextStyle(MomoTypography.caption.copy(fontSize = 10.sp)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "×",
                            color = theme.textMuted,
                            style = momoTextStyle(MomoTypography.title),
                            modifier = Modifier.clickable { vm.removeAttachment(attachment.id) }.padding(MomoSpacing.xs),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
        ) {
            // 左: +号(附件选择), 与群聊一致
            IconButtonCircle(
                theme,
                size = 36.dp,
                background = Color.Transparent,
                borderColor = Color.Transparent,
                onClick = vm::pickAttachments,
            ) {
                PlusIcon(theme.textMuted)
            }
            // 中: 语音模式显示"按住说话", 否则输入框(白底圆角, 与群聊一致)。
            if (state.composerInputMode == ComposerInputMode.Text) {
                // 用 TextFieldValue 本地持有(含光标位置)；lastPushedText 区分"按键回灌回声"与
                // "真正外部重置(语音回填/发送清空/切会话)"，只有外部重置才回写 field，根治光标跳动。
                var field by remember { mutableStateOf(TextFieldValue(state.input)) }
                var lastPushedText by remember { mutableStateOf(state.input) }
                val externalInput = state.input
                LaunchedEffect(externalInput) {
                    if (externalInput != lastPushedText) {
                        field = TextFieldValue(externalInput, selection = TextRange(externalInput.length))
                        lastPushedText = externalInput
                    }
                }
                BasicTextField(
                    value = field,
                    onValueChange = { newValue ->
                        field = newValue
                        if (newValue.text != lastPushedText) {
                            lastPushedText = newValue.text
                            vm.setInput(newValue.text)
                        }
                    },
                    // 默认单行(40dp、文本垂直居中)；内容超出一行后随行数增高(上限 120dp ≈ 4-5 行)。
                    // decorationBox 用 fillMaxWidth() 而非 fillMaxSize()——后者会强制取 heightIn 的
                    // maxHeight(120dp)，导致输入框一开始就被撑成多行高度。fillMaxWidth 让高度由
                    // 内容驱动，被外层 heightIn(min=40,max=120) 钳制：单行→40dp 居中，多行→撑开。
                    singleLine = false,
                    textStyle = momoTextStyle(MomoTypography.body).copy(color = theme.textPrimary),
                    cursorBrush = SolidColor(theme.textPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = {
                        if (canSend) {
                            keyboard?.hide()
                            vm.sendHomeMessage()
                        }
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 120.dp)
                        // 参考图: 胶囊输入框(单行≈半圆, 多行时仍是圆角矩形), 浅灰底无边框。
                        .clip(RoundedCornerShape(20.dp))
                        .background(theme.inputBg)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (field.text.isBlank()) Text("说点什么…", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
                            inner()
                        }
                    },
                )
            } else {
                VoiceHoldButton(state, theme, vm, Modifier.weight(1f))
            }
            // 右: 有文字显示发送; 语音模式显示键盘; 否则显示语音(微信式切换)。
            // (不再提供"终止生成/取消发送"按钮——流式回复由轮次结束自然关闭。)
            when {
                state.composerInputMode == ComposerInputMode.Text && canSend -> SendButton(
                    canSend = true,
                    theme = theme,
                    onClick = {
                        keyboard?.hide()
                        vm.sendHomeMessage()
                    },
                )
                state.composerInputMode == ComposerInputMode.Voice -> Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable(
                        enabled = !state.voiceRecording && !state.voiceTranscribing,
                        onClick = vm::toggleComposerMode,
                    ),
                    contentAlignment = Alignment.Center,
                ) { KeyboardIcon(theme.textMuted) }
                else -> Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable(
                        enabled = !state.voiceRecording && !state.voiceTranscribing,
                        onClick = vm::toggleComposerMode,
                    ),
                    contentAlignment = Alignment.Center,
                ) { MicrophoneIcon(theme.textMuted) }
            }
        }
    }
}

@Composable
private fun VoiceHoldButton(
    state: ComposerUiState,
    theme: MomoTheme,
    vm: MomoAppViewModel,
    modifier: Modifier = Modifier,
    group: Boolean = false,
) {
    var dragY by remember { mutableStateOf(0f) }
    val background = when {
        state.voiceCanceling -> theme.danger
        state.voiceRecording -> theme.accentPrimary
        else -> theme.bgSecondary
    }
    Box(
        modifier = modifier
            .height(40.dp)
            .shadow(1.dp, RoundedCornerShape(MomoCorners.pill), clip = false)
            .clip(RoundedCornerShape(MomoCorners.pill))
            .background(background)
            .pointerInput(state.voiceTranscribing) {
                if (state.voiceTranscribing) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragY = 0f
                    vm.beginVoiceInput(group)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        dragY += change.positionChange().y
                        vm.updateVoiceDrag(dragY)
                        change.consume()
                        if (!change.pressed) {
                            vm.finishVoiceInput()
                            break
                        }
                    }
                    dragY = 0f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (state.voiceRecording) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    when {
                        state.voiceCanceling -> "松手取消"
                        state.voiceTranscribing -> "正在识别..."
                        state.voiceTranscript.isNotBlank() -> state.voiceTranscript
                        else -> "正在听..."
                    },
                    color = Color.White,
                    style = momoTextStyle(MomoTypography.caption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = MomoSpacing.md),
                )
                Spacer(Modifier.height(MomoSpacing.xs))
                VoiceVolumeMeter(state.voiceVolumeLevel)
            }
        } else if (state.voiceTranscribing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = theme.accentPrimary, strokeWidth = 2.dp)
                Text("正在识别...", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
            }
        } else {
            Text(
                if (state.speechPermissionDenied && !state.speechPermissionGranted) "麦克风不可用" else "按住说话",
                color = if (state.speechPermissionDenied && !state.speechPermissionGranted) theme.textMuted else theme.textPrimary,
                style = momoTextStyle(MomoTypography.body.copy(fontWeight = FontWeight.SemiBold)),
            )
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> {
        val tenths = bytes * 10 / (1024 * 1024)
        "${tenths / 10}.${tenths % 10} MB"
    }
}

@Composable
private fun VoiceVolumeMeter(level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(MomoSpacing.xs), verticalAlignment = Alignment.Bottom) {
        repeat(5) { index ->
            val active = index < level.coerceIn(0, 5)
            Box(
                Modifier
                    .width(5.dp)
                    .height((5 + index * 2).dp)
                    .clip(RoundedCornerShape(MomoCorners.icon))
                    .background(if (active) Color.White else Color.White.copy(alpha = 0.36f)),
            )
        }
    }
}

@Composable
private fun BoxScope.MenuPanel(open: Boolean, theme: MomoTheme, vm: MomoAppViewModel) {
    AnimatedVisibility(open, modifier = Modifier.align(Alignment.TopEnd)) {
        Column(
            Modifier
                .padding(MomoSpacing.md)
                .width(184.dp)
                .then(
                    if (!theme.dark) Modifier.shadow(MomoElevation.menu, RoundedCornerShape(MomoCorners.card), clip = false)
                    else Modifier
                )
                .clip(RoundedCornerShape(MomoCorners.card))
                .background(theme.bgSecondary)
                .border(0.5.dp, theme.borderDefault, RoundedCornerShape(MomoCorners.card)),
        ) {
            MenuItem("通讯录", theme) { vm.navigate(AppScreen.Contacts) }
            MenuItem("聊天列表", theme) { vm.navigate(AppScreen.ChatList) }
            MenuItem("分身列表", theme) { vm.navigate(AppScreen.Clones) }
            MenuItem("清空对话", theme) { vm.clearActiveConversation() }
            MenuItem("设置", theme) { vm.navigate(AppScreen.Settings) }
        }
    }
}

@Composable
private fun MenuItem(label: String, theme: MomoTheme, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp).clickable { onClick() }.padding(horizontal = MomoSpacing.lg), contentAlignment = Alignment.CenterStart) {
        Text(label, color = theme.textPrimary, style = momoTextStyle(MomoTypography.body))
    }
}

@Composable
private fun CloneRow(session: Session, cloneNumber: String?, theme: MomoTheme, onClick: () -> Unit) {
    val roleMain = session.isMainSession()
    Row(
        Modifier.fillMaxWidth().height(76.dp).background(theme.bgSecondary).clickable { onClick() }.padding(horizontal = MomoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MomoCloneAvatar(44.dp, isMain = roleMain, cloneNumber = if (roleMain) null else cloneNumber)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(session.name.ifBlank { "分身 Mobius" }, color = theme.textPrimary, style = momoTextStyle(MomoTypography.headline), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (roleMain) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(MomoCorners.chip)).background(theme.accentPrimary).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text("主体", color = Color.White, style = momoTextStyle(MomoTypography.caption))
                    }
                }
                if (session.jobAccomplished == true && session.jobFailed != true) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(MomoCorners.chip)).background(theme.success.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text("已完成", color = theme.success, style = momoTextStyle(MomoTypography.caption))
                    }
                }
            }
            Spacer(Modifier.height(MomoSpacing.xs))
            Text(session.description.ifBlank { "你好呀，我是 Mobius..." }, color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(formatBackendTime(session.lastActive).ifBlank { "—" }, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    theme: MomoTheme,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.lg)) {
        Text(
            title,
            color = theme.textMuted,
            style = momoTextStyle(MomoTypography.sectionHeader),
            modifier = Modifier.padding(start = MomoSpacing.xs, bottom = MomoSpacing.sm),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (!theme.dark) Modifier.shadow(MomoElevation.card, RoundedCornerShape(MomoCorners.card), clip = false)
                    else Modifier
                )
                .clip(RoundedCornerShape(MomoCorners.card))
                .background(theme.bgSecondary),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsHairline(theme: MomoTheme) {
    HorizontalDivider(
        color = theme.borderDefault,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 56.dp),
    )
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    theme: MomoTheme,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(theme.bgSecondary).padding(horizontal = MomoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = theme.textPrimary, style = momoTextStyle(MomoTypography.headline), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onClick() })
    }
    if (showDivider) SettingsHairline(theme)
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    theme: MomoTheme,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
        .background(theme.bgSecondary)
        .let { modifier -> if (onClick != null) modifier.clickable { onClick() } else modifier }
        .padding(horizontal = MomoSpacing.lg)
    Row(
        rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = theme.textPrimary, style = momoTextStyle(MomoTypography.headline), modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Text("›", color = theme.textMuted, style = momoTextStyle(MomoTypography.title))
        }
    }
    if (showDivider) SettingsHairline(theme)
}

@Composable
private fun SettingActionRow(
    label: String,
    theme: MomoTheme,
    danger: Boolean = false,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(theme.bgSecondary)
            .clickable { onClick() }
            .padding(horizontal = MomoSpacing.lg),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = if (danger) theme.danger else theme.accentPrimary, style = momoTextStyle(MomoTypography.headline))
    }
    if (showDivider) SettingsHairline(theme)
}

@Composable
private fun TtsPlaybackModeRow(
    label: String,
    description: String,
    selected: Boolean,
    theme: MomoTheme,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(theme.bgSecondary).clickable(onClick = onClick).padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = theme.textPrimary, style = momoTextStyle(MomoTypography.headline))
            Text(description, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
        }
        Spacer(Modifier.width(8.dp))
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = theme.accentPrimary))
    }
    if (showDivider) SettingsHairline(theme)
}

@Composable
private fun TtsPlaybackModeSection(
    mode: TtsPlaybackMode,
    theme: MomoTheme,
    onChange: (TtsPlaybackMode) -> Unit,
) {
    TtsPlaybackModeRow(
        label = "全部",
        description = "整条消息都会被朗读",
        selected = mode == TtsPlaybackMode.All,
        theme = theme,
        showDivider = true,
        onClick = { onChange(TtsPlaybackMode.All) },
    )
    TtsPlaybackModeRow(
        label = "只朗读关键结论",
        description = "只朗读 Mobius 标出的关键结论；详细回复仍显示在屏幕",
        selected = mode == TtsPlaybackMode.Selected,
        theme = theme,
        showDivider = true,
        onClick = { onChange(TtsPlaybackMode.Selected) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSelectionRow(
    voices: List<Voice>,
    selectedVoice: String,
    loading: Boolean,
    failed: Boolean,
    ttsConfigured: Boolean,
    theme: MomoTheme,
    showDivider: Boolean = true,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = remember(voices, selectedVoice) {
        if (selectedVoice == TTS_SYSTEM_VOICE_ID) "系统默认"
        else voices.firstOrNull { it.id == selectedVoice }?.label ?: "系统默认"
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(theme.bgSecondary).clickable {
            if (voices.isEmpty() && !loading && !failed) onRefresh()
            expanded = true
        }.padding(horizontal = MomoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("音色", color = theme.textPrimary, style = momoTextStyle(MomoTypography.headline), modifier = Modifier.weight(1f))
        Text(selectedLabel, color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text("›", color = theme.textMuted, style = momoTextStyle(MomoTypography.title))
    }
    if (showDivider) SettingsHairline(theme)
    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }, containerColor = theme.bgSecondary) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = MomoSpacing.xl)) {
                Text(
                    "选择音色",
                    color = theme.textPrimary,
                    style = momoTextStyle(MomoTypography.headline.copy(fontWeight = FontWeight.Bold)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.xl, vertical = MomoSpacing.sm),
                )
                VoiceSheetItem(
                    label = "系统默认",
                    description = "设备系统 TTS（离线、免费、即时）",
                    selected = selectedVoice == TTS_SYSTEM_VOICE_ID,
                    theme = theme,
                    onClick = {
                        onSelect(TTS_SYSTEM_VOICE_ID)
                        expanded = false
                    },
                )
                if (voices.isNotEmpty()) {
                    HorizontalDivider(color = theme.borderDefault, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = MomoSpacing.xl))
                    voices.forEach { voice ->
                        VoiceSheetItem(
                            label = voice.label,
                            description = voice.description,
                            selected = selectedVoice == voice.id,
                            theme = theme,
                            onClick = {
                                onSelect(voice.id)
                                expanded = false
                            },
                        )
                    }
                } else if (loading) {
                    Text(
                        "正在加载豆包音色…",
                        color = theme.textMuted,
                        style = momoTextStyle(MomoTypography.subheadline),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.xl, vertical = MomoSpacing.lg),
                    )
                } else if (failed) {
                    Text(
                        "豆包音色加载失败",
                        color = theme.danger,
                        style = momoTextStyle(MomoTypography.subheadline),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.xl, vertical = MomoSpacing.lg),
                    )
                    Box(
                        Modifier.fillMaxWidth().clickable { onRefresh() }.padding(horizontal = MomoSpacing.xl, vertical = MomoSpacing.md),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("重试", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.body))
                    }
                } else if (!ttsConfigured) {
                    Text(
                        "豆包 TTS 未配置，请联系管理员在后台填写豆包凭据。",
                        color = theme.danger,
                        style = momoTextStyle(MomoTypography.caption),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.xl, vertical = MomoSpacing.lg),
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().clickable { onRefresh() }.padding(horizontal = MomoSpacing.xl, vertical = MomoSpacing.md),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("加载豆包音色", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.body))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceSheetItem(
    label: String,
    description: String?,
    selected: Boolean,
    theme: MomoTheme,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = MomoSpacing.xl, vertical = MomoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = theme.textPrimary, style = momoTextStyle(MomoTypography.body.copy(fontWeight = FontWeight.W500)))
            if (!description.isNullOrBlank()) {
                Text(description, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
            }
        }
        Spacer(Modifier.width(8.dp))
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = theme.accentPrimary))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    ModalBottomSheet(onDismissRequest = vm::closePresetSheet, containerColor = theme.bgSecondary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(MomoSpacing.xl), verticalArrangement = Arrangement.spacedBy(MomoSpacing.lg)) {
            Text("Mobius 预设配置", color = theme.textPrimary, style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)))
            Text(
                "保存新预设会影响下一次主 Mobius Session。若当前 Mobius Session 与新预设不一致，需要确认关闭后台执行并重建。",
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.subheadline.copy(lineHeight = 18.sp)),
            )
            if (state.presetLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MomoSpacing.md)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = theme.accentPrimary, strokeWidth = 2.dp)
                    Text("正在读取当前预设...", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
                }
            } else {
                if (state.presetError.isNotBlank()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MomoCorners.card))
                            .background(theme.danger.copy(alpha = 0.10f))
                            .border(1.dp, theme.danger.copy(alpha = 0.28f), RoundedCornerShape(MomoCorners.card))
                            .padding(MomoSpacing.md),
                    ) {
                        Text(state.presetError, color = theme.danger, style = momoTextStyle(MomoTypography.subheadline.copy(lineHeight = 18.sp)))
                    }
                }
                Text("人设", color = theme.textMuted, style = momoTextStyle(MomoTypography.sectionHeader))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
                ) {
                    items(state.presetPersonalityOptions, key = { it.key }) { option ->
                        val selected = state.presetDraft.personality == option.key
                        PresetOptionRow(
                            title = option.label.ifBlank { option.key },
                            subtitle = option.description,
                            selected = selected,
                            theme = theme,
                            onClick = { vm.setPresetPersonality(option.key) },
                        )
                    }
                }
                Text("模型", color = theme.textMuted, style = momoTextStyle(MomoTypography.sectionHeader))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
                ) {
                    items(state.cloneModelOptions, key = { it.key }) { option ->
                        val selected = state.presetDraft.model == option.key
                        PresetOptionRow(
                            title = option.label.ifBlank { option.title.ifBlank { option.key } },
                            subtitle = option.sub,
                            selected = selected,
                            theme = theme,
                            onClick = { vm.setPresetModel(option.key) },
                        )
                    }
                }
                if (state.presetConfirmDelete) {
                    Text(
                        "确认后会关闭后台执行并永久删除「${state.presetConfirmSessionName}」，然后保存新预设并重建 Mobius Session。",
                        color = theme.danger,
                        style = momoTextStyle(MomoTypography.subheadline.copy(lineHeight = 18.sp)),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(MomoSpacing.md), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = vm::closePresetSheet, modifier = Modifier.weight(1f)) {
                            Text("取消", color = theme.textMuted)
                        }
                        Button(
                            onClick = { vm.saveAssistantPreset(deleteCurrentSession = true) },
                            enabled = !state.presetSaving,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(MomoCorners.button),
                            colors = ButtonDefaults.buttonColors(containerColor = theme.danger, contentColor = Color.White),
                        ) {
                            Text(if (state.presetSaving) "请稍候..." else "确认保存", style = momoTextStyle(MomoTypography.body.copy(fontWeight = FontWeight.Bold)))
                        }
                    }
                } else {
                    PrimaryButton("保存预设", state.presetSaving, theme) { vm.saveAssistantPreset() }
                }
            }
            Spacer(Modifier.height(MomoSpacing.xxl))
        }
    }
}

@Composable
private fun PresetOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    theme: MomoTheme,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MomoCorners.card))
            .background(if (selected) theme.accentPrimary.copy(alpha = 0.12f) else theme.inputBg)
            .border(1.dp, if (selected) theme.accentPrimary else theme.borderDefault, RoundedCornerShape(MomoCorners.card))
            .clickable { onClick() }
            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.textPrimary, style = momoTextStyle(MomoTypography.subheadline.copy(fontWeight = FontWeight.SemiBold)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(MomoSpacing.xs))
                Text(subtitle, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) Text("✓", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.headline.copy(fontWeight = FontWeight.Bold)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneSheet(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    ModalBottomSheet(onDismissRequest = vm::closeCloneSheet, containerColor = theme.bgSecondary) {
        Column(Modifier.fillMaxWidth().padding(MomoSpacing.xl), verticalArrangement = Arrangement.spacedBy(MomoSpacing.lg)) {
            Text("开一个分身 Mobius", color = theme.textPrimary, style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)))
            Text("分身会在当前 Mobius 任务单下创建独立 Session。", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
            MomoInput(state.cloneTitle, "分身名称", theme, onChange = vm::setCloneTitle)
            MomoInput(state.cloneDescription, "任务描述", theme, minHeight = 92.dp, onChange = vm::setCloneDescription)
            Text("选择模型", color = theme.textMuted, style = momoTextStyle(MomoTypography.sectionHeader))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
            ) {
                items(state.cloneModelOptions, key = { it.key }) { option ->
                    val selected = state.cloneModel == option.key
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MomoCorners.card))
                            .background(if (selected) theme.accentPrimary.copy(alpha = 0.12f) else theme.inputBg)
                            .border(1.dp, if (selected) theme.accentPrimary else theme.borderDefault, RoundedCornerShape(MomoCorners.card))
                            .clickable { vm.setCloneModel(option.key) }
                            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.label.ifBlank { option.title.ifBlank { option.key } },
                                color = theme.textPrimary,
                                style = momoTextStyle(MomoTypography.subheadline.copy(fontWeight = FontWeight.SemiBold)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (option.sub.isNotBlank()) {
                                Spacer(Modifier.height(MomoSpacing.xs))
                                Text(option.sub, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (selected) Text("✓", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.headline.copy(fontWeight = FontWeight.Bold)))
                    }
                }
            }
            PrimaryButton("+ 创建并启动", state.loading, theme, vm::createClone)
            Spacer(Modifier.height(MomoSpacing.xxl))
        }
    }
}

@Composable
private fun MomoInput(
    value: String,
    placeholder: String,
    theme: MomoTheme,
    password: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = 48.dp,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSubmit: (() -> Unit)? = null,
    onChange: (String) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val singleLine = minHeight <= 56.dp
    // 本地 state 持有输入文本（避免每次按键经 ViewModel 大 state 往返导致整树重组/光标跳动）。
    // lastPushed 区分"自己 onChange 回灌的回声"与"真正的外部重置（发送清空/切会话/语音回填）"：
    // 只有外部重置才回写本地 field，按键往返一律忽略，避免覆盖光标位置或把已输入文本回退。
    var field by remember { mutableStateOf(value) }
    var lastPushed by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != lastPushed && value != field) field = value
    }
    BasicTextField(
        value = field,
        onValueChange = { newValue ->
            field = newValue
            if (newValue != lastPushed) {
                lastPushed = newValue
                onChange(newValue)
            }
        },
        singleLine = singleLine,
        textStyle = momoTextStyle(MomoTypography.headline.copy(color = theme.textPrimary, lineHeight = 22.sp)),
        cursorBrush = SolidColor(theme.textPrimary),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onSubmit?.invoke() },
            onNext = { onSubmit?.invoke() },
        ),
        visualTransformation = if (password) PasswordVisualTransformation('•') else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().let { if (focusRequester != null) it.focusRequester(focusRequester) else it }.height(minHeight).clip(RoundedCornerShape(MomoCorners.medium)).background(theme.inputBg).border(1.dp, if (focused) theme.accentPrimary else theme.borderDefault, RoundedCornerShape(MomoCorners.medium)).onFocusChanged { focused = it.isFocused }.padding(horizontal = MomoSpacing.lg, vertical = if (singleLine) 0.dp else MomoSpacing.lg),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (field.isBlank()) Text(placeholder, color = theme.textMuted, style = momoTextStyle(MomoTypography.body.copy(fontSize = 16.sp, lineHeight = 22.sp)))
                inner()
            }
        },
    )
}

@Composable
private fun PrimaryButton(label: String, loading: Boolean, theme: MomoTheme, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Button(
        onClick = onClick,
        enabled = !loading,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().height(48.dp).scale(if (pressed && !loading) 0.96f else 1f),
        shape = RoundedCornerShape(MomoCorners.button),
        colors = ButtonDefaults.buttonColors(containerColor = theme.accentPrimary, contentColor = Color.White),
    ) {
        Text(if (loading) "请稍候..." else label, style = momoTextStyle(MomoTypography.headline.copy(fontWeight = FontWeight.Bold)))
    }
}

@Composable
private fun AvatarSquare(text: String, size: androidx.compose.ui.unit.Dp, brush: Brush) {
    Box(Modifier.size(size).clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = (size.value * 0.48).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UserAvatar(size: androidx.compose.ui.unit.Dp, displayName: String?) {
    val initial = remember(displayName) {
        displayName
            ?.trim()
            ?.firstOrNull()
            ?.toString()
            ?.uppercase()
            ?: "我"
    }
    // 方案A: 按名字稳定取一组靛蓝/紫/天蓝渐变(同名恒定, 不同名错开)。
    val gradient = remember(displayName) {
        val palettes = listOf(
            listOf(Color(0xFF6366F1), Color(0xFFA78BFA)),
            listOf(Color(0xFF8B5CF6), Color(0xFF38BDF8)),
            listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)),
        )
        palettes[kotlin.math.abs(displayName?.hashCode() ?: 0) % palettes.size]
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    gradient,
                    start = Offset(0f, 0f),
                    end = Offset(96f, 96f),
                ),
            )
            .border(1.dp, Color(0x73B3D9FF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val canvasSize = this.size
            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = canvasSize.minDimension * 0.34f,
                center = Offset(canvasSize.width * 0.35f, canvasSize.height * 0.30f),
            )
            drawCircle(
                color = Color(0x552563EB),
                radius = canvasSize.minDimension * 0.42f,
                center = Offset(canvasSize.width * 0.72f, canvasSize.height * 0.76f),
            )
        }
        Text(
            initial,
            color = Color.White,
            fontSize = (size.value * 0.44f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MomoLogo(
    size: androidx.compose.ui.unit.Dp,
    active: Boolean = false,
    animated: Boolean = false,
    lite: Boolean = true,
) {
    MoAvatar(sizeDp = size, active = active || animated, lite = lite)
}

// 群聊头像：取成员展示名首字符拼成一张图（最多 6 人），圆形裁剪、内部按人数自适应网格。
// 1 人居中；2 人左右；3 人上 1 下 2；4 人 2×2；5 人上 3 下 2；6 人 2×3。对齐网页/微信群头像。
private val GROUP_AVATAR_COLORS = listOf(
    Color(0xFF8B7FE6), // 紫
    Color(0xFF4FC3A1), // 绿
    Color(0xFFF2B544), // 黄
    Color(0xFF5BA8E8), // 蓝
    Color(0xFFEC7C7C), // 红
    Color(0xFF14B8A6), // 青
)

private fun avatarInitial(name: String): String =
    name.trim().firstOrNull()?.toString()?.uppercase() ?: "·"

private fun groupAvatarRows(count: Int): List<List<Int>> = when (count.coerceAtLeast(1)) {
    1 -> listOf(listOf(0))
    2 -> listOf(listOf(0, 1))
    3 -> listOf(listOf(0), listOf(1, 2))
    4 -> listOf(listOf(0, 1), listOf(2, 3))
    5 -> listOf(listOf(0, 1, 2), listOf(3, 4))
    else -> listOf(listOf(0, 1, 2), listOf(3, 4, 5))
}

@Composable
private fun GroupAvatar(
    names: List<String>,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val usable = names.filter { it.isNotBlank() }.take(6)
    val display = if (usable.isEmpty()) listOf("") else usable
    val rows = groupAvatarRows(display.size)
    val cols = rows.maxOf { it.size }
    // 字号按最密方向取，保证不溢出最小色块；色块本身用 weight(1f) 横向填满整行。
    val fontSize = (size.value / maxOf(rows.size, cols) * 0.6f).sp
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFFE9ECF2)),
    ) {
        Column(Modifier.fillMaxSize()) {
            rows.forEach { rowCells ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowCells.forEach { idx ->
                        // 每个成员(idx)各取一种背景色（成员数 ≤6，配色表 6 色，故每个色块都不同）。
                        val colorIdx = idx % GROUP_AVATAR_COLORS.size
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(GROUP_AVATAR_COLORS[colorIdx]),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                avatarInitial(display[idx]),
                                color = Color.White,
                                fontSize = fontSize,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

// 小莫/分身头像：动态光球(MomoLogo)。分身在右下角加序号徽标区分"分身几"。
@Composable
private fun MomoCloneAvatar(
    size: androidx.compose.ui.unit.Dp,
    isMain: Boolean,
    cloneNumber: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        MomoLogo(size, animated = true, lite = true)
        if (!isMain && !cloneNumber.isNullOrBlank()) {
            val badgeDp = (size.value * 0.5f).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badgeDp)
                    .border(1.5.dp, Color.White, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFF4F46E5)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    cloneNumber,
                    color = Color.White,
                    fontSize = (size.value * 0.24f).sp,
                    lineHeight = (size.value * 0.24f).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 0.dp, bottom = (size.value * 0.02f).dp),
                )
            }
        }
    }
}

@Composable
private fun IconButtonCircle(
    theme: MomoTheme,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    background: Color = theme.inputBg,
    borderColor: Color = theme.borderDefault,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .scale(if (pressed) 0.96f else 1f)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun SendButton(canSend: Boolean, theme: MomoTheme, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (canSend) theme.accentPrimary else theme.textMuted.copy(alpha = 0.30f)
    Box(
        Modifier
            .size(36.dp)
            .scale(if (pressed && canSend) 0.96f else 1f)
            .clip(CircleShape)
            .background(
                if (canSend) Brush.linearGradient(
                    colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
                ) else SolidColor(bg),
            )
            .clickable(
                enabled = canSend,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SendIcon(if (canSend) Color.White else Color.White.copy(alpha = 0.72f))
    }
}

@Composable
private fun ThemePaletteSelector(selected: ThemePalette, theme: MomoTheme, onSelect: (ThemePalette) -> Unit) {
    // 色卡网格选择器: 每个色卡用该色板真实配色渲染迷你预览(底色+双气泡+accent 按钮),
    // 按下缩放+触感反馈, 选中 accent 描边+角标对勾 — 即点即选、即时预览,
    // 取代旧"列表行+折叠更多"形式(无触感/无即时反馈)。
    Column(Modifier.fillMaxWidth().background(theme.bgSecondary).padding(MomoSpacing.md)) {
        ThemePalette.entries.forEachIndexed { index, palette ->
            if (index > 0) Spacer(Modifier.height(MomoSpacing.sm))
            ThemePaletteCard(
                palette = palette,
                selected = selected == palette,
                theme = theme,
                onClick = { onSelect(palette) },
            )
        }
    }
}

@Composable
private fun ThemePaletteCard(
    palette: ThemePalette,
    selected: Boolean,
    theme: MomoTheme,
    onClick: () -> Unit,
) {
    val colors = paletteSwatches(palette, theme.dark)
    val bg = if (theme.dark) colors.bgPrimaryDark else colors.bgPrimary
    val bubbleAi = if (theme.dark) colors.bubbleMomo else colors.bubbleMomo
    val accent = if (theme.dark) colors.accentSecondary else colors.accentPrimary
    val haptic = LocalHapticFeedback.current
    // 按下触感反馈: 按压缩放 + 轻触 haptic(选中的重要即时确认, 不是长按场景)。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "paletteCardScale",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(MomoCorners.card))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) theme.accentPrimary else theme.borderDefault,
                shape = RoundedCornerShape(MomoCorners.card),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 迷你聊天预览: 该色板下的 AI 气泡 + 用户气泡 + accent 发送块, 所见即所得。
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.width(64.dp),
        ) {
            Box(Modifier.size(width = 22.dp, height = 14.dp).clip(RoundedCornerShape(5.dp)).background(bubbleAi))
            Box(Modifier.size(width = 14.dp, height = 14.dp).clip(RoundedCornerShape(5.dp)).background(accent))
        }
        Spacer(Modifier.width(MomoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(palette.label, color = theme.textPrimary, style = momoTextStyle(MomoTypography.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)))
            Text(
                if (selected) "当前使用" else "轻点切换",
                color = if (selected) theme.accentPrimary else theme.textMuted,
                style = momoTextStyle(MomoTypography.caption.copy(fontSize = 10.sp)),
            )
        }
        // 选中态: accent 圆底 + 白色对勾(比行尾裸对勾更醒目)。
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) theme.accentPrimary else Color.Transparent)
                .border(
                    width = if (selected) 0.dp else 1.5.dp,
                    color = if (selected) Color.Transparent else theme.borderDefault,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text("✓", color = Color.White, style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.Bold)))
            }
        }
    }
}

@Composable
private fun ThemePalettePreview(theme: MomoTheme) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.bgPrimary)
            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MomoSpacing.md),
    ) {
        Box(
            Modifier
                .align(Alignment.End)
                .clip(messageBubbleShape(isUser = true))
                .background(theme.bubbleBg)
                .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
        ) {
            Text("你好，Mobius", color = Color.White, style = momoTextStyle(MomoTypography.subheadline.copy(lineHeight = 18.sp)))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
        ) {
            Box(
                Modifier
                    .clip(messageBubbleShape(isUser = false))
                    .background(theme.bubbleMomo)
                    .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
            ) {
                Text("我在", color = theme.textPrimary, style = momoTextStyle(MomoTypography.subheadline.copy(lineHeight = 18.sp)))
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(MomoCorners.button))
                    .background(theme.accentPrimary)
                    .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
            ) {
                Text("发送", color = Color.White, style = momoTextStyle(MomoTypography.subheadline.copy(fontWeight = FontWeight.SemiBold)))
            }
        }
    }
}

@Composable
private fun SettingSectionTitle(label: String, theme: MomoTheme) {
    Text(
        label,
        color = theme.textMuted,
        style = momoTextStyle(MomoTypography.sectionHeader),
        modifier = Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.sm),
    )
}

@Composable
private fun ToastBubble(text: String, theme: MomoTheme) {
    Box(Modifier.fillMaxSize().padding(top = 72.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.clip(RoundedCornerShape(MomoCorners.pill)).background(if (theme.dark) Color(0xEE2A2A2D) else Color(0xEE202124)).padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.sm)) {
            Text(text, color = Color.White, style = momoTextStyle(MomoTypography.subheadline))
        }
    }
}

// 通用二次确认弹窗: 删除聊天 / 清空消息 等破坏性操作统一走它.
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    theme: MomoTheme,
    confirmLabel: String = "删除",
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, color = theme.textPrimary, style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)))
        },
        text = {
            Text(message, color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline.copy(lineHeight = 18.sp)))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (danger) theme.danger else theme.accentPrimary, style = momoTextStyle(MomoTypography.body.copy(fontWeight = FontWeight.Bold)))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
            }
        },
        containerColor = theme.bgSecondary,
        titleContentColor = theme.textPrimary,
        textContentColor = theme.textMuted,
    )
}

// 左滑露出的红色删除背景. label 用于区分「删除」(群聊)与「清空」(小莫/分身).
@Composable
private fun SwipeDeleteBackground(theme: MomoTheme, label: String = "删除") {
    Box(
        Modifier.fillMaxSize().background(theme.danger).padding(end = MomoSpacing.xl),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(label, color = Color.White, style = momoTextStyle(MomoTypography.body.copy(fontWeight = FontWeight.Bold)))
    }
}

// 聊天行删除目标: kind 为 "session"(1v1 小莫/分身, 实为清空消息记录) 或 "conversation"(群聊/私聊).
private data class PendingChatDeletion(
    val kind: String,
    val id: String,
    val name: String,
    val isOwner: Boolean = false,
    val isDirect: Boolean = false,
)

// 可删除聊天行: 左滑(SwipeToDismissBox, 仅 EndToStart)或长按触发删除回调.
// 点击仍打开会话. actionLabel 用于区分「删除」(群聊)与「清空」(小莫/分身)的左滑背景文案.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeletableChatRow(
    theme: MomoTheme,
    deleteEnabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    actionLabel: String = "删除",
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (deleteEnabled && value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                false
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeDeleteBackground(theme, actionLabel) },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = deleteEnabled,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // 长按直接弹确认弹窗(小莫/分身=清空消息, 群聊=删除/解散).
                            onDelete()
                        },
                    )
                },
        ) {
            content()
        }
    }
}

@Composable
private fun PlusIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        drawLine(color, Offset(size.width * 0.16f, size.height * 0.50f), Offset(size.width * 0.84f, size.height * 0.50f), strokeWidth = 2.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.50f, size.height * 0.16f), Offset(size.width * 0.50f, size.height * 0.84f), strokeWidth = 2.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun KeyboardIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        repeat(3) { row ->
            repeat(3) { column ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * (0.18f + column * 0.26f), size.height * (0.18f + row * 0.26f)),
                    size = Size(size.width * 0.12f, size.height * 0.12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f),
                )
            }
        }
    }
}

@Composable
private fun MicrophoneIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(width = 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawRoundRect(color, topLeft = Offset(size.width * 0.34f, size.height * 0.13f), size = Size(size.width * 0.32f, size.height * 0.46f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f), style = stroke)
        drawArc(color, startAngle = 35f, sweepAngle = 110f, useCenter = false, topLeft = Offset(size.width * 0.22f, size.height * 0.35f), size = Size(size.width * 0.56f, size.height * 0.44f), style = stroke)
        drawLine(color, Offset(size.width * 0.50f, size.height * 0.79f), Offset(size.width * 0.50f, size.height * 0.88f), strokeWidth = 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.36f, size.height * 0.88f), Offset(size.width * 0.64f, size.height * 0.88f), strokeWidth = 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun SendIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.20f)
            lineTo(size.width * 0.84f, size.height * 0.50f)
            lineTo(size.width * 0.18f, size.height * 0.80f)
            lineTo(size.width * 0.30f, size.height * 0.55f)
            lineTo(size.width * 0.56f, size.height * 0.50f)
            lineTo(size.width * 0.30f, size.height * 0.45f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun SpeakerIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = Stroke(width = 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val body = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.40f)
            lineTo(size.width * 0.30f, size.height * 0.40f)
            lineTo(size.width * 0.52f, size.height * 0.24f)
            lineTo(size.width * 0.52f, size.height * 0.76f)
            lineTo(size.width * 0.30f, size.height * 0.60f)
            lineTo(size.width * 0.12f, size.height * 0.60f)
            close()
        }
        drawPath(body, color)
        drawArc(color, startAngle = -35f, sweepAngle = 70f, useCenter = false, topLeft = Offset(size.width * 0.48f, size.height * 0.32f), size = Size(size.width * 0.30f, size.height * 0.36f), style = stroke)
    }
}

// 放大镜(搜索)图标: 镜片圆 + 手柄, 与 SpeakerIcon 同一描边风格.
@Composable
private fun SearchIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        drawCircle(
            color,
            radius = size.minDimension * 0.20f,
            center = Offset(size.width * 0.40f, size.height * 0.40f),
            style = Stroke(width = 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawLine(
            color,
            Offset(size.width * 0.55f, size.height * 0.55f),
            Offset(size.width * 0.82f, size.height * 0.82f),
            strokeWidth = 1.6f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

// 清除(×)图标, 搜索框有内容时点按清空.
@Composable
private fun ClearIcon(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(color, Offset(size.width * 0.30f, size.height * 0.30f), Offset(size.width * 0.70f, size.height * 0.70f), strokeWidth = 1.6f, cap = cap)
        drawLine(color, Offset(size.width * 0.70f, size.height * 0.30f), Offset(size.width * 0.30f, size.height * 0.70f), strokeWidth = 1.6f, cap = cap)
    }
}

// 齿轮(设置)图标: 外圈 8 齿 + 中心圆孔, 与其它 Canvas 图标同风格(1.6f 线宽/圆帽)。
@Composable
private fun GearIcon(color: Color, sizeDp: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val stroke = Stroke(width = 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val center = Offset(size.width / 2f, size.height / 2f)
        // 外齿圈(含圆孔的完整齿轮轮廓)
        drawCircle(color, radius = size.minDimension * 0.30f, center = center, style = stroke)
        // 8 根辐射状齿: 从外圆向四角/正交方向伸出。
        val toothInner = size.minDimension * 0.30f
        val toothOuter = size.minDimension * 0.42f
        repeat(8) { i ->
            // 不用 java.lang.Math(Kotlin Native 不解析裸 Math): 角度→弧度手算。
            val angleRad = (i * 45.0) * (kotlin.math.PI / 180.0)
            val cos = kotlin.math.cos(angleRad).toFloat()
            val sin = kotlin.math.sin(angleRad).toFloat()
            drawLine(
                color,
                Offset(center.x + toothInner * cos, center.y + toothInner * sin),
                Offset(center.x + toothOuter * cos, center.y + toothOuter * sin),
                strokeWidth = 1.8f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        // 中心圆孔
        drawCircle(color, radius = size.minDimension * 0.11f, center = center, style = stroke)
    }
}

@Composable
private fun CheckIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        drawLine(color, Offset(size.width * 0.20f, size.height * 0.54f), Offset(size.width * 0.42f, size.height * 0.74f), strokeWidth = 2.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.42f, size.height * 0.74f), Offset(size.width * 0.82f, size.height * 0.28f), strokeWidth = 2.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private fun momoTheme(themeMode: ThemeMode, palette: ThemePalette, dark: Boolean): MomoTheme {
    val colors = paletteSwatches(palette, dark)
    return MomoTheme(
        themeMode = themeMode,
        palette = palette,
        dark = dark,
        accentPrimary = colors.accentPrimary,
        accentSecondary = colors.accentSecondary,
        bgPrimary = if (dark) colors.bgPrimaryDark else colors.bgPrimary,
        bgSecondary = if (dark) colors.bgSecondaryDark else colors.bgSecondary,
        textPrimary = if (dark) colors.textPrimaryDark else colors.textPrimary,
        textMuted = if (dark) colors.textMutedDark else colors.textMuted,
        borderDefault = if (dark) colors.borderDark else colors.border,
        inputBg = if (dark) colors.inputBgDark else colors.inputBg,
        bubbleBg = colors.bubbleUser,
        bubbleMomo = colors.bubbleMomo,
        danger = if (dark) colors.dangerDark else colors.danger,
        success = if (dark) colors.successDark else colors.success,
    )
}

private data class PaletteColors(
    val accentPrimary: Color,
    val accentSecondary: Color,
    val bgPrimary: Color,
    val bgPrimaryDark: Color,
    val bgSecondary: Color,
    val bgSecondaryDark: Color,
    val bubbleUser: Color,
    val bubbleMomo: Color,
    val textPrimary: Color,
    val textPrimaryDark: Color,
    val textMuted: Color,
    val textMutedDark: Color,
    val border: Color,
    val borderDark: Color,
    val inputBg: Color,
    val inputBgDark: Color,
    val danger: Color,
    val dangerDark: Color,
    val success: Color,
    val successDark: Color,
)

private fun paletteSwatches(palette: ThemePalette, dark: Boolean): PaletteColors = when (palette) {
    // Hypergrid(ui-ux-pro-max skill: AI-Native UI + Bento): 深空底 + AI Purple #6366F1 单强调。
    // 深色为主设计; 浅色保留同 hue 的"晨雾"变体。
    ThemePalette.Default -> PaletteColors(
        accentPrimary = if (dark) Color(0xFF818CF8) else Color(0xFF4F46E5),
        accentSecondary = if (dark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
        bgPrimary = if (dark) Color(0xFF0A0B10) else Color(0xFFF3F4F8),
        bgPrimaryDark = Color(0xFF0A0B10),
        bgSecondary = if (dark) Color(0xFF12141C) else Color(0xFFFFFFFF),
        bgSecondaryDark = Color(0xFF12141C),
        bubbleUser = if (dark) Color(0xFF6366F1) else Color(0xFF4F46E5),
        // agent 回复卡(无 border, 纯背景色):
        // 浅色模式 = 比页面底(#F3F4F8)深一档的灰蓝 #E4E9F2;
        // 深色模式 = 浅灰白卡 #EDF0F5(深底白卡, 参考图)。文字色按卡面亮度自适应。
        bubbleMomo = if (dark) Color(0xFFEDF0F5) else Color(0xFFE4E9F2),
        textPrimary = if (dark) Color(0xFFF2F3F7) else Color(0xFF15161C),
        textPrimaryDark = Color(0xFFF2F3F7),
        textMuted = if (dark) Color(0xFF8B8FA3) else Color(0xFF8A8FA3),
        textMutedDark = Color(0xFF8B8FA3),
        border = if (dark) Color(0xFF23262F) else Color(0xFFE4E6EE),
        borderDark = Color(0xFF23262F),
        inputBg = if (dark) Color(0xFF171A24) else Color(0xFFEDEFF5),
        inputBgDark = Color(0xFF171A24),
        danger = Color(0xFFEF4444),
        dangerDark = Color(0xFFF87171),
        success = if (dark) Color(0xFF10B981) else Color(0xFF0E9F6E),
        successDark = Color(0xFF34D399),
    )
    ThemePalette.Aurora -> PaletteColors(
        accentPrimary = Color(0xFFB95218),
        accentSecondary = Color(0xFFF3A536),
        bgPrimary = Color(0xFFFBF3EB),
        bgPrimaryDark = Color(0xFF17120F),
        bgSecondary = Color(0xFFFFFCF8),
        bgSecondaryDark = Color(0xFF241B16),
        bubbleUser = Color(0xFFB95218),
        bubbleMomo = if (dark) Color(0xFF302119) else Color(0xFFFFF8F0),
        textPrimary = Color(0xFF241914),
        textPrimaryDark = Color(0xFFFFF3EA),
        textMuted = Color(0xFF745D50),
        textMutedDark = Color(0xFFC9AA98),
        border = Color(0xFFEAD9CB),
        borderDark = Color(0xFF473227),
        inputBg = Color(0xFFF3E7DC),
        inputBgDark = Color(0xFF2E231D),
        danger = Color(0xFFFF3B30),
        dangerDark = Color(0xFFFF453A),
        success = Color(0xFF34C759),
        successDark = Color(0xFF30D158),
    )
    ThemePalette.Mint -> PaletteColors(
        accentPrimary = Color(0xFF0E7652),
        accentSecondary = Color(0xFF2C9A8A),
        bgPrimary = Color(0xFFEEF8F2),
        bgPrimaryDark = Color(0xFF0D1713),
        bgSecondary = Color(0xFFFAFFFC),
        bgSecondaryDark = Color(0xFF16231E),
        bubbleUser = Color(0xFF0E7652),
        bubbleMomo = if (dark) Color(0xFF1D3029) else Color(0xFFFFFFFF),
        textPrimary = Color(0xFF12241C),
        textPrimaryDark = Color(0xFFF1FFF8),
        textMuted = Color(0xFF557065),
        textMutedDark = Color(0xFFA7C4B7),
        border = Color(0xFFD8E8DF),
        borderDark = Color(0xFF2D473D),
        inputBg = Color(0xFFE6F1EA),
        inputBgDark = Color(0xFF20332B),
        danger = Color(0xFFFF3B30),
        dangerDark = Color(0xFFFF453A),
        success = Color(0xFF34C759),
        successDark = Color(0xFF30D158),
    )
    ThemePalette.Coral -> PaletteColors(
        accentPrimary = Color(0xFFC73570),
        accentSecondary = Color(0xFF9D5AE6),
        bgPrimary = Color(0xFFF9F0F5),
        bgPrimaryDark = Color(0xFF171017),
        bgSecondary = Color(0xFFFFFBFE),
        bgSecondaryDark = Color(0xFF241924),
        bubbleUser = Color(0xFFC73570),
        bubbleMomo = if (dark) Color(0xFF302132) else Color(0xFFFFF7FB),
        textPrimary = Color(0xFF251722),
        textPrimaryDark = Color(0xFFFFF3FB),
        textMuted = Color(0xFF715867),
        textMutedDark = Color(0xFFC8A9BB),
        border = Color(0xFFEAD6E2),
        borderDark = Color(0xFF493248),
        inputBg = Color(0xFFF2E4ED),
        inputBgDark = Color(0xFF2F2330),
        danger = Color(0xFFFF3B30),
        dangerDark = Color(0xFFFF453A),
        success = Color(0xFF34C759),
        successDark = Color(0xFF30D158),
    )
    ThemePalette.Gold -> PaletteColors(
        accentPrimary = Color(0xFF92560D),
        accentSecondary = Color(0xFF0D9488),
        bgPrimary = Color(0xFFF8F4E8),
        bgPrimaryDark = Color(0xFF14130E),
        bgSecondary = Color(0xFFFFFDF7),
        bgSecondaryDark = Color(0xFF201E16),
        bubbleUser = Color(0xFF92560D),
        bubbleMomo = if (dark) Color(0xFF2C281C) else Color(0xFFFFFAEE),
        textPrimary = Color(0xFF211C10),
        textPrimaryDark = Color(0xFFFFF8E7),
        textMuted = Color(0xFF6C6250),
        textMutedDark = Color(0xFFC2B394),
        border = Color(0xFFE7DDC8),
        borderDark = Color(0xFF403A2C),
        inputBg = Color(0xFFF0E8D6),
        inputBgDark = Color(0xFF2A261C),
        danger = Color(0xFFFF3B30),
        dangerDark = Color(0xFFFF453A),
        success = Color(0xFF34C759),
        successDark = Color(0xFF30D158),
    )
    // 科技感: 深空底色 + 青蓝/电紫双 accent, 冷色低饱和, 深色模式为"星云深蓝"。
    ThemePalette.Nebula -> PaletteColors(
        accentPrimary = if (dark) Color(0xFF38BDF8) else Color(0xFF0284C7),
        accentSecondary = if (dark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
        bgPrimary = Color(0xFFF4F7FB),
        bgPrimaryDark = Color(0xFF0A0E1A),
        bgSecondary = Color(0xFFFFFFFF),
        bgSecondaryDark = Color(0xFF111827),
        bubbleUser = if (dark) Color(0xFF38BDF8) else Color(0xFF0284C7),
        bubbleMomo = if (dark) Color(0xFF151D2E) else Color(0xFFFFFFFF),
        textPrimary = Color(0xFF0F172A),
        textPrimaryDark = Color(0xFFE2E8F0),
        textMuted = Color(0xFF64748B),
        textMutedDark = Color(0xFF94A3B8),
        border = Color(0xFFDDE5F0),
        borderDark = Color(0xFF1E293B),
        inputBg = Color(0xFFE9EFF7),
        inputBgDark = Color(0xFF1B2437),
        danger = Color(0xFFFF3B30),
        dangerDark = Color(0xFFFF453A),
        success = Color(0xFF34C759),
        successDark = Color(0xFF30D158),
    )
}

private fun cloneBrush(seed: String): Brush {
    val colors = listOf(
        listOf(Color(0xFFFF8B5F), Color(0xFFFF625C)),
        listOf(Color(0xFF36D675), Color(0xFF20B65D)),
        listOf(Color(0xFFB86AF6), Color(0xFF8B55F6)),
    )
    return Brush.linearGradient(colors[kotlin.math.abs(seed.hashCode()) % colors.size])
}

// ===== Hypergrid 动效层(skill 规范: 入场 fade+16dp 位移, 350ms decelerate; 列表交错 40ms) =====

/** 卡片入场: 首次进入组合时 fade + 上移(模拟 power2.out 到达减速)。index 用于交错。 */
@Composable
internal fun HypergridReveal(index: Int = 0, content: @Composable () -> Unit) {
    // iOS 可靠性: 不用 LaunchedEffect 驱动 alpha(偶发不提交→卡0→卡不可见)。
    // 改为 remember 初值即完成态 + animateFloatAsState 从初值起播 — 组合期就有值,
    // 最坏情况(动画不跑)也只是无入场效果, 永不隐形。
    var progressTarget by remember { mutableStateOf(0f) }
    progressTarget = 1f
    val progress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(durationMillis = 350, delayMillis = (index * 40).coerceAtMost(240), easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)),
        label = "hgReveal",
    )
    Box(
        Modifier.graphicsLayer {
            alpha = progress.coerceAtLeast(0.01f)
            translationY = (1f - progress) * 16.dp.toPx()
        },
    ) { content() }
}

/** 统计数字 count-up: 0→N 600ms 滚动(等宽字体数据读出感)。 */
@Composable
internal fun CountUpNumber(target: Int, color: Color) {
    // iOS 保底: 组合期直接给 1f(不依赖 LaunchedEffect), 动画只做锦上添花。
    var countTarget by remember { mutableStateOf(0f) }
    countTarget = target.toFloat()
    val value by animateFloatAsState(
        targetValue = countTarget,
        animationSpec = tween(durationMillis = 600, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
        label = "countUp",
    )
    Text(
        value.toInt().toString(),
        color = color,
        style = momoMonoStyle(size = 18, color = color, weight = FontWeight.Bold),
    )
}

/** HUD 角标卡: 四角 6dp L 形细线(科技感 readout 框), 悬浮在卡片容器外框。 */
@Composable
internal fun HudCorners(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val len = 7.dp.toPx()
        val stroke = 1.2.dp.toPx()
        val c = color.copy(alpha = 0.55f)
        val w = size.width
        val h = size.height
        // 四角 L 形
        drawLine(c, Offset.Zero, Offset(len, 0f), stroke)
        drawLine(c, Offset.Zero, Offset(0f, len), stroke)
        drawLine(c, Offset(w - len, 0f), Offset(w, 0f), stroke)
        drawLine(c, Offset(w, 0f), Offset(w, len), stroke)
        drawLine(c, Offset(0f, h - len), Offset(0f, h), stroke)
        drawLine(c, Offset(0f, h), Offset(len, h), stroke)
        drawLine(c, Offset(w, h - len), Offset(w, h), stroke)
        drawLine(c, Offset(w - len, h), Offset(w, h), stroke)
    }
}

/** 扫描线: 一条横向亮线自上而下缓慢移动(HUD 扫描效果, 深色卡上)。 */
@Composable
internal fun ScanLine(modifier: Modifier = Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "scan")
    val y by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Restart),
        label = "scanY",
    )
    Canvas(modifier) {
        val yPos = size.height * y
        drawRect(
            color = color.copy(alpha = 0.10f),
            topLeft = Offset(0f, yPos - 8.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(size.width, 16.dp.toPx()),
        )
        drawLine(color.copy(alpha = 0.30f), Offset(0f, yPos), Offset(size.width, yPos), 1.dp.toPx())
    }
}

// 取展示名首字符作分组键: 拉丁取大写字母, 其它(中文/数字/符号)归 "#"。
internal fun initialOf(name: String): String {
    val first = name.trim().firstOrNull() ?: return "#"
    return if (first.isLetter() && first.code < 128) first.uppercase() else "#"
}

private fun statusColor(session: Session, theme: MomoTheme): Color = when {
    session.jobFailed == true || session.agentStatus.contains("fail", true) -> theme.danger
    session.agentStatus.contains("run", true) || session.agentStatus.contains("work", true) -> theme.success
    session.jobAccomplished == true -> theme.success
    else -> Color(0xFFB5B7BD)
}

// 聊天页任务进度条: 顶栏下细条, agent 状态一目了然(后台任务"后续通知"有了可见进度)。
// running/waiting → 绿点呼吸 + "任务进行中"; completed → "任务已完成"; failed → "任务失败"。
// idle/空 不显示。typing(流式回复中)优先显示思考态。
@Composable
private fun AgentStatusStrip(status: String, theme: MomoTheme, typing: Boolean = false) {
    val normalized = status.trim().lowercase()
    val (label, color, pulsing) = when {
        typing -> Triple("Mobius 正在回复…", theme.accentPrimary, true)
        normalized.isEmpty() || normalized == "idle" -> return
        normalized.contains("fail") -> Triple("任务失败", theme.danger, false)
        normalized == "completed" -> Triple("任务已完成", theme.success, false)
        normalized.contains("run") || normalized.contains("wait") || normalized.contains("work") ->
            Triple("任务进行中", theme.success, true)
        else -> return
    }
    val pulseAlpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "agentStatusPulse")
        t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "agentStatusPulseAlpha",
        ).value
    } else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.bgSecondary)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .graphicsLayer { alpha = pulseAlpha }
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = color,
            style = momoMonoStyle(size = 9, color = color, weight = FontWeight.Bold),
        )
    }
}

// ===== 项目钻取 UI(项目 -> Issue -> Session -> 聊天) =====

// 骨架屏占位块: 呼吸式闪烁的浅灰圆角块, 用于加载态替代转圈。
// 透明度保持低位(0.12→0.28), 视觉上是"浅灰底纹"而非深灰块, 更柔和。
@Composable
private fun SkeletonBlock(width: Dp, height: Dp, theme: MomoTheme, corner: Dp = MomoCorners.chip) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(theme.borderDefault.copy(alpha = alpha)),
    )
}

// 列表骨架屏: 模拟 N 行"头像 + 两行文本"占位, 用于项目/Issue/Session 列表加载。
@Composable
private fun ListSkeleton(rows: Int = 8, theme: MomoTheme) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.lg)) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = MomoSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(44.dp, 44.dp, theme, corner = MomoCorners.medium)
                Spacer(Modifier.width(MomoSpacing.md))
                Column(Modifier.weight(1f)) {
                    SkeletonBlock(120.dp, 14.dp, theme)
                    Spacer(Modifier.height(MomoSpacing.sm))
                    SkeletonBlock(180.dp, 11.dp, theme)
                }
            }
        }
    }
}

// 聊天列表骨架屏: 模拟"圆形头像(含未读徽标位) + 名称/摘要 + 时间"的会话行(72dp 高)。
@Composable
private fun ChatListSkeleton(rows: Int = 7, theme: MomoTheme) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.lg)) {
        repeat(rows) { i ->
            Row(
                Modifier.fillMaxWidth().height(72.dp).padding(vertical = MomoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(44.dp, 44.dp, theme, corner = MomoCorners.pill)
                Spacer(Modifier.width(MomoSpacing.md))
                Column(Modifier.weight(1f)) {
                    SkeletonBlock(110.dp + 24.dp * (i % 3), 14.dp, theme)
                    Spacer(Modifier.height(MomoSpacing.sm))
                    SkeletonBlock(170.dp + 40.dp * (i % 2), 11.dp, theme)
                }
                Spacer(Modifier.width(MomoSpacing.md))
                Column(horizontalAlignment = Alignment.End) {
                    SkeletonBlock(34.dp, 10.dp, theme)
                    Spacer(Modifier.height(MomoSpacing.xs))
                    SkeletonBlock(18.dp, 18.dp, theme, corner = MomoCorners.pill)
                }
            }
        }
    }
}

// 通讯录骨架屏: 模拟"圆形首字头像 + 名称(单行)"的成员行(56dp 高, 行高低于聊天)。
@Composable
private fun ContactsSkeleton(rows: Int = 10, theme: MomoTheme) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.lg)) {
        repeat(rows) { i ->
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(vertical = MomoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(40.dp, 40.dp, theme, corner = MomoCorners.pill)
                Spacer(Modifier.width(MomoSpacing.md))
                SkeletonBlock(96.dp + 22.dp * (i % 4), 14.dp, theme)
            }
        }
    }
}

// 「我」页骨架屏: 顶部用户卡(56dp 头像 + 名称/角色) + 分节标题 + 分身行占位。
@Composable
private fun ProfileSkeleton(theme: MomoTheme) {
    Column(Modifier.fillMaxWidth()) {
        // 用户信息卡
        Row(
            Modifier.fillMaxWidth().background(theme.bgSecondary).padding(MomoSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBlock(56.dp, 56.dp, theme, corner = MomoCorners.pill)
            Spacer(Modifier.width(MomoSpacing.md))
            Column {
                SkeletonBlock(120.dp, 16.dp, theme)
                Spacer(Modifier.height(MomoSpacing.sm))
                SkeletonBlock(48.dp, 11.dp, theme)
            }
        }
        HorizontalDivider(color = theme.borderDefault, thickness = 0.6.dp)
        Column(Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.lg)) {
            Spacer(Modifier.height(MomoSpacing.lg))
            SkeletonBlock(88.dp, 12.dp, theme)
            repeat(3) { i ->
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(vertical = MomoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkeletonBlock(44.dp, 44.dp, theme, corner = MomoCorners.pill)
                    Spacer(Modifier.width(MomoSpacing.md))
                    Column {
                        SkeletonBlock(104.dp + 26.dp * (i % 3), 14.dp, theme)
                        Spacer(Modifier.height(MomoSpacing.xs))
                        SkeletonBlock(150.dp, 10.dp, theme)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ProjectsScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val listState = rememberLazyListState()
    val selectedTab = state.projectsSelectedTab
    var plusMenuOpen by remember { mutableStateOf(false) }
    // 展示当前用户有权查看的全部项目(后端 GET /api/projects 已按 readableProjectsForUser 返回),
    // 不再仅限 createdBy == 自己; 按网页端同款规则排序 + 顶部 tab(全部/活跃/收藏/扩展)过滤。
    val userId = state.user?.id.orEmpty()
    val activeWindowDays = state.projectsActiveWindowDays
    val visible by remember(state.projects, state.projectsSearchText, selectedTab, activeWindowDays, userId) {
        derivedStateOf {
            state.projects
                .filter { it.id.isNotBlank() }
                .sortedWithProjects()
                .let { selectedTab.filter(it, activeWindowDays = activeWindowDays, userId = userId) }
                .let { list ->
                    val q = state.projectsSearchText.trim()
                    if (q.isEmpty()) list
                    else list.filter { it.name.contains(q, ignoreCase = true) }
                }
        }
    }
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = "项目",
            theme = theme,
            largeTitle = "项目",
            subtitle = if (state.projects.isNotEmpty()) "${state.projects.size} 个项目" else null,
            scrollState = listState,
            rightContent = {
                Box {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(MomoCorners.pill))
                            .clickable { plusMenuOpen = true }
                            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.xs),
                    ) {
                        // 设置(齿轮)图标 — 菜单含"创建项目/切换模式", 不再暗示单纯"新建"。
                        GearIcon(theme.accentPrimary)
                    }
                    DropdownMenu(
                        expanded = plusMenuOpen,
                        onDismissRequest = { plusMenuOpen = false },
                        modifier = Modifier.background(Color.Transparent),
                        shape = RoundedCornerShape(18.dp),
                        containerColor = theme.bgSecondary,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        MenuActionRow(
                            icon = { PlusIcon(theme.accentPrimary) },
                            label = "创建项目",
                            theme = theme,
                            onClick = {
                                plusMenuOpen = false
                                vm.openProjectEditor()
                            },
                        )
                        MenuActionRow(
                            icon = {
                                Text(
                                    if (state.projectsLiteMode) "典" else "简",
                                    color = theme.accentPrimary,
                                    style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.SemiBold)),
                                )
                            },
                            label = if (state.projectsLiteMode) "切换经典模式" else "切换精简模式",
                            theme = theme,
                            onClick = {
                                plusMenuOpen = false
                                vm.toggleProjectsLiteMode()
                            },
                        )
                    }
                }
            },
        )
        // 搜索栏: 按项目名称实时过滤, 与顶部 tab 叠加(与顶栏留 12dp 间距)
        Box(Modifier.padding(top = MomoSpacing.md).padding(horizontal = MomoSpacing.lg)) {
            MomoInput(
                value = state.projectsSearchText,
                placeholder = if (state.projectsLiteMode) "搜索 Session…" else "搜索项目…",
                theme = theme,
                minHeight = 40.dp,
                onChange = { vm.setProjectsSearchText(it) },
            )
        }
        Spacer(Modifier.height(MomoSpacing.sm))
        if (state.projectsLiteMode) {
            // ===== 精简模式: 直接平铺所有 session, 活跃/非活跃用 tab 切换(参考经典模式 tabbar) =====
            Column(Modifier.weight(1f)) {
                val q = state.projectsSearchText.trim()
                val filtered = state.liteSessions.filter {
                    q.isEmpty() || it.session.name.contains(q, true) || it.projectName.contains(q, true)
                }
                // 活跃 = agent_status running/waiting 或最近 windowDays 天有活动。
                val activeList = filtered.filter { it.session.isActiveLite(activeWindowDays) }
                val inactiveList = filtered.filterNot { it.session.isActiveLite(activeWindowDays) }
                val showTabBar = filtered.isNotEmpty()
                if (showTabBar) {
                    LiteTabBar(
                        activeCount = activeList.size,
                        inactiveCount = inactiveList.size,
                        selected = state.liteSelectedTab,
                        onSelect = { vm.setLiteTab(it) },
                        theme = theme,
                    )
                }
                Box(Modifier.weight(1f)) {
                    PullToRefreshBox(
                        isRefreshing = state.projectsRefreshing,
                        onRefresh = {
                            vm.refreshProjects()
                            vm.loadLiteSessions()
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when {
                            state.liteSessionsLoading && state.liteSessions.isEmpty() -> ListSkeleton(rows = 8, theme = theme)
                            state.liteSessions.isEmpty() -> Box(
                                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                            ) {
                                Text("暂无会话", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
                            }
                            else -> {
                                val list = when (state.liteSelectedTab) {
                                    LiteListTab.All -> filtered
                                    LiteListTab.Running -> activeList
                                    LiteListTab.Done -> inactiveList
                                }
                                if (list.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            when (state.liteSelectedTab) {
                                                LiteListTab.All -> "暂无会话"
                                                LiteListTab.Running -> "暂无 RUNNING 会话"
                                                LiteListTab.Done -> "暂无 DONE 会话"
                                            },
                                            color = theme.textMuted,
                                            style = momoTextStyle(MomoTypography.body),
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        state = listState,
                                    ) {
                                        items(
                                            list,
                                            key = { it.session.sessionId },
                                        ) { entry ->
                                            LiteSessionRow(
                                                entry,
                                                active = entry.session.isActiveLite(activeWindowDays),
                                                theme = theme,
                                            ) { vm.openSession(entry.session) }
                                        }
                                        item { Spacer(Modifier.height(MomoSpacing.xxxl)) }
                                    }
                                }
                            }
                        }
                    }
                    VerticalScrollbar(listState)
                }
            }
        } else {
        ProjectsTabBar(selected = selectedTab, onSelect = { vm.setProjectsTab(it) },
            activeWindowDays = activeWindowDays,
            onActiveWindowChange = { vm.setProjectsActiveWindow(it) },
            theme = theme)
        Box(Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = state.projectsRefreshing,
                onRefresh = { vm.refreshProjects() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.projectsLoading && state.projects.isEmpty() -> ListSkeleton(rows = 8, theme = theme)
                    visible.isEmpty() -> Box(
                        Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (state.projectsSearchText.isNotBlank()) "未找到匹配\"${state.projectsSearchText}\"的项目"
                            else selectedTab.emptyHint,
                            color = theme.textMuted,
                            style = momoTextStyle(MomoTypography.body),
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                    ) {
                        itemsIndexed(visible, key = { _, p -> p.id }) { pIdx, project ->
                            // 项目行卡片: 入场交错动画。
                            HypergridReveal(index = pIdx) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.xs)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(theme.bgSecondary)
                                    .border(1.dp, theme.borderDefault, RoundedCornerShape(16.dp)),
                            ) {
                                ProjectRow(project, theme) { if (project.kind == "extension") vm.openExtension(project) else vm.openProject(project) }
                            }
                            }
                        }
                        item { Spacer(Modifier.height(MomoSpacing.xxxl)) }
                    }
                }
            }
            VerticalScrollbar(listState)
        }
        }
    }
}

// 精简模式活跃判定: agent 正在跑(running/waiting) 或最近 windowDays 天有活动。
private fun Session.isActiveLite(windowDays: Long): Boolean {
    val running = agentStatus.contains("run", true) || agentStatus.contains("wait", true) || agentStatus.contains("work", true)
    if (running) return true
    val threshold = nowEpochMillis() - windowDays * 24 * 60 * 60 * 1000L
    return (parseBackendTimeMillis(lastActive) ?: Long.MIN_VALUE) >= threshold
}

// 精简模式列表分组 tab: 全部 / RUNNING(活跃) / DONE(非活跃) — 选中态提升到 UiState, 跨导航持久。
// 与「我的」页统计三格(SESSIONS/RUNNING/DONE)一一对应可跳转。
enum class LiteListTab(val label: String) {
    All("全部"),
    Running("RUNNING"),
    Done("DONE"),
}

// 精简模式分组 tabbar: 分段药丸样式与经典模式 ProjectsTabBar 同款, 附各组数量。
@Composable
private fun LiteTabBar(
    activeCount: Int,
    inactiveCount: Int,
    selected: LiteListTab,
    onSelect: (LiteListTab) -> Unit,
    theme: MomoTheme,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
    ) {
        listOf(
            LiteListTab.All to (activeCount + inactiveCount),
            LiteListTab.Running to activeCount,
            LiteListTab.Done to inactiveCount,
        ).forEach { (tab, count) ->
            val isOn = tab == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MomoCorners.pill))
                    .background(if (isOn) theme.accentPrimary else theme.bgSecondary)
                    .clickable { onSelect(tab) }
                    .padding(vertical = MomoSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${tab.label} · $count",
                    color = if (isOn) Color.White else theme.textMuted,
                    style = momoTextStyle(MomoTypography.subheadline.copy(fontWeight = FontWeight.W600)),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LiteSessionRow(entry: LiteSessionEntry, active: Boolean, theme: MomoTheme, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.xs)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.bgSecondary)
            .clickable { onClick() }
            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 状态点: 活跃绿点呼吸 / 非活跃灰点
            val dotColor = if (active) theme.success else theme.textMuted.copy(alpha = 0.5f)
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(MomoSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.session.name.ifBlank { "未命名会话" },
                    color = theme.textPrimary,
                    style = momoTextStyle(MomoTypography.headline),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(entry.projectName, entry.issueTitle).filter { it.isNotBlank() }.joinToString(" · "),
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.caption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active) {
                Text(
                    "进行中",
                    color = theme.success,
                    style = momoTextStyle(MomoTypography.caption.copy(fontWeight = FontWeight.Medium)),
                )
            }
        }
    }
}

// 项目列表顶部筛选 tab: 全部 / 活跃(最近活动) / 收藏(星标) / 扩展(kind=extension).
// 作用在"我的项目"(createdBy == 当前用户)之上, 不改变页面语义。
// "活跃"窗口: 最近 N 天内有 session 活动算活跃。可按需调整。
private const val PROJECT_ACTIVE_WINDOW_DAYS = 7L
private const val PROJECT_ACTIVE_WINDOW_MILLIS = PROJECT_ACTIVE_WINDOW_DAYS * 24 * 60 * 60 * 1000L

enum class ProjectListTab(val label: String, val emptyHint: String) {
    All("全部", "暂无项目"),
    Active("活跃", "暂无活跃项目"),
    Starred("收藏", "暂无收藏项目"),
    Extension("扩展", "暂无扩展项目"),
    Mine("我的", "暂无自己的项目");

    fun filter(projects: List<Project>, activeWindowDays: Long = PROJECT_ACTIVE_WINDOW_DAYS, userId: String = ""): List<Project> = when (this) {
        All -> projects
        // 活跃 = 最近 activeWindowDays 天内有活动(last_session_activity_at 优先, 回退 last_active)。
        Active -> {
            val threshold = nowEpochMillis() - activeWindowDays * 24 * 60 * 60 * 1000L
            projects.filter { (parseBackendTimeMillis(it.lastSessionActivityAt ?: it.lastActive) ?: Long.MIN_VALUE) >= threshold }
        }
        Starred -> projects.filter { it.starred == true }
        Extension -> projects.filter { it.kind == "extension" }
        Mine -> projects.filter { userId.isNotBlank() && it.createdBy == userId }
    }
}

// 项目列表顶部 tabbar: 分段药丸样式, 选中=accentPrimary 实底+白字, 未选=bgSecondary+textMuted。
// 活跃 tab 选中时再点击弹出时间窗口下拉菜单。
@Composable
private fun ProjectsTabBar(
    selected: ProjectListTab,
    onSelect: (ProjectListTab) -> Unit,
    activeWindowDays: Long = 7L,
    onActiveWindowChange: (Long) -> Unit = {},
    theme: MomoTheme,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
    ) {
        ProjectListTab.values().forEach { tab ->
            val isOn = tab == selected
            val isActive = tab == ProjectListTab.Active
            var showDropdown by remember { mutableStateOf(false) }

            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MomoCorners.pill))
                    .background(if (isOn) theme.accentPrimary else theme.bgSecondary)
                    .clickable {
                        if (isOn && isActive) { showDropdown = true }
                        else onSelect(tab)
                    }
                    .padding(vertical = MomoSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tab.label,
                        color = if (isOn) Color.White else theme.textMuted,
                        style = momoTextStyle(MomoTypography.subheadline.copy(fontWeight = FontWeight.W600)),
                        maxLines = 1,
                    )
                    // 活跃 tab 选中时: 大号下拉箭头 + 当前天数角标
                    if (isOn && isActive) {
                        Text(
                            "  ▼",
                            color = Color.White,
                            style = momoTextStyle(MomoTypography.body),
                        )
                    }
                }

                // 活跃时间下拉 (当前选项前有圆点标记)
                DropdownMenu(
                    expanded = showDropdown,
                    onDismissRequest = { showDropdown = false },
                ) {
                    val options = listOf(1L to "最近 1 天", 3L to "最近 3 天", 7L to "最近 7 天", 14L to "最近 14 天", 30L to "最近 30 天")
                    options.forEach { (days, label) ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (activeWindowDays == days) "● " else "○ ",
                                        color = if (activeWindowDays == days) theme.accentPrimary else theme.textMuted,
                                        style = momoTextStyle(MomoTypography.caption),
                                    )
                                    Text(label, color = theme.textPrimary, style = momoTextStyle(MomoTypography.body))
                                }
                            },
                            onClick = {
                                onActiveWindowChange(days)
                                showDropdown = false
                            },
                        )
                    }
                }
            }
        }
    }
}

// 扩展应用(App 内 WebView): 加载 /extension/<name>/, 顶栏标题=扩展名 + 返回项目列表。
@Composable
private fun ExtensionWebScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = state.activeExtensionTitle.ifBlank { "扩展应用" },
            theme = theme,
            left = "‹",
            onLeft = { vm.navigate(AppScreen.Projects) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PlatformWebView(
                url = state.activeExtensionUrl,
                token = state.activeExtensionToken,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CreateProjectScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = "新建项目",
            theme = theme,
            left = "‹",
            onLeft = { vm.navigate(AppScreen.Projects) },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(MomoSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MomoSpacing.lg),
        ) {
            Text("新建项目", color = theme.textPrimary, style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)))
            Text("创建一个属于你的 Mobius 项目。", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
            MomoInput(state.createProjectName, "项目名称", theme, imeAction = ImeAction.Next, onChange = vm::setCreateProjectName)
            MomoInput(state.createProjectDescription, "项目描述（可选）", theme, minHeight = 92.dp, onChange = vm::setCreateProjectDescription)
            PrimaryButton(label = "创建项目", loading = state.creatingProject, theme = theme) { vm.createProject() }
        }
    }
}

@Composable
private fun CreateIssueScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = "新建 Issue",
            theme = theme,
            left = "‹",
            onLeft = { vm.navigate(AppScreen.ProjectIssues) },
        )
        Column(
            modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(MomoSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MomoSpacing.lg),
        ) {
            Text("新建 Issue", color = theme.textPrimary, style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)))
            Text("在当前项目下创建一个 Issue（任务单）。", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
            MomoInput(state.createIssueTitle, "Issue 标题", theme, imeAction = ImeAction.Next, onChange = vm::setCreateIssueTitle)
            MomoInput(state.createIssueDescription, "Issue 描述", theme, minHeight = 92.dp, onChange = vm::setCreateIssueDescription)
            Text("选择模型", color = theme.textMuted, style = momoTextStyle(MomoTypography.sectionHeader))
            state.cloneModelOptions.forEach { option ->
                val selected = state.createIssueModel == option.key
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MomoCorners.card))
                        .background(if (selected) theme.accentPrimary.copy(alpha = 0.12f) else theme.inputBg)
                        .border(1.dp, if (selected) theme.accentPrimary else theme.borderDefault, RoundedCornerShape(MomoCorners.card))
                        .clickable { vm.setCreateIssueModel(option.key) }
                        .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.title.ifBlank { option.label }.ifBlank { option.key }, color = theme.textPrimary, style = momoTextStyle(MomoTypography.body))
                        if (option.sub.isNotBlank()) {
                            Text(option.sub, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
                        }
                    }
                }
            }
            PrimaryButton(label = "创建 Issue", loading = state.creatingIssue, theme = theme) { vm.createIssue() }
        }
    }
}

@Composable
private fun CreateSessionScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = "新建会话",
            theme = theme,
            left = "‹",
            onLeft = { vm.navigate(AppScreen.IssueSessions) },
        )
        Column(
            modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(MomoSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MomoSpacing.lg),
        ) {
            Text("新建会话", color = theme.textPrimary, style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)))
            Text("在当前 Issue 下创建会话，可选择模型与启用的 skill / memory。", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
            MomoInput(state.createSessionTitle, "会话名称", theme, imeAction = ImeAction.Next, onChange = vm::setCreateSessionTitle)
            MomoInput(state.createSessionDescription, "会话描述（可选）", theme, minHeight = 92.dp, onChange = vm::setCreateSessionDescription)

            // ===== 模型选择 =====
            ContextPickSection(title = "模型", theme = theme) {
                state.cloneModelOptions.forEach { option ->
                    val selected = option.key == state.createSessionModel
                    SelectChip(
                        label = option.label.ifBlank { option.key },
                        sub = option.sub,
                        selected = selected,
                        theme = theme,
                        onClick = { vm.setCreateSessionModel(option.key) },
                    )
                }
            }

            // ===== Skill 选择(默认全启用, 点按=取消启用) =====
            ContextPickSection(
                title = "Skill",
                count = state.sessionSkills.size - state.sessionExcludedSkills.size,
                total = state.sessionSkills.size,
                loading = state.contextLoading,
                theme = theme,
            ) {
                if (state.sessionSkills.isEmpty() && !state.contextLoading) {
                    Text("暂无可选 skill", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
                }
                state.sessionSkills.forEach { item ->
                    val enabled = item.id !in state.sessionExcludedSkills
                    SelectChip(
                        label = item.name,
                        sub = item.description.take(40),
                        selected = enabled,
                        theme = theme,
                        onClick = { vm.toggleSessionSkill(item.id) },
                    )
                }
            }

            // ===== Memory 选择 =====
            ContextPickSection(
                title = "Memory",
                count = state.sessionMemories.size - state.sessionExcludedMemories.size,
                total = state.sessionMemories.size,
                loading = state.contextLoading,
                theme = theme,
            ) {
                if (state.sessionMemories.isEmpty() && !state.contextLoading) {
                    Text("暂无可选 memory", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
                }
                state.sessionMemories.forEach { item ->
                    val enabled = item.id !in state.sessionExcludedMemories
                    SelectChip(
                        label = item.name,
                        sub = item.description.take(40),
                        selected = enabled,
                        theme = theme,
                        onClick = { vm.toggleSessionMemory(item.id) },
                    )
                }
            }

            PrimaryButton(label = "创建会话", loading = state.creatingSession, theme = theme) { vm.createSession() }
        }
    }
}

// 新建会话页的分组容器: 标题 + (启用数/总数) + 内容流式排列。
@Composable
private fun ContextPickSection(
    title: String,
    theme: MomoTheme,
    count: Int? = null,
    total: Int = 0,
    loading: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MomoSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (count != null) "$title · $count/$total" else title,
                color = theme.textMuted.copy(alpha = 0.75f),
                style = momoMonoStyle(size = 9, color = theme.textMuted.copy(alpha = 0.75f), weight = FontWeight.Bold),
            )
            Spacer(Modifier.width(MomoSpacing.sm))
            if (loading) {
                CircularProgressIndicator(color = theme.accentPrimary, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
            } else if (count != null) {
                Text("$count / $total", color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
            }
        }
        Column {
            content()
        }
    }
}

// 可选胶囊: 选中 = accent 实底白字; 未选中 = 浅底灰字(表示"已取消启用")。
@Composable
private fun SelectChip(label: String, sub: String?, selected: Boolean, theme: MomoTheme, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(end = MomoSpacing.sm, bottom = MomoSpacing.sm)
            .clip(RoundedCornerShape(MomoCorners.pill))
            .background(if (selected) theme.accentPrimary.copy(alpha = 0.14f) else theme.bgSecondary)
            .border(
                1.dp,
                if (selected) theme.accentPrimary.copy(alpha = 0.55f) else theme.borderDefault,
                RoundedCornerShape(MomoCorners.pill),
            )
            .clickable { onClick() }
            .padding(horizontal = MomoSpacing.md, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (selected) theme.accentPrimary else theme.textPrimary,
            style = momoTextStyle(MomoTypography.subheadline.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!sub.isNullOrBlank()) {
            Text(
                sub,
                color = if (selected) theme.accentPrimary.copy(alpha = 0.75f) else theme.textMuted,
                style = momoTextStyle(MomoTypography.caption.copy(fontSize = 10.sp)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 项目排序: 星标优先 -> 最近 session 活动(last_session_activity_at)倒序 -> last_active 倒序 -> 名称.
// 与网页端 sortProjectsForDisplay 一致, 保证两端顺序相同。
private fun Iterable<Project>.sortedWithProjects(): List<Project> =
    toList().sortedWith(
        compareByDescending<Project> { it.starred == true }
            .thenByDescending { parseBackendTimeMillis(it.lastSessionActivityAt) ?: Long.MIN_VALUE }
            .thenByDescending { parseBackendTimeMillis(it.lastActive) ?: Long.MIN_VALUE }
            .thenBy { it.name },
    )

@Composable
private fun ProjectRow(project: Project, theme: MomoTheme, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.bgPrimary)
            .clickable { onClick() }
            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm)) {
                if (project.starred == true) {
                    Text("★", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.caption.copy(fontSize = 13.sp)))
                }
                Text(
                    project.name.ifBlank { "未命名项目" },
                    color = if (project.disabled == true) theme.textMuted else theme.textPrimary,
                    style = momoTextStyle(MomoTypography.headline),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (project.description.isNotBlank()) {
                Text(
                    project.description,
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.caption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                buildProjectMeta(project),
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption.copy(fontSize = 11.sp)),
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text("›", color = theme.textMuted, style = momoTextStyle(MomoTypography.title))
    }
}

private fun buildProjectMeta(project: Project): String {
    val parts = mutableListOf<String>()
    if (project.disabled == true) parts.add("已停用")
    project.issueCount?.let { parts.add("$it 个 Issue") }
    formatBackendTime(project.lastSessionActivityAt ?: project.lastActive).takeIf { it.isNotBlank() }?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

@Composable
private fun ProjectIssuesScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = state.activeProject?.name ?: "项目",
            theme = theme,
            left = "‹",
            onLeft = { vm.navigate(AppScreen.Projects) },
            rightContent = {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(MomoCorners.pill))
                        .clickable { vm.openIssueEditor() }
                        .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.xs),
                ) { Text("+", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.title)) }
            },
        )
        Box(Modifier.weight(1f)) {
            when {
                state.projectIssuesLoading && state.projectIssues.isEmpty() -> ListSkeleton(rows = 8, theme = theme)
                state.projectIssues.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) { Text("暂无 Issue", color = theme.textMuted, style = momoTextStyle(MomoTypography.body)) }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                ) {
                    items(state.projectIssues, key = { it.id }) { issue ->
                        IssueRow(issue, theme) { vm.openIssue(issue) }
                        HorizontalDivider(color = theme.borderDefault, thickness = 0.6.dp)
                    }
                    item { Spacer(Modifier.height(MomoSpacing.xxxl)) }
                }
            }
            VerticalScrollbar(listState)
        }
    }
}

@Composable
private fun IssueRow(issue: Issue, theme: MomoTheme, onClick: () -> Unit) {
    val completed = issue.status == "completed"
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.bgPrimary)
            .clickable { onClick() }
            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(MomoCorners.pill))
                        .background(if (completed) theme.bgSecondary else theme.accentPrimary.copy(alpha = 0.12f))
                        .padding(horizontal = MomoSpacing.sm, vertical = 2.dp),
                ) {
                    Text(
                        if (completed) "已完成" else "进行中",
                        color = if (completed) theme.textMuted else theme.accentPrimary,
                        style = momoTextStyle(MomoTypography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)),
                    )
                }
                Text(
                    issue.title.ifBlank { "未命名 Issue" },
                    color = theme.textPrimary,
                    style = momoTextStyle(MomoTypography.headline),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (issue.description.isNotBlank()) {
                Text(
                    issue.description,
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.caption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                buildIssueMeta(issue),
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption.copy(fontSize = 11.sp)),
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text("›", color = theme.textMuted, style = momoTextStyle(MomoTypography.title))
    }
}

private fun buildIssueMeta(issue: Issue): String {
    val parts = mutableListOf<String>()
    issue.sessionCount?.let { parts.add("$it 个会话") }
    issue.messageCount?.let { parts.add("$it 条消息") }
    formatBackendTime(issue.lastActive).takeIf { it.isNotBlank() }?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

@Composable
private fun IssueSessionsScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = state.activeIssue?.title ?: "会话",
            theme = theme,
            left = "‹",
            onLeft = { vm.navigate(AppScreen.ProjectIssues) },
            rightContent = {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(MomoCorners.pill))
                        .clickable { vm.openSessionEditor() }
                        .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.xs),
                ) { Text("+", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.title)) }
            },
        )
        Box(Modifier.weight(1f)) {
            when {
                state.issueSessionsLoading && state.issueSessionsList.isEmpty() -> ListSkeleton(rows = 6, theme = theme)
                state.issueSessionsList.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) { Text("暂无会话", color = theme.textMuted, style = momoTextStyle(MomoTypography.body)) }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                ) {
                    items(state.issueSessionsList, key = { it.sessionId }) { session ->
                        SessionListRow(session, theme) { vm.openSession(session) }
                        HorizontalDivider(color = theme.borderDefault, thickness = 0.6.dp)
                    }
                    item { Spacer(Modifier.height(MomoSpacing.xxxl)) }
                }
            }
            VerticalScrollbar(listState)
        }
    }
}

@Composable
private fun SessionListRow(session: Session, theme: MomoTheme, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.bgPrimary)
            .clickable { onClick() }
            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧 agent 状态色点(running=绿, failed=红, 否则灰)。
        val dotColor = when (session.agentStatus) {
            "running", "waiting" -> Color(0xFF22C55E)
            "failed" -> theme.danger
            "completed" -> theme.accentPrimary
            else -> theme.textMuted
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(MomoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                session.name.ifBlank { "未命名会话" },
                color = theme.textPrimary,
                style = momoTextStyle(MomoTypography.headline),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.description.isNotBlank()) {
                Text(
                    session.description,
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.caption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                buildSessionMeta(session),
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption.copy(fontSize = 11.sp)),
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text("›", color = theme.textMuted, style = momoTextStyle(MomoTypography.title))
    }
}

private fun buildSessionMeta(session: Session): String {
    val parts = mutableListOf<String>()
    session.modelLabel?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    session.agentStatus.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    session.messageCount?.let { parts.add("$it 条消息") }
    formatBackendTime(session.lastActive).takeIf { it.isNotBlank() }?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

// ===== 群聊 UI =====

@Composable
private fun ContactsScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        // 固定高度标题栏(不传 largeTitle/scrollState)——避免滑动时 TopBar 96dp↔56dp 跳变导致下方闪屏.
        TopBar(
            title = "通讯录",
            theme = theme,
            // 参考图: 大标题 + 成员数副标题(滚动时收起为小标题)。
            largeTitle = "通讯录",
            subtitle = if (state.contacts.isNotEmpty()) "${state.contacts.size} 位成员" else null,
            scrollState = listState,
        )
        // 通讯录搜索框（本地 state 持有，按键同步给 VM 做防抖拉取）
        val externalSearch = state.contactsSearch
        var searchField by remember { mutableStateOf(externalSearch) }
        LaunchedEffect(externalSearch) {
            if (externalSearch != searchField) searchField = externalSearch
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.xs),
        ) {
            BasicTextField(
                value = searchField,
                onValueChange = { newValue ->
                    searchField = newValue
                    if (newValue != externalSearch) vm.onContactsSearchChange(newValue)
                },
                singleLine = true,
                textStyle = momoTextStyle(MomoTypography.body).copy(color = theme.textPrimary),
                cursorBrush = SolidColor(theme.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(MomoCorners.pill))
                    .background(theme.bgSecondary)
                    .padding(horizontal = MomoSpacing.md),
                decorationBox = { inner ->
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        SearchIcon(theme.textMuted)
                        Box(
                            Modifier.weight(1f).padding(start = MomoSpacing.sm).fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (searchField.isBlank()) {
                                Text("搜索通讯录成员…", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
                            }
                            inner()
                        }
                        if (searchField.isNotBlank()) {
                            Box(
                                Modifier.padding(start = MomoSpacing.xs).size(24.dp).clip(CircleShape).clickable {
                                    searchField = ""
                                    vm.onContactsSearchChange("")
                                },
                                contentAlignment = Alignment.Center,
                            ) { ClearIcon(theme.textMuted) }
                        }
                    }
                },
            )
        }
        Box(Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = state.contactsRefreshing,
                onRefresh = { vm.refreshContacts() },
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
            ) {
                item {
                    Text(
                        "通讯录",
                        color = theme.textMuted,
                        style = momoTextStyle(MomoTypography.sectionHeader),
                        modifier = Modifier.padding(start = MomoSpacing.lg, top = MomoSpacing.sm, bottom = MomoSpacing.sm),
                    )
                }
                when {
                    // 首次加载(无数据): 骨架屏。
                    state.contacts.isEmpty() && !state.contactsLoaded -> item { ContactsSkeleton(theme = theme) }
                    state.contacts.isEmpty() -> item {
                        Box(
                            Modifier.fillMaxWidth().height(80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("暂无成员", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
                        }
                    }
                    else -> {
                        // 设计稿: 按展示名首字母分组, 组头 mono 字母 + 描边卡片行。
                        // 注意: toSortedMap 在 kotlin-native(iOS)不可用 — 手工排序 key。
                        val grouped = state.contacts
                            .groupBy { c -> initialOf(c.displayName.ifBlank { c.id }) }
                            .entries
                            .sortedBy { (k, _) -> if (k == "#") "~~" else k }
                        grouped.forEach { (letter, members) ->
                            item(key = "letter-$letter") {
                                Text(
                                    letter,
                                    color = theme.accentPrimary,
                                    style = momoMonoStyle(size = 10, color = theme.accentPrimary, weight = FontWeight.Bold),
                                    modifier = Modifier.padding(start = MomoSpacing.lg, top = MomoSpacing.sm, bottom = MomoSpacing.xs),
                                )
                            }
                            itemsIndexed(members, key = { _, u -> "${u.id}-${u.isSelf}" }) { cIdx, user ->
                                HypergridReveal(index = cIdx) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.xs)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(theme.bgSecondary)
                                        .border(1.dp, theme.borderDefault, RoundedCornerShape(16.dp)),
                                ) {
                                    ContactRow(user, theme) { vm.openDirectChat(user.id) }
                                }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(MomoSpacing.xxxl)) }
            }
            }
            VerticalScrollbar(listState)
            MenuPanel(state.menuOpen, theme, vm)
        }
    }
}

@Composable
private fun ChatListScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val listState = rememberLazyListState()
    var menuExpanded by remember { mutableStateOf(false) }
    val badges = remember(state.clones) { cloneBadges(state.clones) }
    var pendingDelete by remember { mutableStateOf<PendingChatDeletion?>(null) }
    Box(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            val chatSubtitle = buildString {
                val total = state.conversations.size + state.clones.size
                if (total > 0) {
                    append("$total 个会话")
                    val unread = state.conversations.sumOf { it.unread }
                    if (unread > 0) append(" · $unread 条未读")
                }
            }
            TopBar(
                title = "聊天",
                theme = theme,
                largeTitle = "聊天",
                subtitle = chatSubtitle.takeIf { it.isNotBlank() },
                scrollState = listState,
                rightContent = {
                    Box {
                        IconAdd(theme.textPrimary) { menuExpanded = true }
                        // 自绘菜单弹窗: 白色 18dp 圆角卡片 + 阴影 + 图标行, 替代默认 DropdownMenu 样式。
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.background(Color.Transparent),
                            shape = RoundedCornerShape(18.dp),
                            containerColor = theme.bgSecondary,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                        ) {
                            MenuActionRow(
                                icon = { PlusIcon(theme.accentPrimary) },
                                label = "创建分身",
                                theme = theme,
                                onClick = {
                                    menuExpanded = false
                                    vm.openCloneEditor()
                                },
                            )
                            MenuActionRow(
                                icon = { ContactsTabIcon(theme.accentPrimary) },
                                label = "发起群聊",
                                theme = theme,
                                onClick = {
                                    menuExpanded = false
                                    vm.openGroupEditor()
                                },
                            )
                        }
                    }
                },
            )
            PullToRefreshBox(
                isRefreshing = state.conversationsRefreshing,
                onRefresh = { vm.refreshChatList() },
                modifier = Modifier.weight(1f),
            ) {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                if (!state.chatListHintDismissed &&
                    (state.conversations.isNotEmpty() || state.clones.isNotEmpty())
                ) {
                    item("chat-list-swipe-hint") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(MomoCorners.card))
                                    .background(theme.accentPrimary.copy(alpha = 0.10f))
                                    .border(0.5.dp, theme.accentPrimary.copy(alpha = 0.28f), RoundedCornerShape(MomoCorners.card))
                                    .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
                            ) {
                                Text(
                                    "💡 左滑或长按聊天可删除",
                                    color = theme.textPrimary,
                                    style = momoTextStyle(MomoTypography.subheadline),
                                )
                            }
                            Text(
                                "×",
                                color = theme.textMuted,
                                style = momoTextStyle(MomoTypography.title),
                                modifier = Modifier.clickable { vm.dismissChatListHint() }
                                    .padding(horizontal = MomoSpacing.sm, vertical = MomoSpacing.xs),
                            )
                        }
                    }
                }
                if (state.conversations.isEmpty() && state.clones.isEmpty()) {
                    item {
                        if (state.conversationsLoaded) {
                            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "还没有会话，点右上角 + 创建",
                                    color = theme.textMuted,
                                    style = momoTextStyle(MomoTypography.subheadline),
                                )
                            }
                        } else {
                            // 首次加载(无缓存数据): 骨架屏替代"加载中…"文案。
                            ChatListSkeleton(theme = theme)
                        }
                    }
                }
                if (state.conversations.isNotEmpty()) {
                    itemsIndexed(state.conversations, key = { _, conv -> conv.id }) { convIdx, conv ->
                        val cachedNames = state.conversationMemberNames[conv.id].orEmpty()
                        HypergridReveal(index = convIdx) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.xs)
                                .clip(RoundedCornerShape(18.dp))
                                .background(theme.bgSecondary)
                                .border(1.dp, theme.borderDefault, RoundedCornerShape(18.dp)),
                        ) {
                        DeletableChatRow(
                            theme = theme,
                            deleteEnabled = true,
                            onClick = { vm.openConversation(conv.id) },
                            onDelete = {
                                pendingDelete = PendingChatDeletion(
                                    kind = "conversation",
                                    id = conv.id,
                                    name = conv.name.ifBlank { "群聊" },
                                    isOwner = conv.ownerId == state.user?.id,
                                    isDirect = conv.type == "direct",
                                )
                            },
                        ) {
                            ConversationRow(
                                conv = conv,
                                memberNames = conv.memberNames.ifEmpty { cachedNames },
                                currentUserName = state.user?.displayName ?: state.user?.id.orEmpty(),
                                theme = theme,
                            )
                        }
                        }
                        }
                    }
                }
                if (state.clones.isNotEmpty()) {
                    item {
                        Text(
                            "AGENTS",
                            color = theme.textMuted,
                            style = momoMonoStyle(size = 9, color = theme.textMuted.copy(alpha = 0.7f), weight = FontWeight.Bold),
                            modifier = Modifier.padding(start = MomoSpacing.lg, top = MomoSpacing.md, bottom = MomoSpacing.xs),
                        )
                    }
                }
                // ===== Hypergrid Bento: 主 Mobius/分身大卡(左侧 accent 竖条 + 实时状态行) =====
                itemsIndexed(state.clones, key = { _, s -> s.sessionId }) { cloneIdx, session ->
                    // 小莫/分身: 「删除」= 清空消息记录, 保留分身本身(含主小莫, 仅清消息不删主会话).
                    HypergridReveal(index = cloneIdx) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.xs)
                            .clip(RoundedCornerShape(18.dp))
                            .background(theme.bgSecondary)
                            .border(1.dp, theme.borderDefault, RoundedCornerShape(18.dp)),
                    ) {
                        // HUD 科技感: 四角 L 形角标 + 缓慢扫描线(仅深色, 浅色闪烁过强)。
                        if (theme.dark) {
                            HudCorners(Modifier.matchParentSize(), theme.accentPrimary)
                            ScanLine(Modifier.matchParentSize().padding(horizontal = 10.dp), theme.accentPrimary)
                        }
                        // 设计稿: 左侧 3dp accent 竖条(context 语义)
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(theme.accentPrimary),
                        )
                        Box(Modifier.padding(start = 8.dp)) {
                            DeletableChatRow(
                                theme = theme,
                                deleteEnabled = true,
                                actionLabel = "清空",
                                onClick = { vm.openSession(session) },
                                onDelete = {
                                    pendingDelete = PendingChatDeletion(
                                        kind = "session",
                                        id = session.sessionId,
                                        name = session.name.ifBlank { "小莫" },
                                    )
                                },
                            ) {
                                CloneChatRow(session, badges[session.sessionId], theme)
                            }
                        }
                    }
                    }
                }
                item { Spacer(Modifier.height(MomoSpacing.xxxl)) }
            }
            }
        }
        VerticalScrollbar(listState)
        pendingDelete?.let { target ->
            ConfirmDialog(
                title = when {
                    target.kind == "session" -> "清空聊天记录"
                    target.kind == "conversation" && target.isOwner && !target.isDirect -> "解散群聊"
                    else -> "删除聊天"
                },
                message = when {
                    target.kind == "session" ->
                        "确定清空「${target.name}」的聊天记录？将清除该会话的消息，小莫/分身本身不会被删除。"
                    target.isOwner && target.isDirect ->
                        "确定删除「${target.name}」？你是发起者，删除后双方列表都将移除该私聊。"
                    target.isOwner ->
                        "确定解散「${target.name}」？你是群主，解散后所有成员都会失去此群聊且无法恢复。"
                    else ->
                        "确定删除「${target.name}」？退出后将从你的列表移除。"
                },
                theme = theme,
                confirmLabel = when {
                    target.kind == "session" -> "清空"
                    target.kind == "conversation" && target.isOwner && !target.isDirect -> "解散"
                    else -> "删除"
                },
                onConfirm = {
                    val t = target
                    pendingDelete = null
                    when (t.kind) {
                        "session" -> vm.clearSessionMessages(t.id)
                        else -> vm.deleteConversation(t.id)
                    }
                },
                onDismiss = { pendingDelete = null },
            )
        }
    }
}

@Composable
private fun CloneChatRow(session: Session, cloneNumber: String?, theme: MomoTheme) {
    val isMain = session.isMainSession()
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(theme.bgSecondary).padding(horizontal = MomoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MomoCloneAvatar(44.dp, isMain = isMain, cloneNumber = cloneNumber)
        Spacer(Modifier.width(MomoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                session.name.ifBlank { if (isMain) "主 Mobius" else "分身 Mobius" },
                color = theme.textPrimary,
                style = momoTextStyle(MomoTypography.headline),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.description.isNotBlank()) {
                Spacer(Modifier.height(MomoSpacing.xs))
                Text(
                    session.description.take(40),
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.caption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conv: ConversationSummary,
    memberNames: List<String>,
    currentUserName: String,
    theme: MomoTheme,
) {
    val isDirect = conv.type == "direct"
    // 1v1 显示"对方": 优先从成员名里取非自己的(比 conv.name 可靠)。
    // direct 的 conv.name 是创建者视角的"对方", 对另一方会是自己名字 → 这种情况不显示自己, 留空走"私聊"兜底。
    val displayName = if (isDirect) {
        val other = memberNames.firstOrNull { it.isNotBlank() && it != currentUserName }
        when {
            !other.isNullOrBlank() -> other
            conv.name.isNotBlank() && conv.name != currentUserName -> conv.name
            else -> ""
        }
    } else {
        conv.name
    }
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(theme.bgSecondary).padding(horizontal = MomoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像：1v1=对方首字符(UserAvatar)；群聊=成员首字符网格(GroupAvatar)。未读徽标贴在头像右上角。
        // 群成员名优先用后端列表返回/打开时缓存的 memberNames；都没有时退化为群名首字符。
        Box {
            if (isDirect) {
                UserAvatar(44.dp, displayName)
            } else {
                GroupAvatar(memberNames.ifEmpty { listOf(conv.name) }, 44.dp)
            }
            if (conv.unread > 0) {
                val unreadText = if (conv.unread > 99) "99+" else conv.unread.toString()
                // 角标宽度随内容自适应(单字符为圆, 多字符/99+ 为胶囊)，数字居中且不被裁切。
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .height(18.dp)
                        .defaultMinSize(minWidth = 18.dp)
                        .border(2.dp, theme.bgSecondary, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        unreadText,
                        color = Color.White,
                        fontSize = 10.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.width(MomoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                displayName.ifBlank { if (isDirect) "私聊" else "未命名群聊" },
                color = theme.textPrimary,
                style = momoTextStyle(MomoTypography.headline),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(MomoSpacing.xs))
            Text(
                conv.lastMessage?.takeIf { it.isNotBlank() }
                    ?: if (isDirect) "" else "${conv.memberCount} 位成员",
                color = theme.textMuted,
                style = momoTextStyle(MomoTypography.caption),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(MomoSpacing.sm))
        val timeText = formatBackendTime(conv.lastMessageAt)
        if (timeText.isNotBlank()) {
            Text(timeText, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
        }
    }
}

@Composable
private fun ContactRow(user: UserDirectoryEntry, theme: MomoTheme, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(theme.bgSecondary).clickable { onClick() }.padding(horizontal = MomoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(36.dp, user.displayName.ifBlank { user.id })
        Spacer(Modifier.width(MomoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                user.displayName.ifBlank { user.id }.let { if (user.isSelf) "$it（我）" else it },
                color = theme.textPrimary,
                style = momoTextStyle(MomoTypography.body),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (user.role.isNotBlank()) {
                Text(user.role, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
            }
        }
    }
}

@Composable
private fun CreateCloneScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = "创建分身",
            theme = theme,
            left = "‹",
            onLeft = { vm.backToLastTab() },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(MomoSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MomoSpacing.lg),
        ) {
            Text("开一个分身 Mobius", color = theme.textPrimary, style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)))
            Text("分身会在当前 Mobius 任务单下创建独立 Session。", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
            MomoInput(state.cloneTitle, "分身名称", theme, imeAction = ImeAction.Next, onChange = vm::setCloneTitle)
            MomoInput(state.cloneDescription, "任务描述", theme, minHeight = 92.dp, onChange = vm::setCloneDescription)
            Text("选择模型", color = theme.textMuted, style = momoTextStyle(MomoTypography.sectionHeader))
            state.cloneModelOptions.forEach { option ->
                val selected = state.cloneModel == option.key
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MomoCorners.card))
                        .background(if (selected) theme.accentPrimary.copy(alpha = 0.12f) else theme.inputBg)
                        .border(1.dp, if (selected) theme.accentPrimary else theme.borderDefault, RoundedCornerShape(MomoCorners.card))
                        .clickable { vm.setCloneModel(option.key) }
                        .padding(horizontal = MomoSpacing.lg, vertical = MomoSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label.ifBlank { option.title.ifBlank { option.key } },
                            color = theme.textPrimary,
                            style = momoTextStyle(MomoTypography.subheadline.copy(fontWeight = FontWeight.SemiBold)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (option.sub.isNotBlank()) {
                            Spacer(Modifier.height(MomoSpacing.xs))
                            Text(option.sub, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (selected) Text("✓", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.headline.copy(fontWeight = FontWeight.Bold)))
                }
            }
            PrimaryButton("+ 创建并启动", state.loading, theme, vm::createClone)
            Spacer(Modifier.height(MomoSpacing.xxl))
        }
    }
}

@Composable
private fun CreateGroupScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    var groupName by remember { mutableStateOf(state.createGroupName) }
    LaunchedEffect(state.createGroupName) {
        if (state.createGroupName != groupName) groupName = state.createGroupName
    }
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = "发起群聊",
            theme = theme,
            left = "‹",
            onLeft = { vm.backToLastTab() },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(MomoSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MomoSpacing.lg),
        ) {
            Text("创建群聊", color = theme.textPrimary, style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)))
            BasicTextField(
                value = groupName,
                onValueChange = { newValue ->
                    groupName = newValue
                    if (newValue != state.createGroupName) vm.setCreateGroupName(newValue)
                },
                singleLine = true,
                textStyle = momoTextStyle(MomoTypography.headline.copy(color = theme.textPrimary)),
                cursorBrush = SolidColor(theme.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(MomoCorners.medium))
                    .background(theme.inputBg)
                    .border(1.dp, theme.borderDefault, RoundedCornerShape(MomoCorners.medium))
                    .padding(horizontal = MomoSpacing.lg),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (groupName.isBlank()) {
                            Text("群名称", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
                        }
                        inner()
                    }
                },
            )
            Text("选择成员（通讯录）", color = theme.textMuted, style = momoTextStyle(MomoTypography.sectionHeader))
            if (state.contacts.isEmpty()) {
                Text(
                    if (state.contactsLoading) "加载中…" else "暂无可选成员",
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.subheadline),
                )
            } else {
                state.contacts.forEach { user ->
                    val selected = user.id in state.createGroupSelectedUserIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MomoCorners.medium))
                            .background(if (selected) theme.accentPrimary.copy(alpha = 0.10f) else theme.inputBg)
                            .clickable { vm.toggleCreateGroupUser(user.id) }
                            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { vm.toggleCreateGroupUser(user.id) },
                            colors = CheckboxDefaults.colors(checkedColor = theme.accentPrimary),
                        )
                        Spacer(Modifier.width(MomoSpacing.sm))
                        Text(
                            user.displayName.ifBlank { user.id }.let { if (user.isSelf) "$it（我）" else it },
                            color = theme.textPrimary,
                            style = momoTextStyle(MomoTypography.body),
                        )
                    }
                }
            }
            Text("选择小莫/分身", color = theme.textMuted, style = momoTextStyle(MomoTypography.sectionHeader))
            if (state.clones.isEmpty()) {
                Text("暂无可选小莫", color = theme.textMuted, style = momoTextStyle(MomoTypography.subheadline))
            } else {
                state.clones.forEach { session ->
                    val selected = session.sessionId in state.createGroupSelectedAgentSessions
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MomoCorners.medium))
                            .background(if (selected) theme.accentPrimary.copy(alpha = 0.10f) else theme.inputBg)
                            .clickable { vm.toggleCreateGroupAgent(session.sessionId) }
                            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { vm.toggleCreateGroupAgent(session.sessionId) },
                            colors = CheckboxDefaults.colors(checkedColor = theme.accentPrimary),
                        )
                        Spacer(Modifier.width(MomoSpacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                session.name.ifBlank { "小莫会话" },
                                color = theme.textPrimary,
                                style = momoTextStyle(MomoTypography.body),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (session.description.isNotBlank()) {
                                Text(
                                    session.description,
                                    color = theme.textMuted,
                                    style = momoTextStyle(MomoTypography.caption),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            PrimaryButton("创建群聊", state.loading, theme, vm::createGroup)
            Spacer(Modifier.height(MomoSpacing.xxl))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val detail = state.activeConversation
    val composerState by vm.composerState.collectAsState()
    val conversation = detail?.conversation
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    LaunchedEffect(state.groupMessages.lastOrNull()?.id) {
        // 滚到最底部：列表有顶部 spacer + 可选 typing 行，size+2 超出最后一条，
        // scrollToItem 自动钳制到最后 item，保证最新消息贴底。
        if (state.groupMessages.isNotEmpty()) {
            listState.scrollToItem(state.groupMessages.size + 2)
        }
    }
    val currentUserId = state.user?.id.orEmpty()
    val hideKeyboard = rememberHideKeyboard()
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        val isDirectChat = conversation?.type == "direct"
        // 1v1 顶栏显示对方: direct 的 conversation.name 是创建者视角的"对方", 对另一方会是自己名字,
        // 取成员里非自己的 user 成员 display_name 更可靠; 拿不到再回退 name(若不是自己)/"私聊".
        val selfName = state.user?.displayName ?: state.user?.id.orEmpty()
        val chatTitle = if (isDirectChat) {
            detail?.members.orEmpty()
                .firstOrNull { it.memberType == "user" && it.memberId != currentUserId && it.displayName.isNotBlank() }
                ?.displayName
                ?: conversation?.name?.takeIf { it.isNotBlank() && it != selfName }
                ?: ""
        } else {
            conversation?.name ?: ""
        }
        TopBar(
            title = chatTitle.ifBlank { if (isDirectChat) "私聊" else "群聊" },
            theme = theme,
            left = "‹",
            onLeft = { vm.navigate(AppScreen.ChatList) },
            right = if (isDirectChat) null else "⋯",
            onRight = { vm.navigate(AppScreen.GroupInfo) },
        )
        Box(
            Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    // 点击空白区域: 收起键盘 + 停止正在播放的语音(对齐 1v1 聊天页行为)。
                    detectTapGestures(onTap = {
                        hideKeyboard()
                        if (state.ttsSpeakingMessageId != null) vm.stopSpeaking()
                    })
                },
        ) {
            if (state.loading && state.groupMessages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = theme.accentPrimary)
                }
            } else if (state.groupMessages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.groupConnected) "发送第一条消息开始对话吧" else "正在连接群聊…",
                        color = theme.textMuted,
                        style = momoTextStyle(MomoTypography.body),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = MomoSpacing.lg),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(MomoSpacing.md),
                ) {
                    item { Spacer(Modifier.height(MomoSpacing.xs)) }
                    items(state.groupMessages, key = { "g-${it.id}" }) { msg ->
                        GroupMessageRow(
                            message = msg,
                            theme = theme,
                            isCurrentUser = msg.senderId.isNotBlank() && msg.senderId == currentUserId && !msg.isAgent,
                            currentUserName = state.user?.displayName ?: state.user?.id.orEmpty(),
                            onPlay = { vm.speakMessageText(msg.content, "group-${msg.id}") },
                            speakingMessageId = state.ttsSpeakingMessageId,
                            onStopSpeaking = vm::stopSpeaking,
                            isFetching = state.ttsFetchingMessageId == "group-${msg.id}",
                            onDelete = { m -> vm.deleteGroupMessage(m.id) },
                        )
                    }
                    // @小莫/分身 等待回复时的"正在输入"指示(左侧光球 + 三点动画)。
                    val typing = conversation?.id?.let { state.groupTypingAgents[it] }.orEmpty()
                    if (typing.isNotEmpty()) {
                        item("group-typing") {
                            Column(verticalArrangement = Arrangement.spacedBy(MomoSpacing.sm)) {
                                typing.forEach { agent -> GroupTypingRow(agent.name, theme) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(MomoSpacing.md)) }
                }
            }
            VerticalScrollbar(listState)
        }
        GroupChatInputBar(state, composerState, theme, vm, detail?.members.orEmpty(), currentUserId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupInfoScreen(state: UiState, theme: MomoTheme, vm: MomoAppViewModel) {
    val detail = state.activeConversation
    val members = detail?.members.orEmpty()
    val conv = detail?.conversation
    val convId = conv?.id.orEmpty()
    val currentUserId = state.user?.id.orEmpty()
    val isOwner = conv?.ownerId == currentUserId && convId.isNotBlank()
    val mainSessionIds = remember(state.clones) {
        state.clones.filter { it.assistantRole == "main" }.map { it.sessionId }.toSet()
    }
    var showAdd by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val existingUserIds = members.filter { it.memberType == "user" }.map { it.memberId }.toSet()
    val existingAgentIds = members.filter { it.memberType == "agent" }.map { it.memberId }.toSet()
    val userCandidates = state.contacts.filter { it.id !in existingUserIds && it.id != currentUserId }
    val cloneCandidates = state.clones.filter { it.sessionId.isNotBlank() && it.sessionId !in existingAgentIds }
    Column(Modifier.fillMaxSize().background(theme.bgPrimary).hypergridDots(theme).statusBarsPadding()) {
        TopBar(
            title = conv?.name?.ifBlank { "群聊" } ?: "群聊",
            theme = theme,
            left = "‹",
            onLeft = { vm.navigate(AppScreen.GroupChat) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(MomoSpacing.lg),
        ) {
            Text(
                conv?.name?.ifBlank { "群聊" } ?: "群聊",
                color = theme.textPrimary,
                style = momoTextStyle(MomoTypography.title.copy(fontWeight = FontWeight.Bold)),
            )
            Spacer(Modifier.height(MomoSpacing.sm))
            Text("${members.size} 位成员", color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
            Spacer(Modifier.height(MomoSpacing.md))
            HorizontalDivider(color = theme.borderDefault, thickness = 0.6.dp)
            Spacer(Modifier.height(MomoSpacing.sm))
            // 任意群成员均可邀请成员(主要用于邀请自己的小莫/分身进群), 不再仅限群主.
            Row(
                Modifier.fillMaxWidth().clickable { showAdd = !showAdd }.padding(vertical = MomoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (showAdd) "收起" else "＋ 添加成员", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.body))
            }
            if (showAdd) {
                if (userCandidates.isEmpty() && cloneCandidates.isEmpty()) {
                    Text("没有可添加的成员", color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
                } else {
                    cloneCandidates.forEach { session ->
                        AddCandidateRow(
                            "莫",
                            session.name.ifBlank { "小莫" },
                            "小莫 / 分身",
                            theme,
                        ) {
                            vm.addConversationMember(
                                convId,
                                ConversationMemberInput(type = "agent", id = session.sessionId, displayName = session.name, agentSessionId = session.sessionId),
                            )
                        }
                    }
                    userCandidates.forEach { user ->
                        AddCandidateRow(
                            user.displayName.trim().firstOrNull()?.toString()?.uppercase() ?: "?",
                            user.displayName.ifBlank { user.id },
                            "成员",
                            theme,
                        ) {
                            vm.addConversationMember(convId, ConversationMemberInput(type = "user", id = user.id))
                        }
                    }
                }
                Spacer(Modifier.height(MomoSpacing.sm))
                HorizontalDivider(color = theme.borderDefault, thickness = 0.6.dp)
                Spacer(Modifier.height(MomoSpacing.sm))
            }
            members.forEach { member ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(vertical = MomoSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(theme.accentPrimary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (member.isAgent) "莫" else member.displayName.trim().firstOrNull()?.toString()?.uppercase() ?: "?",
                                color = theme.accentPrimary,
                                style = momoTextStyle(MomoTypography.headline.copy(fontWeight = FontWeight.Bold)),
                            )
                        }
                        if (member.online) {
                            // 在线绿点: 凸出头像右下角(外层 Box 不裁剪), 带白边描边更醒目.
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(13.dp)
                                    .border(2.5.dp, theme.bgSecondary, CircleShape)
                                    .clip(CircleShape).background(Color(0xFF34C759)),
                            )
                        }
                    }
                    Spacer(Modifier.width(MomoSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            member.displayName.ifBlank { member.memberId },
                            color = theme.textPrimary,
                            style = momoTextStyle(MomoTypography.body),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                member.isOwner -> "群主"
                                member.isAgent -> {
                                    val owner = member.agentOwnerName ?: "我"
                                    if (member.agentSessionId in mainSessionIds) "@$owner 的主小莫"
                                    else "@$owner 的分身${member.displayName.ifBlank { "" }}"
                                }
                                else -> "成员"
                            },
                            color = theme.textMuted,
                            style = momoTextStyle(MomoTypography.caption),
                        )
                    }
                    // 群主可移除任何非群主成员; 普通成员可移除「自己的小莫/分身」; 自己用「退出群聊」.
                    val isOwnAgent = member.isAgent && member.agentOwnerId == currentUserId
                    val canRemove = !member.isOwner &&
                        !(member.memberType == "user" && member.memberId == currentUserId) &&
                        (isOwner || isOwnAgent)
                    if (canRemove) {
                        Text(
                            "移除",
                            color = Color.Red,
                            style = momoTextStyle(MomoTypography.caption),
                            modifier = Modifier.clickable { vm.removeConversationMember(convId, member.memberType, member.memberId) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(MomoSpacing.lg))
            Text(
                "清空聊天记录",
                color = Color.Red,
                style = momoTextStyle(MomoTypography.body),
                modifier = Modifier.fillMaxWidth().clickable { showClearConfirm = true }.padding(vertical = MomoSpacing.md),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MomoSpacing.lg))
            Text(
                "退出群聊",
                color = Color.Red,
                style = momoTextStyle(MomoTypography.body),
                modifier = Modifier.fillMaxWidth().clickable { conv?.id?.let { vm.leaveConversation(it) } }.padding(vertical = MomoSpacing.md),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MomoSpacing.lg))
        }
    }
    if (showClearConfirm) {
        ConfirmDialog(
            title = "清空聊天记录",
            message = "确定清空该群聊的全部消息？清空后仅本端不再显示，不会影响其他成员。",
            theme = theme,
            confirmLabel = "清空",
            onConfirm = {
                showClearConfirm = false
                vm.clearActiveGroupMessages()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

@Composable
private fun AddCandidateRow(avatar: String, name: String, sub: String, theme: MomoTheme, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable { onAdd() }.padding(vertical = MomoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(theme.accentPrimary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(avatar, color = theme.accentPrimary, style = momoTextStyle(MomoTypography.body))
        }
        Spacer(Modifier.width(MomoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(name, color = theme.textPrimary, style = momoTextStyle(MomoTypography.body), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
        }
        Text("添加", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.caption))
    }
}

@Composable
private fun GroupMessageRow(
    message: ConversationMessage,
    theme: MomoTheme,
    isCurrentUser: Boolean,
    currentUserName: String?,
    onPlay: () -> Unit = {},
    speakingMessageId: String? = null,
    onStopSpeaking: () -> Unit = {},
    isFetching: Boolean = false,
    onDelete: ((ConversationMessage) -> Unit)? = null,
) {
    val isAgent = message.isAgent
    // 当前用户发的（非 agent）在右；agent 与其他用户在左。
    val alignRight = isCurrentUser
    var showActions by remember(message.id) { mutableStateOf(false) }
    val hideKeyboard = rememberHideKeyboard()
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!alignRight) {
            if (isAgent) {
                MomoLogo(24.dp, animated = false, lite = true)
            } else {
                UserAvatar(24.dp, message.senderName.ifBlank { message.senderId })
            }
            Spacer(Modifier.width(MomoSpacing.xs))
        }
        Column(
            horizontalAlignment = if (alignRight) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            if (!alignRight) {
                Text(
                    message.senderName.ifBlank { if (isAgent) "小莫" else "成员" },
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.caption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = MomoSpacing.xs, start = MomoSpacing.xs),
                )
            }
            val bubbleShape = messageBubbleShape(isUser = alignRight)
            Box(
                Modifier
                    .then(
                        if (!theme.dark) Modifier.shadow(
                            elevation = if (alignRight) 2.dp else 1.5.dp,
                            shape = bubbleShape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.06f),
                            spotColor = Color.Black.copy(alpha = 0.10f),
                        )
                        else Modifier
                    )
                    .then(
                        if (theme.dark && !alignRight) Modifier.border(0.5.dp, theme.borderDefault, bubbleShape)
                        else Modifier
                    )
                    .clip(bubbleShape)
                    .background(if (alignRight) theme.bubbleBg else theme.bubbleMomo)
                    .combinedClickable(
                        interactionSource = remember(message.id) { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            // 点击气泡: 收起键盘 + 停止"其他"消息的语音(正在播放的本条不停止, 便于看内容)。
                            hideKeyboard()
                            if (speakingMessageId != null && speakingMessageId != "group-${message.id}") onStopSpeaking()
                            showActions = false
                        },
                        onLongClick = {
                            // 长按文本由 SelectionContainer 接管弹原生选区; 此处保留操作菜单兜底。
                            showActions = !showActions
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                // SelectionContainer 让长按文本触发原生选区工具栏(选取/全选/复制);
                // 外层 combinedClickable.onLongClick 保留作为"操作菜单"兜底(命中空白边距时)。
                SelectionContainer {
                    if (alignRight) {
                        Text(
                            message.content,
                            color = Color.White,
                            style = momoTextStyle(MomoTypography.body),
                        )
                    } else {
                        MarkdownMessageBody(content = message.content, theme = theme)
                    }
                }
            }
            if (isFetching) {
                // 取音频/连接中: 操作菜单已关闭, 在气泡下方常驻转圈给出 loading 反馈。
                Row(
                    modifier = Modifier.align(if (alignRight) Alignment.End else Alignment.Start).padding(top = MomoSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = theme.accentPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("语音加载中…", color = theme.textMuted, style = momoTextStyle(MomoTypography.caption))
                }
            }
            if (showActions && (message.content.isNotBlank() || onDelete != null)) {
                Spacer(Modifier.height(MomoSpacing.xs))
                Row(
                    modifier = Modifier.align(if (alignRight) Alignment.End else Alignment.Start),
                    horizontalArrangement = Arrangement.spacedBy(MomoSpacing.xs),
                ) {
                    if (message.content.isNotBlank()) {
                        MessageActionPill(
                            label = "复制",
                            theme = theme,
                            onClick = {
                                clipboard.setText(AnnotatedString(message.content))
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showActions = false
                            },
                        )
                        MessagePlayIconButton(theme = theme, loading = isFetching) {
                            onPlay()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showActions = false
                        }
                    }
                    if (onDelete != null) {
                        MessageActionPill(
                            label = "删除",
                            theme = theme,
                            danger = true,
                            onClick = {
                                onDelete(message)
                                showActions = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(MomoSpacing.xs))
            val timeText = formatBackendTime(message.createdAt)
            if (timeText.isNotBlank()) {
                Text(
                    timeText,
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.caption),
                )
            }
        }
        if (alignRight) {
            Spacer(Modifier.width(MomoSpacing.xs))
            UserAvatar(24.dp, currentUserName)
        }
    }
}

@Composable
private fun GroupChatInputBar(
    state: UiState,
    composerState: ComposerUiState,
    theme: MomoTheme,
    vm: MomoAppViewModel,
    members: List<ConversationMember>,
    currentUserId: String,
) {
    // 输入文本本地持有，避免按键经大 UiState 往返造成卡顿。
    // 用 TextFieldValue 让"外部插入 @xxx" 后能把 selection 移到末尾，光标就不会停在原处。
    var text by remember { mutableStateOf(TextFieldValue("")) }
    // 本次要 @ 的成员（agent）。用 mutableStateListOf 以快照观察。
    val mentions = remember { mutableStateListOf<ConversationMemberInput>() }
    var showMentionPicker by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val mentionableMembers = members.filter { it.memberType != "user" || it.memberId != currentUserId }
    // 主小莫(assistantRole == "main")用于在 popup 区分"主小莫"和"分身xx"。
    val mainSessionIds = remember(state.clones) {
        state.clones.filter { it.assistantRole == "main" }.map { it.sessionId }.toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.bgSecondary)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = MomoSpacing.sm, vertical = MomoSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(MomoSpacing.xs),
    ) {
        // 已选 @ 列表（chips）
        if (mentions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MomoSpacing.xs),
            ) {
                mentions.forEach { m ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(MomoCorners.pill))
                            .background(theme.accentPrimary.copy(alpha = 0.12f))
                            .clickable {
                                mentions.remove(m)
                                val stripped = text.text.removeSuffix("@${m.displayName ?: m.id} ")
                                text = TextFieldValue(stripped, selection = TextRange(stripped.length))
                            }
                            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.xs),
                    ) {
                        Text(
                            "@${m.displayName ?: m.id}",
                            color = theme.accentPrimary,
                            style = momoTextStyle(MomoTypography.caption),
                        )
                    }
                }
            }
        }
        // @ 触发的成员选择浮层（仅 agent 成员，@ 小莫由后端 P3 触发；移动端只传 mentions）
        if (showMentionPicker && mentionableMembers.isNotEmpty()) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, with(LocalDensity.current) { (-48).dp.roundToPx() }),
                onDismissRequest = { showMentionPicker = false },
                properties = PopupProperties(focusable = false),
            ) {
            Column(
                Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(MomoCorners.card))
                    .background(theme.bgSecondary)
                    .border(1.dp, theme.borderDefault, RoundedCornerShape(MomoCorners.card))
                    .padding(MomoSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(MomoSpacing.xs),
            ) {
                Text(
                    "@选择用户",
                    color = theme.textMuted,
                    style = momoTextStyle(MomoTypography.sectionHeader),
                    modifier = Modifier.padding(start = MomoSpacing.sm, top = MomoSpacing.xs),
                )
                mentionableMembers.forEach { member ->
                    val already = mentions.any { it.id == member.memberId }
                    val isMemberMain = member.isAgent && member.agentSessionId in mainSessionIds
                    val ownerName = if (member.isAgent) (member.agentOwnerName ?: "我") else null
                    // 主小莫 → "@(用户名)的主小莫"; 分身 → "@(用户名)的(分身名)"; 真人 → 显示名。
                    val label = when {
                        !member.isAgent -> "@${member.displayName.ifBlank { member.memberId }}"
                        isMemberMain -> "@${ownerName ?: "我"}的主小莫"
                        else -> "@${ownerName ?: "我"}的${member.displayName.ifBlank { "分身" }}"
                    }
                    val subLine = if (member.isAgent) member.displayName.ifBlank { null } else null
                    val insertName = "$label "
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MomoCorners.medium))
                            .background(if (already) theme.accentPrimary.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable {
                                if (!already) {
                                    mentions.add(
                                        ConversationMemberInput(
                                            type = if (member.isAgent) "agent" else "user",
                                            // agent 成员用 session id 作为 id（与建群时一致），便于后端
                                            // triggerAgentMentions 定位 agent session 并把回复广播回群聊。
                                            id = if (member.isAgent) (member.agentSessionId ?: member.memberId) else member.memberId,
                                            displayName = member.displayName,
                                            agentSessionId = member.agentSessionId,
                                        ),
                                    )
                                    // 用户已输入触发的 "@"，这里只补 "@name "(避免出现 "@@name")：
                                    // 若末尾已有 "@"，先去掉再插入，保证恰好一个 "@"。
                                    val base = if (text.text.endsWith("@")) text.text.dropLast(1) else text.text
                                    val newText = base + insertName
                                    text = TextFieldValue(newText, selection = TextRange(newText.length))
                                }
                                showMentionPicker = false
                            }
                            .padding(horizontal = MomoSpacing.md, vertical = MomoSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (member.isAgent) {
                            MomoLogo(24.dp, animated = false, lite = true)
                        } else {
                            UserAvatar(24.dp, member.displayName.ifBlank { member.memberId })
                        }
                        Spacer(Modifier.width(MomoSpacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                label,
                                color = theme.textPrimary,
                                style = momoTextStyle(MomoTypography.body),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subLine != null) {
                                Text(
                                    subLine,
                                    color = theme.textMuted,
                                    style = momoTextStyle(MomoTypography.caption),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (already) {
                            Text("✓", color = theme.accentPrimary, style = momoTextStyle(MomoTypography.body))
                        }
                    }
                }
            }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MomoSpacing.sm),
        ) {
            // 左: +号(附件选择)
            IconButtonCircle(
                theme,
                size = 36.dp,
                background = Color.Transparent,
                borderColor = Color.Transparent,
                onClick = vm::pickAttachments,
            ) {
                PlusIcon(theme.textMuted)
            }
            // 中: voiceMode 显示"按住说话", 否则输入框(圆角灰色背景, 微信式)。
            // 输入 "@" 仍可触发 @选择浮层(无独立 @按钮)。
            if (voiceMode) {
                VoiceHoldButton(composerState, theme, vm, Modifier.weight(1f), group = true)
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = { newValue ->
                        text = newValue
                        val lastChar = newValue.text.lastOrNull()
                        if (lastChar == '@' && mentionableMembers.isNotEmpty()) {
                            showMentionPicker = true
                        } else if (lastChar != null && lastChar != '@' && newValue.text.endsWith("@")) {
                            showMentionPicker = false
                        }
                    },
                    // 默认单行(40dp、文本垂直居中)；内容换行后随行数增高(上限 120dp)。
                    // decorationBox 用 fillMaxWidth()(非 fillMaxSize)--后者会强制取 heightIn 的
                    // maxHeight(120dp) 致输入框一开始就撑成多行高度。
                    singleLine = false,
                    textStyle = momoTextStyle(MomoTypography.body).copy(color = theme.textPrimary),
                    cursorBrush = SolidColor(theme.textPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 120.dp)
                        // 参考图: 胶囊输入框(单行≈半圆, 多行时仍是圆角矩形), 浅灰底无边框。
                        .clip(RoundedCornerShape(20.dp))
                        .background(theme.inputBg)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (text.text.isBlank()) {
                                Text("说点什么…", color = theme.textMuted, style = momoTextStyle(MomoTypography.body))
                            }
                            inner()
                        }
                    },
                )
            }
            // 右: 有文字→发送按钮; 语音模式→键盘按钮(切回文字); 否则→语音按钮(切到按住说话)。
            val canSend = text.text.isNotBlank() && !state.groupSending
            when {
                canSend -> SendButton(
                    canSend = true,
                    theme = theme,
                    onClick = {
                        val content = text.text.trim()
                        keyboard?.hide()
                        vm.sendGroupMessage(content, mentions.toList())
                        text = TextFieldValue("")
                        mentions.clear()
                        showMentionPicker = false
                    },
                )
                voiceMode -> Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable {
                        voiceMode = false
                        showMentionPicker = false
                    },
                    contentAlignment = Alignment.Center,
                ) { KeyboardIcon(theme.textMuted) }
                else -> Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable {
                        voiceMode = true
                        showMentionPicker = false
                    },
                    contentAlignment = Alignment.Center,
                ) { MicrophoneIcon(theme.textMuted) }
            }
        }
    }
}

private fun UiState.currentPresetLabel(): String {
    val personality = presetPersonalityOptions.firstOrNull { it.key == presetDraft.personality }
        ?.label
        ?.takeIf { it.isNotBlank() }
        ?: "默认 Mobius"
    val model = cloneModelOptions.firstOrNull { it.key == presetDraft.model }
        ?.let { option -> option.label.ifBlank { option.title.ifBlank { option.key } } }
        ?: presetDraft.model.ifBlank { "默认模型" }
    return "$personality · $model"
}

// 主小莫判定：与 ViewModel.isMainAssistant() 一致(assistantRole=="main" 或名称含主 Mobius/主小莫)，
// 保证历史(服务端存的"小莫"命名)Session 不会被错判成分身。
private fun Session.isMainSession(): Boolean =
    assistantRole == "main" ||
        name == "我的主 Mobius" || name.contains("主 Mobius") ||
        name == "我的主小莫" || name.contains("主小莫")

// 分身徽标序号：**忽略名称里的 #N**(历史分身名称不可靠，会出现多个都叫 #1 的情况)，
// 严格按"非主小莫"在列表中的出现顺序编号 1,2,3...；主小莫返回 null(不显示徽标)。
// 这样历史与新分身都得到正确且唯一的序号。
private fun cloneBadges(clones: List<Session>): Map<String, String?> {
    var ordinal = 0
    return clones.associate { s ->
        val badge = if (s.isMainSession()) null else { ordinal += 1; ordinal.toString() }
        s.sessionId to badge
    }
}

private fun demoSessions() = listOf(
    Session(sessionId = "demo-main", name = "我的主 Mobius", description = "你好呀，我是 Mobius...", agentStatus = "idle", lastActive = "10:24"),
    Session(sessionId = "demo-1", name = "分身 Mobius #1 - 查项目列表", description = "找到 13 个项目，正在整理...", agentStatus = "running", lastActive = "10:24"),
    Session(sessionId = "demo-2", name = "分身 Mobius #2 - 汇总今日 Issue", description = "今日新增 3 个 Issue", agentStatus = "running", lastActive = "10:25"),
    Session(sessionId = "demo-3", name = "分身 Mobius #3 - 写测试用例", description = "API 错误：timeout", agentStatus = "failed", lastActive = "10:18", jobFailed = true),
)
