package com.worldcopy.agentdeck.core.notifications

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.MainActivity

class ConnectionService : Service() {
    companion object { var active by mutableStateOf(false); private set }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("connection", "后台连接", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, ConnectionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "connection").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AgentDeck 正在同步电脑状态").setContentText("接收回复和待处理事项").setOngoing(true)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "断开连接", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, notification)
        active = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as AgentDeckApplication
        if (intent?.action == "stop") {
            app.updateKeepConnected(false); app.disconnectAll(); stopSelf(); return START_NOT_STICKY
        }
        if (!app.keepConnected) { stopSelf(); return START_NOT_STICKY }
        if (intent == null) app.hosts.syncProjects(refreshConnected = false)
        return START_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as AgentDeckApplication).disconnectAll()
        stopSelf()
    }
    override fun onDestroy() { active = false; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
