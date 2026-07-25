package io.github.arnonym.integrationtest

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One event envelope as delivered by ha-sip, tagged with which instance sent it. */
data class ReceivedEvent(
    val instance: String,
    val receivedAtMillis: Long,
    val payload: JsonObject,
) {
    val eventName: String get() = string("event") ?: "<no event field>"
    val internalId: String? get() = string("internal_id")
    val menuId: String? get() = string("menu_id")
    val digit: String? get() = string("digit")

    private fun string(key: String): String? = (payload[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    override fun toString(): String =
        buildString {
            append("[$instance] $eventName")
            menuId?.let { append(" menu_id=$it") }
            digit?.let { append(" digit=$it") }
        }
}

/**
 * Captures every [io.github.arnonym.event.WebhookEvent] emitted by the instances
 * under test.
 *
 * ha-sip's Home Assistant webhook channel is a plain `POST $HA_BASE_URL/webhook/$HA_WEBHOOK_ID`
 * carrying the full envelope built by `buildWebhookEnvelope`, so pointing `HA_BASE_URL`
 * at this server and giving each instance a distinct `HA_WEBHOOK_ID` yields a complete,
 * per-instance-attributed event stream with no broker and no real Home Assistant involved.
 */
class EventCollector : Closeable {
    private val received = CopyOnWriteArrayList<ReceivedEvent>()
    private val monitor = Object()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            // NOT the default (null) executor: that one serves requests on the single
            // dispatcher thread, one at a time. `EventSender.sendEvent` fans out
            // synchronously on the emitting thread and `triggerWebhook` blocks in
            // `runBlocking { post }`, so a serial server would couple the two instances'
            // timing -- corrupting the very measurements these tests exist to take.
            executor = Executors.newCachedThreadPool { r -> Thread(r, "event-collector").apply { isDaemon = true } }
            // Anything that is not a webhook (tts_get_url, template, services, ...):
            // answer immediately, so a stray call fails fast instead of parking a call's
            // thread on a connect timeout. Scenarios use audio_file menus so this should
            // never fire; if it does, the log line below says which endpoint was hit.
            createContext("/api/") { exchange ->
                System.err.println("event-collector: unexpected HA call to ${exchange.requestURI}")
                exchange.respondEmptyJson()
            }
            // Text-to-speech, stalled on demand. A synthesis request is the one thing a
            // menu does that can take seconds, so holding it open is how a test makes one
            // call's menu handling slow enough to observe what it does (and does not)
            // delay elsewhere.
            createContext("/api/tts_get_url") { exchange ->
                ttsRequests.incrementAndGet()
                val stall = ttsStallMillis.get()
                if (stall > 0) {
                    if (VERBOSE) println("  event-collector: stalling TTS for ${stall}ms")
                    Thread.sleep(stall)
                }
                val audio = ttsAudio
                if (audio == null) {
                    // ha-sip falls back to its bundled error prompt, which is fine for
                    // scenarios that care about the delay rather than about the audio.
                    exchange.use { it.sendResponseHeaders(500, -1) }
                    return@createContext
                }
                exchange.use {
                    val body = """{"url": "$baseUrl/tts-audio/speech.wav"}""".toByteArray()
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
            // The audio the URL above points at -- the second leg of the round-trip, which
            // ha-sip downloads and converts before it can play anything.
            createContext("/api/tts-audio/") { exchange ->
                val audio = ttsAudio
                if (audio == null) {
                    exchange.use { it.sendResponseHeaders(404, -1) }
                    return@createContext
                }
                val bytes = audio.readBytes()
                exchange.use {
                    it.responseHeaders.add("Content-Type", "audio/wav")
                    it.sendResponseHeaders(200, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            start()
        }

    private val ttsStallMillis = java.util.concurrent.atomic.AtomicLong(0)
    private val ttsRequests = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Audio served for synthesis requests, or `null` to fail them with a 500.
     *
     * Null by default: most scenarios only need TTS to be slow, and a failed request
     * still exercises the whole request/playback path via the bundled error prompt.
     */
    @Volatile
    var ttsAudio: File? = null

    /** Makes every subsequent TTS request block for [millis] before answering. */
    fun stallTts(millis: Long) = ttsStallMillis.set(millis)

    /** How many synthesis requests have arrived since [clear] -- zero proves a cache hit. */
    fun ttsRequestCount(): Int = ttsRequests.get()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/api"

    /** Registers a webhook endpoint for [instance]; pass the same id as its `HA_WEBHOOK_ID`. */
    fun register(instance: String) {
        server.createContext("/api/webhook/$instance") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val payload =
                try {
                    Json.parseToJsonElement(body) as? JsonObject
                } catch (e: Exception) {
                    null
                }
            if (payload == null) {
                System.err.println("event-collector: could not parse body from $instance: $body")
            } else {
                val event = ReceivedEvent(instance, System.currentTimeMillis(), payload)
                received.add(event)
                if (VERBOSE) println("  << $event")
                synchronized(monitor) { monitor.notifyAll() }
            }
            exchange.respondEmptyJson()
        }
    }

    /** Everything received since the last [clear], in arrival order. */
    fun events(): List<ReceivedEvent> = received.toList()

    fun events(instance: String): List<ReceivedEvent> = received.filter { it.instance == instance }

    fun clear() {
        received.clear()
        ttsRequests.set(0)
    }

    /**
     * Waits for the first event on [instance] named [eventName] satisfying [predicate].
     *
     * Matching runs over the whole history rather than draining a queue, so an event
     * that arrived *before* the wait started still counts. That matters: the SIP stack
     * routinely emits several events faster than the test can ask for them.
     */
    fun await(
        instance: String,
        eventName: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        predicate: (ReceivedEvent) -> Boolean = { true },
    ): ReceivedEvent =
        awaitProbe(timeout, "[$instance] $eventName") {
            received.firstOrNull { it.instance == instance && it.eventName == eventName && predicate(it) }
        }

    /** Waits until [instance] has emitted at least [count] events named [eventName], returning all of them. */
    fun awaitCount(
        instance: String,
        eventName: String,
        count: Int,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): List<ReceivedEvent> =
        awaitProbe(timeout, "[$instance] ${count}x $eventName") {
            events(instance).filter { it.eventName == eventName }.takeIf { it.size >= count }
        }

    /**
     * Asserts [eventNames] occur on [instance] in this relative order, waiting for the
     * last of them. Unrelated events in between are allowed -- SIP emits plenty.
     */
    fun awaitSequence(
        instance: String,
        vararg eventNames: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ) {
        awaitProbe(timeout, "[$instance] sequence ${eventNames.toList()}") {
            val actual = events(instance).map { it.eventName }
            if (containsInOrder(actual, eventNames.toList())) Unit else null
        }
    }

    /** Fails if [instance] emitted anything after its (last) `call_disconnected`. */
    fun assertNothingAfterDisconnect(instance: String) {
        val instanceEvents = events(instance)
        val lastDisconnect = instanceEvents.indexOfLast { it.eventName == "call_disconnected" }
        if (lastDisconnect < 0) return
        val trailing = instanceEvents.drop(lastDisconnect + 1)
        check(trailing.isEmpty()) { "[$instance] emitted events after call_disconnected: $trailing" }
    }

    private fun <T : Any> awaitProbe(
        timeout: Duration,
        what: String,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            probe()?.let { return it }
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMillis <= 0) {
                throw AssertionError(
                    "Timed out after ${timeout.toMillis()}ms waiting for $what.\nEvents received:\n" +
                        received.joinToString("\n") { "  $it" }.ifEmpty { "  (none)" },
                )
            }
            synchronized(monitor) { monitor.wait(remainingMillis.coerceAtMost(25)) }
        }
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val VERBOSE = System.getProperty("hasip.verbose") == "true"

        /** True when [expected] appears as a (not necessarily contiguous) subsequence of [actual]. */
        fun containsInOrder(
            actual: List<String>,
            expected: List<String>,
        ): Boolean {
            var index = 0
            for (name in actual) {
                if (index < expected.size && name == expected[index]) index++
            }
            return index == expected.size
        }
    }
}

private fun HttpExchange.respondEmptyJson() {
    use {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, 2)
        responseBody.write("{}".toByteArray())
    }
}
