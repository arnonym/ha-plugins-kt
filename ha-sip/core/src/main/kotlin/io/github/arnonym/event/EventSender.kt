package io.github.arnonym.event

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

class EventSender {
    private val callbacks = CopyOnWriteArrayList<(JsonObject, String?) -> Unit>()

    fun registerSender(callback: (event: JsonObject, webhookId: String?) -> Unit) {
        callbacks.add(callback)
    }

    fun sendEvent(
        event: JsonObject,
        webhookId: String? = null,
    ) {
        callbacks.forEach { it(event, webhookId) }
    }
}
