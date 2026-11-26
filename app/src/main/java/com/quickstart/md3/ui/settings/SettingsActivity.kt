package com.quickstart.md3.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickstart.md3.QuickStartApp
import com.quickstart.md3.data.AppSettings
import com.quickstart.md3.ui.theme.QuickStartMD3Theme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as QuickStartApp

        setContent {
            val settings by app.store.settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
            QuickStartMD3Theme(settings = settings) {
                SettingsScreen(
                    app = app,
                    settings = settings,
                    onBack = { finish() }
                )
            }
        }
    }
}
