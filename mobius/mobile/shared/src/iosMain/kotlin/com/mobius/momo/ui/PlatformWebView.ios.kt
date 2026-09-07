@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.mobius.momo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/**
 * iOS WKWebView：用 WKUserScript 在 documentStart 注入 localStorage['cc-token']=token，
 * 页面脚本启动前 token 已就位(比 Android 的 reload-once 干净, 一次成型)。
 */
@Suppress("DEPRECATION", "SENSELESS_COMPARISON")
@Composable
actual fun PlatformWebView(url: String, token: String, modifier: Modifier) {
    if (token.isEmpty()) return
    val safe = token.replace("\\", "\\\\").replace("'", "\\'")
    val script = "try{localStorage.setItem('cc-token','$safe')}catch(e){}"
    UIKitView(
        factory = {
            val config = WKWebViewConfiguration()
            config.userContentController.addUserScript(
                WKUserScript(
                    source = script,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = false,
                ),
            )
            val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config)
            val nsUrl = NSURL(string = url)
            if (nsUrl != null) {
                webView.loadRequest(NSURLRequest(uRL = nsUrl))
            }
            webView
        },
        modifier = modifier,
    )
}
