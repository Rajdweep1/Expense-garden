package com.expensegarden.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Which way the status and navigation bar glyphs are drawn, declared per screen.
 *
 * One XML value cannot be right everywhere. In dark mode the dashboard is dark and wants light
 * glyphs, while home still shows a bright daytime sky and wants dark ones — the canvas keeps its
 * own palette and never follows the theme (spec §3.2), so only the screen knows.
 *
 * @param darkIcons true to draw dark glyphs, i.e. for a LIGHT background behind the bars.
 */
@Composable
fun SystemBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        // "AppearanceLight" describes the BAR, not the glyphs: light bar => dark glyphs.
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}
