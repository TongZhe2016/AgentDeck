package com.worldcopy.agentdeck.core.notifications

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.worldcopy.agentdeck.MainActivity

class RunNotifications(private val context: Context) {
    private val preferences = context.getSharedPreferences("notification-cursors", Context.MODE_PRIVATE)
    fun show(hostId: String, threadId: String, seq: Long, title: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (seq <= preferences.getLong(hostId, 0)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("execution", "执行与审批", NotificationManager.IMPORTANCE_DEFAULT))
        val id = preferences.getInt("nextId", 100) + 1
        val intent = Intent(context, MainActivity::class.java).putExtra("hostId", hostId).putExtra("threadId", threadId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id, Notification.Builder(context, "execution").setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText("点击查看对应会话").setContentIntent(pending).setAutoCancel(true).build())
        preferences.edit().putLong(hostId, seq).putInt("nextId", id).apply()
    }
}
