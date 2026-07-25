package io.github.arnonym.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

fun JsonObject.stringOrNull(key: String): String? = this[key]?.stringValueOrNull()

fun JsonObject.stringOrDefault(
    key: String,
    default: String,
): String = stringOrNull(key) ?: default

fun JsonObject.boolOrDefault(
    key: String,
    default: Boolean,
): Boolean = this[key]?.boolValueOrNull() ?: default

fun JsonObject.boolOrNull(key: String): Boolean? = this[key]?.boolValueOrNull()

fun JsonObject.intOrDefault(
    key: String,
    default: Int,
): Int = this[key]?.intValueOrNull() ?: default

fun JsonObject.doubleOrDefault(
    key: String,
    default: Double,
): Double = this[key]?.doubleValueOrNull() ?: default

fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

fun JsonElement.stringValueOrNull(): String? =
    when (this) {
        is JsonNull -> null
        is JsonPrimitive -> contentOrNull
        else -> null
    }

fun JsonElement.intValueOrNull(): Int? =
    when (this) {
        is JsonPrimitive -> intOrNull ?: contentOrNull?.trim()?.toIntOrNull()
        else -> null
    }

fun JsonElement.doubleValueOrNull(): Double? =
    when (this) {
        is JsonPrimitive -> doubleOrNull ?: contentOrNull?.trim()?.toDoubleOrNull()
        else -> null
    }

fun JsonElement.boolValueOrNull(): Boolean? =
    when (this) {
        is JsonPrimitive -> booleanOrNull ?: contentOrNull?.let { it.equals("true", ignoreCase = true) }
        else -> null
    }

fun JsonObject.isNullOrMissing(key: String): Boolean = this[key] == null || this[key] is JsonNull

fun JsonElement?.orEmptyObject(): JsonObject = (this as? JsonObject) ?: JsonObject(emptyMap())

fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
