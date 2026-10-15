package com.worldcopy.agentdeck.core.storage

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class FollowupMode(val value: String, val label: String, val description: String) {
    Queue("queue", "排队", "当前任务完成后，按顺序处理。"),
    Steer("steer", "引导", "立即把新消息加入当前任务。"),
}

class MessageSettings(context: Context) {
    private val preferences = context.getSharedPreferences("messages", Context.MODE_PRIVATE)
    var followupMode by mutableStateOf(FollowupMode.entries.firstOrNull { it.value == preferences.getString("followupMode", null) } ?: FollowupMode.Queue)
        private set
    fun choose(mode: FollowupMode) {
        followupMode = mode
        preferences.edit { putString("followupMode", mode.value) }
    }
}
