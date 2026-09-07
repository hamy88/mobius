package com.mobius.momo.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp

actual fun Modifier.momoBlur(radius: Int): Modifier = blur(radius.dp)
