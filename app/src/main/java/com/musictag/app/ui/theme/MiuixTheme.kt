package com.musictag.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/**
 * Slightly denser typography than the MiuiX defaults: the reference HyperOS /
 * LSPosed settings screens use more compact text, especially for list rows and
 * secondary descriptions.
 */
private val CompactTextStyles: TextStyles = defaultTextStyles(
    main = TextStyle(fontSize = 15.sp),
    paragraph = TextStyle(fontSize = 15.sp, lineHeight = 1.25f.em),
    body1 = TextStyle(fontSize = 15.sp),
    body2 = TextStyle(fontSize = 13.sp),
    button = TextStyle(fontSize = 15.sp),
    footnote1 = TextStyle(fontSize = 12.sp),
    footnote2 = TextStyle(fontSize = 11.sp),
    headline1 = TextStyle(fontSize = 16.sp),
    headline2 = TextStyle(fontSize = 15.sp),
    subtitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
    title1 = TextStyle(fontSize = 30.sp),
    title2 = TextStyle(fontSize = 22.sp),
    title3 = TextStyle(fontSize = 19.sp),
    title4 = TextStyle(fontSize = 17.sp),
)

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
    MiuixTheme(
        controller = controller,
        textStyles = CompactTextStyles,
        content = content,
    )
}
