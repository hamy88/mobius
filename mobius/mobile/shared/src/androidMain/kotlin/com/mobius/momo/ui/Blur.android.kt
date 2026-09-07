package com.mobius.momo.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

actual fun Modifier.momoBlur(radius: Int): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        graphicsLayer {
            renderEffect = RenderEffect
                .createBlurEffect(radius.toFloat(), radius.toFloat(), Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    } else {
        background(Color.Black.copy(alpha = 0.05f))
    }
