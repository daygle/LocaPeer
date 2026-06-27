package com.locapeer.sharing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ScheduleRule(
    val id: String,
    val label: String = "",
    val days: Int = 0b1111111,
    val startMinute: Int = 0,
    val endMinute: Int = 1439
)

fun newScheduleRule() = ScheduleRule(id = java.util.UUID.randomUUID().toString())

fun String.toScheduleRules(): List<ScheduleRule> =
    try { Json { ignoreUnknownKeys = true }.decodeFromString(this) }
    catch (_: Exception) { emptyList() }
