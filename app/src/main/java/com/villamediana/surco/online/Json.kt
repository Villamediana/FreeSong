package com.villamediana.surco.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lenient JSON shared by the online sources (unknown keys ignored — the APIs add fields all the time). */
internal val OnlineJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

/** Null-safe navigation over untyped JSON (YouTube's InnerTube responses are deep and change often). */
internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
internal fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonElement?.at(path: String): JsonElement? {
    var cur: JsonElement? = this
    for (part in path.split('.')) {
        cur = when (cur) {
            is JsonObject -> cur[part]
            is JsonArray -> part.toIntOrNull()?.let { cur.getOrNull(it) }
            else -> null
        } ?: return null
    }
    return cur
}
internal fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

/** Text of an InnerTube `{runs:[{text}]}` or `{simpleText}` object. */
internal fun JsonElement?.text(): String? {
    if (this !is JsonObject) return null
    str("simpleText")?.let { return it }
    val runs = arr("runs") ?: return null
    return runs.objects().joinToString("") { it.str("text").orEmpty() }.ifEmpty { null }
}

/** First value of [key] anywhere below this element (depth-first). */
internal fun JsonElement?.findFirst(key: String): JsonElement? {
    when (this) {
        is JsonObject -> {
            this[key]?.let { return it }
            for (v in values) v.findFirst(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirst(key)?.let { return it }
        else -> Unit
    }
    return null
}

internal fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
