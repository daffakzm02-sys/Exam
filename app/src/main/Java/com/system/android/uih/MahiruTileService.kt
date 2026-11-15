package com.example.mahiru

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Ubin Quick Settings untuk menyalakan/mematikan bubble Mahiru satu ketukan,
 * tanpa perlu membuka aplikasi lagi setelah "Matikan" ditekan.
 */
class MahiruTileService : TileService() {

    companion object {
        /** Minta ubin yang sedang aktif memperbarui tampilannya (nyala/mati). */
        fun requestTileUpdate(context: Context) {
            requestListeningState(context, ComponentName(context, MahiruTileService::class.java))
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (BubbleService.isRunning) {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_STOP))
        } else if (Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(this, Intent(this, BubbleService::class.java))
        } else {
            // Izin overlay belum diberikan: buka aplikasi agar pengguna mengaktifkannya dari sana.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            return
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val running = BubbleService.isRunning
        t.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Mahiru"
        t.subtitle = if (running) "Aktif" else "Mati"
        t.updateTile()
    }
}
