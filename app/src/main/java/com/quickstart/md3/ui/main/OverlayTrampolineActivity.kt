package com.quickstart.md3.ui.main

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.quickstart.md3.QuickStartApp

/**
 * Invisible no-display trampoline used by QuickSettings TileService so the notification shade
 * collapses cleanly before showing the zero-flash TYPE_APPLICATION_OVERLAY launcher panel.
 */
class OverlayTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as QuickStartApp
        Handler(Looper.getMainLooper()).postDelayed({
            app.overlayHost.show()
        }, 90L)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
