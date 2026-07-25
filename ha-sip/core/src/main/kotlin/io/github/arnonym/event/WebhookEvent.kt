package io.github.arnonym.event

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

sealed class WebhookEvent(val eventName: String) {
    open fun extraFields(): Map<String, JsonElement> = emptyMap()

    data object IncomingCall : WebhookEvent("incoming_call")

    data object OutgoingCallInitiated : WebhookEvent("outgoing_call_initiated")

    data object CallEstablished : WebhookEvent("call_established")

    data object CallDisconnected : WebhookEvent("call_disconnected")

    data object RingTimeout : WebhookEvent("ring_timeout")

    data class EnteredMenu(val menuId: String) : WebhookEvent("entered_menu") {
        override fun extraFields() = mapOf("menu_id" to JsonPrimitive(menuId))
    }

    data class DtmfDigit(val digit: String) : WebhookEvent("dtmf_digit") {
        override fun extraFields() = mapOf("digit" to JsonPrimitive(digit))
    }

    data class Timeout(val menuId: String?) : WebhookEvent("timeout") {
        override fun extraFields() = mapOf("menu_id" to (menuId?.let { JsonPrimitive(it) } ?: JsonNull))
    }

    data class PlaybackDoneAudioFile(val audioFile: String) : WebhookEvent("playback_done") {
        override fun extraFields() =
            mapOf(
                "type" to JsonPrimitive("audio_file"),
                "audio_file" to JsonPrimitive(audioFile),
            )
    }

    data class PlaybackDoneMessage(val message: String) : WebhookEvent("playback_done") {
        override fun extraFields() =
            mapOf(
                "type" to JsonPrimitive("message"),
                "message" to JsonPrimitive(message),
            )
    }

    data class RecordingStarted(val recordingFile: String) : WebhookEvent("recording_started") {
        override fun extraFields() = mapOf("recording_file" to JsonPrimitive(recordingFile))
    }

    data class RecordingStopped(val recordingFile: String) : WebhookEvent("recording_stopped") {
        override fun extraFields() = mapOf("recording_file" to JsonPrimitive(recordingFile))
    }
}
