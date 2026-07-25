package io.github.arnonym.sensor

import io.github.arnonym.ha.HaClient
import io.github.arnonym.json.objectOrNull
import io.github.arnonym.json.stringOrNull
import io.github.arnonym.log.log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime

data class SensorConfig(
    val enabled: Boolean,
    val entityPrefix: String,
)

interface SensorUpdates {
    fun setCallActive(
        accountIndex: Int,
        callInfo: JsonObject,
    )

    fun setCallInactive(accountIndex: Int)

    fun updateRegistrationStatus(
        accountIndex: Int,
        code: Int,
        reason: String,
    )

    fun updateLastCall(
        accountIndex: Int,
        direction: String,
        callInfo: JsonObject? = null,
    )

    fun initializeSensors()
}

class SensorUpdater(
    private val haClient: HaClient,
    private val sensorConfig: SensorConfig,
    private val enabledAccounts: List<Int>,
) : SensorUpdates {
    private fun sanitizedPrefix(): String = sensorConfig.entityPrefix.replace('-', '_').lowercase()

    private fun entityId(accountIndex: Int): String = "sensor.${sanitizedPrefix()}_account_$accountIndex"

    private fun registrationEntityId(accountIndex: Int): String = "sensor.${sanitizedPrefix()}_registration_$accountIndex"

    private fun lastCallEntityId(accountIndex: Int): String = "sensor.${sanitizedPrefix()}_last_call_$accountIndex"

    private fun updateSensor(
        entityId: String,
        state: String,
        attributes: Map<String, JsonElement?>,
    ) {
        val filtered = JsonObject(attributes.filterValues { it != null }.mapValues { it.value!! })
        haClient.updateSensorState(entityId, state, filtered)
    }

    override fun setCallActive(
        accountIndex: Int,
        callInfo: JsonObject,
    ) {
        if (!sensorConfig.enabled) return
        val attributes =
            mapOf(
                "friendly_name" to "SIP Account $accountIndex".toJson(),
                "icon" to "mdi:phone-in-talk".toJson(),
                "caller" to callInfo.stringOrNull("remote_uri")?.toJson(),
                "called" to callInfo.stringOrNull("local_uri")?.toJson(),
                "parsed_caller" to callInfo.stringOrNull("parsed_remote_uri")?.toJson(),
                "parsed_called" to callInfo.stringOrNull("parsed_local_uri")?.toJson(),
                "remote_uri" to callInfo.stringOrNull("remote_uri")?.toJson(),
                "local_uri" to callInfo.stringOrNull("local_uri")?.toJson(),
                "parsed_remote_uri" to callInfo.stringOrNull("parsed_remote_uri")?.toJson(),
                "parsed_local_uri" to callInfo.stringOrNull("parsed_local_uri")?.toJson(),
                "sip_account" to callInfo["sip_account"],
                "call_id" to callInfo.stringOrNull("call_id")?.toJson(),
                "internal_id" to callInfo.stringOrNull("internal_id")?.toJson(),
                "headers" to (callInfo.objectOrNull("headers") ?: JsonObject(emptyMap())),
            )
        updateSensor(entityId(accountIndex), "true", attributes)
    }

    override fun setCallInactive(accountIndex: Int) {
        if (!sensorConfig.enabled) return
        val attributes =
            mapOf(
                "friendly_name" to "SIP Account $accountIndex".toJson(),
                "icon" to "mdi:phone".toJson(),
            )
        updateSensor(entityId(accountIndex), "false", attributes)
    }

    override fun updateRegistrationStatus(
        accountIndex: Int,
        code: Int,
        reason: String,
    ) {
        if (!sensorConfig.enabled) return
        val (state, icon) =
            when (code) {
                200 -> "registered" to "mdi:phone-check"
                0 -> "unregistered" to "mdi:phone-off"
                else -> "failed" to "mdi:phone-alert"
            }
        val attributes =
            mapOf(
                "friendly_name" to "SIP Registration $accountIndex".toJson(),
                "icon" to icon.toJson(),
                "status_code" to code.toJson(),
                "reason" to reason.toJson(),
                "last_change" to LocalDateTime.now().toString().toJson(),
            )
        updateSensor(registrationEntityId(accountIndex), state, attributes)
    }

    override fun updateLastCall(
        accountIndex: Int,
        direction: String,
        callInfo: JsonObject?,
    ) {
        if (!sensorConfig.enabled) return
        val icon =
            when (direction) {
                "none" -> "mdi:phone"
                "incoming" -> "mdi:phone-incoming"
                else -> "mdi:phone-outgoing"
            }
        val attributes =
            mutableMapOf<String, JsonElement?>(
                "friendly_name" to "SIP Last Call $accountIndex".toJson(),
                "icon" to icon.toJson(),
            )
        if (callInfo != null) {
            attributes["caller"] = callInfo.stringOrNull("remote_uri")?.toJson()
            attributes["called"] = callInfo.stringOrNull("local_uri")?.toJson()
            attributes["parsed_caller"] = callInfo.stringOrNull("parsed_remote_uri")?.toJson()
            attributes["parsed_called"] = callInfo.stringOrNull("parsed_local_uri")?.toJson()
            attributes["remote_uri"] = callInfo.stringOrNull("remote_uri")?.toJson()
            attributes["local_uri"] = callInfo.stringOrNull("local_uri")?.toJson()
            attributes["parsed_remote_uri"] = callInfo.stringOrNull("parsed_remote_uri")?.toJson()
            attributes["parsed_local_uri"] = callInfo.stringOrNull("parsed_local_uri")?.toJson()
            attributes["call_id"] = callInfo.stringOrNull("call_id")?.toJson()
            attributes["timestamp"] = LocalDateTime.now().toString().toJson()
        }
        updateSensor(lastCallEntityId(accountIndex), direction, attributes)
    }

    override fun initializeSensors() {
        if (!sensorConfig.enabled) return
        log(null, "Initializing sensors with prefix '${sensorConfig.entityPrefix}' for accounts: $enabledAccounts")
        enabledAccounts.forEach { accountIndex ->
            setCallInactive(accountIndex)
            updateSensor(
                registrationEntityId(accountIndex),
                "unknown",
                mapOf(
                    "friendly_name" to "SIP Registration $accountIndex".toJson(),
                    "icon" to "mdi:phone-clock".toJson(),
                ),
            )
            updateLastCall(accountIndex, "none")
        }
    }
}

private fun String.toJson(): JsonElement = JsonPrimitive(this)

private fun Int.toJson(): JsonElement = JsonPrimitive(this)
