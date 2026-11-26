package com.quickstart.md3.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.RemoteViews
import com.quickstart.md3.QuickStartApp
import com.quickstart.md3.R
import com.quickstart.md3.data.SearchMode
import com.quickstart.md3.ui.main.MainActivity
import com.quickstart.md3.ui.main.MainTab

class QuickStartWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_OPEN_T9 -> {
                openWithMode(context, SearchMode.T9, MainTab.APPS)
                return
            }
            ACTION_OPEN_SHORTCUTS -> {
                openWithMode(context, SearchMode.T9, MainTab.SHORTCUTS)
                return
            }
            ACTION_OPEN_IME -> {
                openWithMode(context, SearchMode.IME, MainTab.APPS)
                return
            }
            ACTION_SHOW_OVERLAY -> {
                openWithMode(context, null, null)
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val t9Pi = actionPendingIntent(context, ACTION_OPEN_T9, 102)
        val shortcutsPi = actionPendingIntent(context, ACTION_OPEN_SHORTCUTS, 104)
        val imePi = actionPendingIntent(context, ACTION_OPEN_IME, 103)
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_start)
            views.setOnClickPendingIntent(R.id.widget_left_t9, t9Pi)
            views.setOnClickPendingIntent(R.id.widget_shortcuts, shortcutsPi)
            views.setOnClickPendingIntent(R.id.widget_right_ime, imePi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    private fun openWithMode(context: Context, mode: SearchMode?, tab: MainTab?) {
        if (Settings.canDrawOverlays(context)) {
            (context.applicationContext as QuickStartApp).overlayHost.show(mode, tab)
        } else {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (mode != null) putExtra(MainActivity.EXTRA_SEARCH_MODE, mode.name)
                if (tab != null) putExtra(MainActivity.EXTRA_INITIAL_TAB, tab.name)
            }
            context.startActivity(intent)
        }
    }

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, QuickStartWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.quickstart.md3.ACTION_SHOW_OVERLAY"
        const val ACTION_OPEN_T9 = "com.quickstart.md3.ACTION_WIDGET_OPEN_T9"
        const val ACTION_OPEN_SHORTCUTS = "com.quickstart.md3.ACTION_WIDGET_OPEN_SHORTCUTS"
        const val ACTION_OPEN_IME = "com.quickstart.md3.ACTION_WIDGET_OPEN_IME"
    }
}
