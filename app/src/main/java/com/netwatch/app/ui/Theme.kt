package com.netwatch.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.netwatch.app.router.Grade

object Palette {
    val background = Color(0xFF0D1117)
    val surface = Color(0xFF161B22)
    val surfaceHigh = Color(0xFF21262D)
    val outline = Color(0xFF30363D)
    val text = Color(0xFFE6EDF3)
    val muted = Color(0xFF8B949E)
    val down = Color(0xFF3DDC97)
    val up = Color(0xFF58A6FF)
    val warn = Color(0xFFE3B341)
    val bad = Color(0xFFF85149)
    val good = Color(0xFF3FB950)
    val excellent = Color(0xFF56D364)

    fun grade(g: Grade?) = when (g) {
        Grade.EXCELLENT -> excellent
        Grade.GOOD -> good
        Grade.FAIR -> warn
        Grade.POOR -> bad
        null -> muted
    }
}

@Composable
fun NetWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.down,
            onPrimary = Color(0xFF00210F),
            secondary = Palette.up,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.muted,
            surfaceContainer = Palette.surface,
            surfaceContainerHigh = Palette.surfaceHigh,
            outline = Palette.outline,
            error = Palette.bad,
        ),
        content = content,
    )
}
