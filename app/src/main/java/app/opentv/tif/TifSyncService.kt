/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.tif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs a [TifSync] pass as a foreground service.
 *
 * Why a service, not just a coroutine: this project's target boxes include TVs (TCL) whose power
 * manager ("tguard") kills any app process the moment it is backgrounded, and also denies
 * WorkManager jobs the auto-start they need. A plain sync launched from an activity's scope — or
 * from the Application scope — dies with the process the instant the launcher comes back to the
 * front, which is exactly what left the TV input with zero channels. A foreground service with an
 * ongoing notification is the one thing such a box will let keep running to completion.
 */
class TifSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        scope.launch {
            runCatching { TifSync.sync(applicationContext) }
            @Suppress("DEPRECATION") // stopForeground(int) is API 24+; this keeps minSdk 23 happy
            stopForeground(true)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OpenTV", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Live Channels sync" },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenTV")
            .setContentText("Updating channels…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "opentv_tif_sync"
        private const val NOTIFICATION_ID = 4711

        /** Start a sync that outlives the caller's own lifecycle. */
        fun start(context: Context) {
            val intent = Intent(context, TifSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
