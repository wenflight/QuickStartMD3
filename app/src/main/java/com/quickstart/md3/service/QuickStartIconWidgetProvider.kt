package com.quickstart.md3.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import com.quickstart.md3.QuickStartApp
import com.quickstart.md3.R
import com.quickstart.md3.data.AppSettings
import com.quickstart.md3.data.WidgetIconStyle
import com.quickstart.md3.ui.main.MainActivity

class QuickStartIconWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_OVERLAY) {
            if (Settings.canDrawOverlays(context)) {
                (context.applicationContext as QuickStartApp).overlayHost.toggle()
            } else {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val settings = (context.applicationContext as QuickStartApp).store.loadInitialSettingsSync()
        val pi = togglePendingIntent(context, 101)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context, settings, pi))
        }
    }

    companion object {
        const val ACTION_TOGGLE_OVERLAY = "com.quickstart.md3.ACTION_TOGGLE_OVERLAY"

        fun togglePendingIntent(context: Context, requestCode: Int = 101): PendingIntent {
            val intent = Intent(context, QuickStartIconWidgetProvider::class.java).setAction(ACTION_TOGGLE_OVERLAY)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun refreshAll(context: Context) {
            val awm = AppWidgetManager.getInstance(context) ?: return
            val cn = ComponentName(context, QuickStartIconWidgetProvider::class.java)
            val ids = awm.getAppWidgetIds(cn)
            if (ids.isEmpty()) return
            val settings = (context.applicationContext as QuickStartApp).store.loadInitialSettingsSync()
            val pi = togglePendingIntent(context, 101)
            val views = buildRemoteViews(context, settings, pi)
            for (id in ids) {
                awm.updateAppWidget(id, views)
            }
        }

        private fun buildRemoteViews(
            context: Context,
            settings: AppSettings,
            pi: PendingIntent
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_start_icon)
            views.setOnClickPendingIntent(R.id.widget_icon_root, pi)

            when (settings.widgetIconStyle) {
                WidgetIconStyle.MONET_SOLID -> {
                    views.setInt(R.id.widget_icon_box, "setBackgroundResource", R.drawable.widget_icon_monet_bg)
                    val alpha = (settings.widgetIconAlpha.coerceIn(0.15f, 1f) * 255).toInt()
                    views.setInt(R.id.widget_icon_image, "setImageAlpha", alpha)
                    views.setViewVisibility(R.id.widget_icon_image, View.VISIBLE)
                    views.setViewVisibility(
                        R.id.widget_icon_label,
                        if (settings.widgetShowLabel) View.VISIBLE else View.GONE
                    )
                }
                WidgetIconStyle.MONET_OUTLINE -> {
                    views.setInt(R.id.widget_icon_box, "setBackgroundResource", R.drawable.widget_icon_outline_bg)
                    val alpha = (settings.widgetIconAlpha.coerceIn(0.15f, 1f) * 255).toInt()
                    views.setInt(R.id.widget_icon_image, "setImageAlpha", alpha)
                    views.setViewVisibility(R.id.widget_icon_image, View.VISIBLE)
                    views.setViewVisibility(
                        R.id.widget_icon_label,
                        if (settings.widgetShowLabel) View.VISIBLE else View.GONE
                    )
                }
                WidgetIconStyle.TRANSPARENT -> {
                    views.setInt(R.id.widget_icon_box, "setBackgroundResource", android.R.color.transparent)
                    views.setInt(R.id.widget_icon_image, "setImageAlpha", 0)
                    views.setViewVisibility(R.id.widget_icon_image, View.INVISIBLE)
                    views.setViewVisibility(R.id.widget_icon_label, View.GONE)
                }
            }
            return views
        }
    }
}
