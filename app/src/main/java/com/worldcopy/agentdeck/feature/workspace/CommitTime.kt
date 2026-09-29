package com.worldcopy.agentdeck.feature.workspace

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

fun commitRelativeTime(date: String, now: Instant = Instant.now()): String {
    val time = runCatching { OffsetDateTime.parse(date).toInstant() }.getOrNull() ?: return ""
    val seconds = Duration.between(time, now).seconds
    val age = kotlin.math.abs(seconds)
    if (age < 60) return "刚刚"
    val (count, unit) = when {
        age < 3600 -> age / 60 to "分钟"
        age < 86400 -> age / 3600 to "小时"
        age < 30 * 86400L -> age / 86400 to "天"
        age < 365 * 86400L -> age / (30 * 86400L) to "个月"
        else -> age / (365 * 86400L) to "年"
    }
    return "$count$unit${if (seconds < 0) "后" else "前"}"
}
