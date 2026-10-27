/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.content.res.Configuration
import android.app.UiModeManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.ui.settings.StremioAddonsScreen
import app.opentv.ui.theme.OpenTvTheme
import app.opentv.update.UpdateGate

/**
 * The whole app is a single screen: manage the Stremio add-on and press "refresh" to resolve its
 * channels and publish them (with EPG) into Live Channels via the TV Input Framework. Watching
 * happens in Live Channels, so OpenTV carries no player, channel grid or VOD of its own.
 */
class MainActivity : ComponentActivity() {

    // Apply the chosen UI language before any view or resource is resolved.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(app.opentv.core.LocaleUtils.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTelevision = isRunningOnTelevision(this)
        val activity = this

        setContent {
            val settings = remember { ServiceLocator.get(activity).settings }
            val themeMode by settings.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                AppSettings.ThemeMode.DARK -> true
                AppSettings.ThemeMode.LIGHT -> false
                AppSettings.ThemeMode.SYSTEM -> isTelevision || isSystemInDarkTheme()
            }
            OpenTvTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        StremioAddonsScreen(onBack = { activity.finish() })
                        // Sits above the screen so a found update can prompt from here.
                        UpdateGate()
                    }
                }
            }
        }
    }
}

/**
 * Detects a ten-foot device, so the theme can default to dark on a TV.
 */
fun isRunningOnTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val packageManager = context.packageManager
    return packageManager.hasSystemFeature("android.software.leanback") ||
        packageManager.hasSystemFeature("android.hardware.type.television")
}
