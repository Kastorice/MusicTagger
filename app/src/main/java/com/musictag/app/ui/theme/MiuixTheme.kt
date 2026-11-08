package com.musictag.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * HyperOS/MIUI style theme.
 *
 * When [dynamicColor] is enabled the MiuiX Monet engine derives the palette from
 * the system wallpaper (Android 12+); otherwise the classic MIUI blue palette is
 * used. Dark mode follows the system in both cases.
 */
@Composable
fun MusicTagTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val mode = if (dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    val controller = remember(mode) { ThemeController(colorSchemeMode = mode) }
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controllerCompat = WindowCompat.getInsetsController(window, view)
            controllerCompat.isAppearanceLightStatusBars = !dark
            controllerCompat.isAppearanceLightNavigationBars = !dark
        }
    }
    MiuixTheme(controller = controller, content = content)
}
