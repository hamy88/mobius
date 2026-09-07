package com.mobius.momo.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.mobius.momo.ui.MomoApp
import java.awt.Dimension

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Mobius",
        state = WindowState(width = 1080.dp, height = 760.dp),
        icon = painterResource("mobius_logo.png"),
        resizable = true,
    ) {
        window.minimumSize = Dimension(640, 480)
        MomoApp()
    }
}
