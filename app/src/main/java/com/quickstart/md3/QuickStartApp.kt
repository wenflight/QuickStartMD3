package com.quickstart.md3

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.quickstart.md3.data.AppRepository
import com.quickstart.md3.data.DataStoreRepository
import com.quickstart.md3.data.KeySoundPlayer
import com.quickstart.md3.service.FloatingBubbleService
import com.quickstart.md3.service.OverlayPanelHost
import com.quickstart.md3.shizuku.ShizukuManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class QuickStartApp : Application() {

    lateinit var store: DataStoreRepository
        private set
    lateinit var appRepo: AppRepository
        private set
    lateinit var soundPlayer: KeySoundPlayer
        private set
    lateinit var overlayHost: OverlayPanelHost
        private set

    private val _packageChangedFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val packageChangedFlow: SharedFlow<String> = _packageChangedFlow.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        store = DataStoreRepository(this)
        appRepo = AppRepository(this, store)
        soundPlayer = KeySoundPlayer(this)
        overlayHost = OverlayPanelHost(this)
        ShizukuManager.init(this)
        registerPackageWatcher()
        val initialSettings = store.loadInitialSettingsSync()
        if (FloatingBubbleService.shouldKeepServiceAlive(initialSettings)) {
            runCatching { FloatingBubbleService.syncWithSettings(this, initialSettings) }
        }
    }

    private fun registerPackageWatcher() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart ?: return
                appRepo.evictIcon(pkg)
                _packageChangedFlow.tryEmit(pkg)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }
}
