package com.mobius.momo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mobius.momo.data.AndroidContext
import com.mobius.momo.ui.MomoApp
import com.mobius.momo.viewmodel.MomoAppViewModel

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MomoAppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContext.activity = this
        AndroidContext.requestInitialNotificationPermissionIfNeeded()
        viewModel = MomoAppViewModel()
        setContent { MomoApp(viewModel) }
        handleNotificationDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationDeepLink(intent)
    }

    // 点击状态栏通知(本地通知/JPush 的 PendingIntent 把 deepLink 放进 intent.data)→ 进入对应聊天。
    private fun handleNotificationDeepLink(intent: Intent?) {
        intent?.dataString?.takeIf { it.isNotBlank() }?.let { viewModel.handleDeepLink(it) }
    }

    override fun onDestroy() {
        if (AndroidContext.activity === this) AndroidContext.activity = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        AndroidContext.handlePermissionResult(requestCode, grantResults)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        AndroidContext.handleActivityResult(requestCode, resultCode, data)
    }
}
