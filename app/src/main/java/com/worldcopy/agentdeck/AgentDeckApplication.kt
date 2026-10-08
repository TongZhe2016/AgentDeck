package com.worldcopy.agentdeck

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.worldcopy.agentdeck.core.notifications.ConnectionService
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.worldcopy.agentdeck.feature.hosts.HostsViewModel
import com.worldcopy.agentdeck.feature.workspace.WorkspaceViewModel

class AgentDeckApplication : Application(), ViewModelStoreOwner {
    var keepConnected by mutableStateOf(true); private set
    override fun onCreate() {
        super.onCreate()
        keepConnected = getSharedPreferences("connection", MODE_PRIVATE).getBoolean("keepConnected", true)
    }
    fun updateKeepConnected(enabled: Boolean) {
        keepConnected = enabled
        getSharedPreferences("connection", MODE_PRIVATE).edit().putBoolean("keepConnected", enabled).apply()
        if (enabled) ensureConnectionService() else stopService(Intent(this, ConnectionService::class.java))
    }
    fun ensureConnectionService() {
        if (!keepConnected || hosts.hosts.isEmpty()) stopService(Intent(this, ConnectionService::class.java))
        else if (!ConnectionService.active) startForegroundService(Intent(this, ConnectionService::class.java))
    }
    override val viewModelStore = ViewModelStore()
    private val provider by lazy { ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this)) }
    val hosts: HostsViewModel get() = provider[HostsViewModel::class.java]
    val workspaces = mutableStateMapOf<String, WorkspaceViewModel>()
    fun workspace(id: String): WorkspaceViewModel = workspaces.getOrPut(id) {
        provider["workspace-$id", WorkspaceViewModel::class.java]
    }
    fun disconnectAll() {
        workspaces.values.forEach { it.disconnect() }
        hosts.disconnectAll()
    }
}
