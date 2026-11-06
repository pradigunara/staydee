package io.github.pradigunara.staydee.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

// All Miuix calls live in this package (plan §13.3), so an API break or a swap
// to Material 3 touches ui/ only.

@Composable
fun StaydeeTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }
    MiuixTheme(controller = controller, content = content)
}

@Composable
fun StaydeeScreen(title: String, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = title) }, content = content)
}
