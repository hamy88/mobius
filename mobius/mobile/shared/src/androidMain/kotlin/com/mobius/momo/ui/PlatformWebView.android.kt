package com.mobius.momo.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Android WebView：加载扩展页， onPageFinished 时把 cc-token 写入 localStorage 再 reload 一次，
 * 让扩展前端 main.js 启动时能读到 token(Android 无 documentStart 注入, reload-once 最稳)。
 */
@Composable
actual fun PlatformWebView(url: String, token: String, modifier: Modifier) {
    if (token.isEmpty()) return
    val safe = token.replace("\\", "\\\\").replace("'", "\\'")
    val injectJs = "try{localStorage.setItem('cc-token','$safe')}catch(e){}"
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val injected = booleanArrayOf(false)
            @SuppressLint("SetJavaScriptEnabled")
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true // localStorage 必需
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        // 首次加载完成 → 注入 token → reload; 第二次完成时 injected 已置 true, 不再重载。
                        if (!injected[0]) {
                            injected[0] = true
                            view.evaluateJavascript(injectJs, null)
                            view.reload()
                        }
                    }
                }
                loadUrl(url)
            }
        },
    )
}
