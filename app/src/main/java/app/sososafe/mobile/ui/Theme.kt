package app.sososafe.mobile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import app.sososafe.mobile.data.UserSettings
import androidx.core.view.WindowCompat
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val LocalChinese = staticCompositionLocalOf { false }

@Composable
fun t(en: String, zh: String) = if (LocalChinese.current) zh else en

@Composable
fun SosoTheme(settings: UserSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val lightPrimary = when (settings.color) {
        "blue" -> Color(0xFF315DA8)
        "plum" -> Color(0xFF81507C)
        else -> Color(0xFF176B5B)
    }
    val darkPrimary = when (settings.color) {
        "blue" -> Color(0xFFB0C6FF)
        "plum" -> Color(0xFFF0B7E6)
        else -> Color(0xFF8BD6C0)
    }
    val lightContainer = when (settings.color) {
        "blue" -> Color(0xFFD9E2FF)
        "plum" -> Color(0xFFFFD7F2)
        else -> Color(0xFFBDEBDD)
    }

    val lightOnPrimaryContainer = when (settings.color) {
        "blue" -> Color(0xFF001A41)
        "plum" -> Color(0xFF320A31)
        else -> Color(0xFF002117)
    }
    val darkOnPrimary = when (settings.color) {
        "blue" -> Color(0xFF1B2D55)
        "plum" -> Color(0xFF4C214A)
        else -> Color(0xFF00382D)
    }
    val darkOnPrimaryContainer = when (settings.color) {
        "blue" -> Color(0xFFD9E2FF)
        "plum" -> Color(0xFFFFD7F2)
        else -> Color(0xFFD0F5E7)
    }
    val darkContainer = when (settings.color) {
        "blue" -> Color(0xFF3B528C)
        "plum" -> Color(0xFF613A60)
        else -> Color(0xFF145345)
    }
    val lightSecondary = when (settings.color) {
        "blue" -> Color(0xFF565F71)
        "plum" -> Color(0xFF6C586A)
        else -> Color(0xFF4B635B)
    }
    val darkSecondary = when (settings.color) {
        "blue" -> Color(0xFFC0C7DC)
        "plum" -> Color(0xFFD7C0D2)
        else -> Color(0xFFB3CCC3)
    }
    val lightTertiary = when (settings.color) {
        "blue" -> Color(0xFF725B7E)
        "plum" -> Color(0xFF765A43)
        else -> Color(0xFF496276)
    }
    val darkTertiary = when (settings.color) {
        "blue" -> Color(0xFFD8BDE2)
        "plum" -> Color(0xFFE5C2A5)
        else -> Color(0xFFB3C9E0)
    }

    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base = when {
        settings.dynamic && dynamicAvailable -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme(
            primary = darkPrimary,
            onPrimary = darkOnPrimary,
            primaryContainer = darkContainer,
            onPrimaryContainer = darkOnPrimaryContainer,
            secondary = darkSecondary,
            tertiary = darkTertiary
        )
        else -> lightColorScheme(
            primary = lightPrimary,
            onPrimary = Color.White,
            primaryContainer = lightContainer,
            onPrimaryContainer = lightOnPrimaryContainer,
            secondary = lightSecondary,
            tertiary = lightTertiary
        )
    }

    val scheme = base.copy(
        background = if (dark) Color(0xFF101512) else Color(0xFFF7F8F6),
        surface = if (dark) Color(0xFF161C18) else Color(0xFFFCFDFC),
        surfaceContainerLowest = if (dark) Color(0xFF0C110E) else Color.White,
        surfaceContainerLow = if (dark) Color(0xFF1B211D) else Color(0xFFF0F2EF),
        surfaceContainer = if (dark) Color(0xFF202722) else Color(0xFFEAEEEA),
        surfaceContainerHigh = if (dark) Color(0xFF27302A) else Color(0xFFE4E8E3),
        surfaceContainerHighest = if (dark) Color(0xFF303A33) else Color(0xFFDDE3DD),
        outline = if (dark) Color(0xFF89948C) else Color(0xFF737D75),
        outlineVariant = if (dark) Color(0xFF3E4941) else Color(0xFFD7DED7)
    )

    val activity = LocalActivity.current
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            if (Build.VERSION.SDK_INT >= 29) it.window.isNavigationBarContrastEnforced = false
        }
    }

    val chinese = when (settings.language) {
        "zh" -> true
        "en" -> false
        else -> LocalConfiguration.current.locales[0].language == "zh"
    }

    CompositionLocalProvider(LocalChinese provides chinese) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(24.dp)
            ),
            content = content
        )
    }
}