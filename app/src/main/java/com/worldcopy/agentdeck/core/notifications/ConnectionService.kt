package com.worldcopy.agentdeck.core.notifications

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.MainActivity

class ConnectionService : Service() {
    companion object { var active by mutableStateOf(false); private set }
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout") // The SSH transport needs CPU while connected; released in onDestroy.
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("connection", "后台连接", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "connection").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AgentDeck 正在保持电脑连接").setContentText("后台接收回复和待处理事项").setOngoing(true)
            .setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, notification)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgentDeck:connections")
            .apply { acquire() }
        active = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as AgentDeckApplication
        if (app.hosts.hosts.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        if (intent == null) app.hosts.syncProjects(refreshConnected = false)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }
    override fun onDestroy() {
        active = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        (application as AgentDeckApplication).disconnectAll()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
