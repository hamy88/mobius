package com.mobius.momo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 跨平台 WebView：用于在 App 内打开用户创建的「扩展应用」(mobius 拓展前端 /extension/<name>/)。
 *
 * Mobius web 端把 JWT 存在 localStorage['cc-token']，扩展前端的 extCall(→ /api/ext) 同源读取它鉴权。
 * 故加载扩展页前，把当前登录 token 注入 localStorage['cc-token']，使扩展应用在 App 内可直接用。
 *
 * @param url   扩展页地址，如 <server>/extension/<name>/
 * @param token 当前登录 JWT，注入 localStorage['cc-token']
 */
@Composable
expect fun PlatformWebView(url: String, token: String, modifier: Modifier = Modifier)
