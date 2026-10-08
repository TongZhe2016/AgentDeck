package com.worldcopy.agentdeck.feature.workspace

import org.json.JSONObject

/** Refresh one recent page while retaining older summaries already loaded on this device. */
internal fun mergeSessionPage(existing: List<JSONObject>, page: List<JSONObject>, more: Boolean): List<JSONObject> =
    (if (more) existing + page else page + existing).distinctBy { it.getString("id") }
