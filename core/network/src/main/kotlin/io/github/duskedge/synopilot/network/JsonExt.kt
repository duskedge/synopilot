package io.github.duskedge.synopilot.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// DSM 各版本返回的字段类型不完全一致（数字有时是字符串），这里统一做宽松读取。

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

internal fun JsonObject?.child(key: String): JsonObject? = this?.get(key).obj()
internal fun JsonObject?.array(key: String): List<JsonObject> = this?.get(key).arr()?.mapNotNull { it.obj() }.orEmpty()

internal fun JsonObject?.str(key: String): String? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

internal fun JsonObject?.long(key: String): Long? {
    val s = str(key) ?: return null
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
}

internal fun JsonObject?.double(key: String): Double? = str(key)?.toDoubleOrNull()
internal fun JsonObject?.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject?.bool(key: String): Boolean? = when (str(key)?.lowercase()) {
    "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> null
}
