package com.system.android.uih

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class UiHelperTileService : TileService() {

    companion object {
        fun requestTileUpdate(ctx: Context) {
            try {
                requestListeningState(
                    ctx,
                    ComponentName(ctx, UiHelperTileService::class.java)
                )
            } catch (_: Throwable) {
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()

        if (!Settings.canDrawOverlays(this)) {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivityAndCollapseCompat(i)
            return
        }

        if (BubbleService.isRunning) {
            stopService(Intent(this, BubbleService::class.java))
        } else {
            val i = Intent(this, BubbleService::class.java).apply {
                action = BubbleService.ACTION_START_CAPTURE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        }

        refreshTile(assumeRunning = !BubbleService.isRunning)
    }

    private fun refreshTile(assumeRunning: Boolean? = null) {
        val tile = qsTile ?: return
        val running = assumeRunning ?: BubbleService.isRunning

        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "UI Helper"
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_menu_view)
        tile.contentDescription = if (running) "Aktif" else "Nonaktif"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "Running" else "Off"
        }

        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}