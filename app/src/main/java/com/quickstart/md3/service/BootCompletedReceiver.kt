package com.quickstart.md3.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.quickstart.md3.QuickStartApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as QuickStartApp
                val s = app.store.settingsFlow.first()
                if ((s.floatingBubbleEnabled && s.floatingBubbleBootStart) ||
                    (s.notificationEnabled && s.notificationBootStart) ||
                    (s.edgeTriggerEnabled && (s.floatingBubbleBootStart || s.notificationBootStart))
                ) {
                    FloatingBubbleService.syncWithSettings(context, s)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
