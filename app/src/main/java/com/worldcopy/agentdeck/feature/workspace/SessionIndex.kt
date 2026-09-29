package com.worldcopy.agentdeck.feature.workspace

import org.json.JSONObject

/** Follow every native history page before replacing the home index. */
internal suspend fun readAllSessions(page: suspend (String?) -> JSONObject): List<JSONObject> {
    val sessions = linkedMapOf<String, JSONObject>()
    var cursor: String? = null
    do {
        val result = page(cursor)
        result.optJSONArray("data").objects().forEach { sessions.putIfAbsent(it.getString("id"), it) }
        cursor = result.string("nextCursor").ifBlank { null }
    } while (cursor != null)
    return sessions.values.toList()
}
