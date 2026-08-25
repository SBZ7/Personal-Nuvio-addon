package com.nuviostremio.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.nuviostremio.R
import com.nuviostremio.engine.NuvioJsRunner
import com.nuviostremio.engine.PluginManager
import com.nuviostremio.ui.MainActivity

private const val TAG = "StremioFgService"
private const val CHANNEL_ID = "nuvio_server_channel"
private const val NOTIF_ID = 1001

class StremioForegroundService : Service() {

    private var server: StremioAddonServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (server?.isAlive == true) return
        val pm = PluginManager(applicationContext)
        val runner = NuvioJsRunner(pm)
        server = StremioAddonServer(pm, runner)
        try {
            server!!.start()
            Log.i(TAG, "Server started")
            startForeground(NOTIF_ID, buildNotification("Running on port 8585"))
            sendBroadcast(Intent(ACTION_SERVER_STATE).putExtra("running", true))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            stopSelf()
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        sendBroadcast(Intent(ACTION_SERVER_STATE).putExtra("running", false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NuvioStremio Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Stremio addon server running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StremioForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NuvioStremio")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_server)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.nuviostremio.START_SERVER"
        const val ACTION_STOP = "com.nuviostremio.STOP_SERVER"
        const val ACTION_SERVER_STATE = "com.nuviostremio.SERVER_STATE"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, StremioForegroundService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StremioForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
