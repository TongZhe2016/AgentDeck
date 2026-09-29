package com.worldcopy.agentdeck.feature.projects

import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.feature.workspace.string
import org.json.JSONObject

data class ProjectKey(val hostId: String, val path: String)
data class ProjectGroup(val key: ProjectKey, val hostName: String, val sessions: List<JSONObject>) {
    val name get() = key.path.trimEnd('/').substringAfterLast('/').ifBlank { key.path.ifBlank { "未记录项目目录" } }
}

fun groupProjects(hosts: List<Host>, sessions: Map<String, List<JSONObject>>): List<ProjectGroup> =
    hosts.flatMap { host ->
        sessions[host.id].orEmpty().distinctBy { it.getString("id") }.groupBy { it.string("cwd").trimEnd('/').ifBlank { if (it.string("cwd").startsWith('/')) "/" else "" } }
            .map { (path, threads) -> ProjectGroup(ProjectKey(host.id, path), host.name, threads.sortedByDescending { it.optLong("updatedAt") }) }
    }.sortedWith(compareByDescending<ProjectGroup> { it.sessions.maxOfOrNull { session -> session.optLong("updatedAt") } ?: 0 }
        .thenBy { it.hostName }.thenBy { it.key.path })
