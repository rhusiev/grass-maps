package nl.r1a.grassmaps.app.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content

internal fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.longValue(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.floatValue(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

internal fun JsonObject.doubleValue(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
