package io.github.arnonym.ha

import io.github.arnonym.audio.audioFormatFromContentType
import io.github.arnonym.audio.audioFormatFromFilename
import io.github.arnonym.audio.convertAudioStreamToWavFile
import io.github.arnonym.log.log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

data class TtsResult(val fileName: String, val mustBeDeleted: Boolean, val wasSuccessful: Boolean)

class HaClient(private val config: HaConfig) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(WebSockets)
        }

    private val rawHttpClient = HttpClient(CIO)

    fun createAndGetTts(
        message: String,
        language: String,
    ): TtsResult =
        runBlocking {
            val tts = config.ttsConfig
            val payload =
                buildJsonObject {
                    if (tts.voice != null) {
                        putJsonObjectField("options") { put("voice", tts.voice) }
                    }
                    put("message", message)
                    put("language", language)
                    if (tts.engineId != null) put("engine_id", tts.engineId) else put("platform", tts.platform)
                }
            if (tts.debugPrint) {
                log(null, "TTS payload: $payload")
            }
            val createResponse =
                try {
                    httpClient.post(config.ttsUrl()) {
                        contentType(ContentType.Application.Json)
                        headers { config.createHeaders().forEach { (k, v) -> append(k, v) } }
                        setBody(payload)
                    }
                } catch (e: Exception) {
                    log(null, "Error getting tts file: ${e.message}")
                    return@runBlocking TtsResult(errorWavPath(), false, false)
                }
            if (!createResponse.status.isSuccess()) {
                log(null, "Error getting tts file ${createResponse.status} ${createResponse.bodyAsText()}")
                return@runBlocking TtsResult(errorWavPath(), false, false)
            }
            val ttsUrl = createResponse.body<JsonObject>()["url"]?.let { (it as? JsonPrimitive)?.content }
            if (ttsUrl == null) {
                log(null, "Error: tts response did not contain a url")
                return@runBlocking TtsResult(errorWavPath(), false, false)
            }
            log(null, "Getting audio from \"$ttsUrl\"")
            val ttsResponse =
                try {
                    rawHttpClient.get(ttsUrl) {
                        headers { config.createHeaders().forEach { (k, v) -> append(k, v) } }
                    }
                } catch (e: Exception) {
                    log(null, "Error getting tts audio: ${e.message}")
                    return@runBlocking TtsResult(errorWavPath(), false, false)
                }
            if (!ttsResponse.status.isSuccess()) {
                val bodySnippet = ttsResponse.bodyAsText().take(300)
                log(
                    null,
                    "Error getting tts audio: HTTP ${ttsResponse.status.value} ${ttsResponse.status.description} — $bodySnippet",
                )
                return@runBlocking TtsResult(errorWavPath(), false, false)
            }
            val contentType = ttsResponse.headers["Content-Type"] ?: ""
            val fileFormat = audioFormatFromContentType(contentType) ?: audioFormatFromFilename(ttsUrl)
            if (fileFormat == null) {
                log(null, "Error getting audio format from content-type \"$contentType\" or filename: $ttsUrl")
                return@runBlocking TtsResult(errorWavPath(), false, false)
            }
            val audioBytes = ttsResponse.body<ByteArray>()
            if (audioBytes.isEmpty()) {
                log(null, "Error getting tts audio: empty response body")
                return@runBlocking TtsResult(errorWavPath(), false, false)
            }
            val wavFileName = convertAudioStreamToWavFile(audioBytes, fileFormat)
            if (wavFileName == null) {
                log(null, "Error converting to wav (content-type: $contentType, format: $fileFormat, ${audioBytes.size} bytes)")
                return@runBlocking TtsResult(errorWavPath(), false, false)
            }
            TtsResult(wavFileName, true, true)
        }

    fun renderTemplate(text: String): String =
        runBlocking {
            log(null, "Rendering template: $text")
            val response =
                try {
                    httpClient.post(config.templateUrl()) {
                        contentType(ContentType.Application.Json)
                        headers { config.createHeaders().forEach { (k, v) -> append(k, v) } }
                        setBody(buildJsonObject { put("template", text) })
                    }
                } catch (e: Exception) {
                    log(null, "Error rendering template: ${e.message}")
                    return@runBlocking text
                }
            log(null, "Template response ${response.status} ${response.bodyAsText()}")
            if (response.status.isSuccess()) response.bodyAsText() else text
        }

    fun callService(
        domain: String,
        service: String,
        entityId: String?,
        serviceData: JsonObject?,
    ) {
        runBlocking {
            val payload =
                buildJsonObject {
                    if (entityId != null) put("entity_id", entityId)
                    serviceData?.forEach { (key, value) -> put(key, value) }
                }
            val response =
                try {
                    httpClient.post(config.serviceUrl(domain, service)) {
                        contentType(ContentType.Application.Json)
                        headers { config.createHeaders().forEach { (k, v) -> append(k, v) } }
                        setBody(payload)
                    }
                } catch (e: Exception) {
                    log(null, "Error calling home-assistant service: ${e.message}")
                    return@runBlocking
                }
            log(null, "Service response ${response.status} ${response.bodyAsText()}")
        }
    }

    fun triggerWebhook(
        event: JsonObject,
        overwriteWebhookId: String? = null,
    ) {
        val webhookId = overwriteWebhookId ?: config.webhookId
        if (webhookId.isEmpty()) {
            log(null, "Warning: No webhook defined.")
            return
        }
        log(null, "Calling webhook $webhookId with data $event")
        runBlocking {
            val response =
                try {
                    httpClient.post(config.webhookUrl(webhookId)) {
                        contentType(ContentType.Application.Json)
                        headers { config.createHeaders().forEach { (k, v) -> append(k, v) } }
                        setBody(event)
                    }
                } catch (e: Exception) {
                    log(null, "Error calling webhook: ${e.message}")
                    return@runBlocking
                }
            log(null, "Webhook response ${response.status} ${response.bodyAsText()}")
        }
    }

    fun updateSensorState(
        entityId: String,
        state: String,
        attributes: JsonObject,
    ) {
        runBlocking {
            val payload =
                buildJsonObject {
                    put("state", state)
                    put("attributes", attributes)
                }
            val response =
                try {
                    httpClient.post("${config.baseUrl}/states/$entityId") {
                        contentType(ContentType.Application.Json)
                        headers { config.createHeaders().forEach { (k, v) -> append(k, v) } }
                        setBody(payload)
                    }
                } catch (e: Exception) {
                    log(null, "Error updating sensor $entityId: ${e.message}")
                    return@runBlocking
                }
            if (response.status.isSuccess()) {
                log(null, "Sensor update $entityId: ${response.status}")
            } else {
                log(null, "Sensor update $entityId failed: ${response.status} ${response.bodyAsText()}")
            }
        }
    }

    fun printTtsProviders() =
        runBlocking {
            val wsUrl = config.websocketUrl
            log(null, "Connecting to websocket under URL '$wsUrl'")
            try {
                httpClient.webSocket(urlString = wsUrl) {
                    // Receive auth challenge (ignored, we just need to ack it exists)
                    incoming.receive()
                    // Authenticate
                    outgoing.send(
                        Frame.Text(
                            buildJsonObject {
                                put("type", "auth")
                                put("access_token", config.token)
                            }.toString(),
                        ),
                    )
                    val authResponse = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()) as? JsonObject
                    if (authResponse?.get("type").strOrNull() != "auth_ok") {
                        log(null, "TTS debug: authentication failed: $authResponse")
                        return@webSocket
                    }
                    // Request provider list
                    outgoing.send(
                        Frame.Text(
                            buildJsonObject {
                                put("id", 1)
                                put("type", "tts/engine/list")
                            }.toString(),
                        ),
                    )
                    val listResponse = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()) as? JsonObject
                    if (listResponse?.get("success").boolOrNull() != true) {
                        log(null, "TTS debug: tts/engine/list request failed: $listResponse")
                        return@webSocket
                    }
                    val providers =
                        (listResponse?.get("result") as? JsonObject)
                            ?.get("providers") as? JsonArray
                            ?: run {
                                log(null, "TTS debug: unexpected response shape: $listResponse")
                                return@webSocket
                            }
                    log(null, "Available TTS providers:")
                    for (provider in providers) {
                        val obj = provider as? JsonObject ?: continue
                        val engineId = obj["engine_id"].strOrNull() ?: continue
                        val langs =
                            (obj["supported_languages"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                        log(null, "  $engineId:")
                        log(null, "    Languages:")
                        langs.chunked(10).forEach { chunk -> log(null, "      ${chunk.joinToString(", ")}") }
                    }
                    // Check configured engine
                    val tts = config.ttsConfig
                    val engineFromConfig = tts.engineId ?: tts.platform
                    val providerForConfig =
                        providers.mapNotNull { it as? JsonObject }.firstOrNull {
                            it["engine_id"].strOrNull() == engineFromConfig
                        }
                    if (providerForConfig == null) {
                        log(null, "  Warning: No TTS provider found for engine $engineFromConfig")
                        return@webSocket
                    }
                    val supportedLangs =
                        (providerForConfig["supported_languages"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    if (tts.language !in supportedLangs) {
                        log(null, "  Warning: Language ${tts.language} not supported by TTS provider $engineFromConfig")
                        return@webSocket
                    }
                    log(null, "  Good news: TTS provider $engineFromConfig was found and supports language ${tts.language}")
                    // Request voices for engine + language
                    outgoing.send(
                        Frame.Text(
                            buildJsonObject {
                                put("id", 2)
                                put("type", "tts/engine/voices")
                                put("engine_id", engineFromConfig)
                                put("language", tts.language)
                            }.toString(),
                        ),
                    )
                    val voicesResponse = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()) as? JsonObject
                    if (voicesResponse?.get("success").boolOrNull() != true) {
                        log(null, "TTS debug: tts/engine/voices request failed: $voicesResponse")
                        return@webSocket
                    }
                    val voices =
                        (voicesResponse?.get("result") as? JsonObject)
                            ?.get("voices") as? JsonArray
                    if (!voices.isNullOrEmpty()) {
                        log(null, "  Voices for current engine and language:")
                        for (voice in voices) {
                            val v = voice as? JsonObject ?: continue
                            val voiceId = v["voice_id"].strOrNull() ?: ""
                            val name = v["name"].strOrNull() ?: ""
                            log(null, "      $voiceId: $name")
                        }
                    } else {
                        log(null, "  Current engine doesn't support voices")
                    }
                }
            } catch (e: Exception) {
                log(null, "TTS debug: websocket error: ${e.message}")
            }
        }

    private fun JsonElement?.strOrNull(): String? = (this as? JsonPrimitive)?.content

    private fun JsonElement?.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    override fun close() {
        httpClient.close()
        rawHttpClient.close()
    }

    companion object {
        @Volatile
        private var cachedErrorWavPath: String? = null

        @Synchronized
        fun errorWavPath(): String {
            cachedErrorWavPath?.let { return it }
            val resource =
                HaClient::class.java.getResourceAsStream("/sound/error.wav")
                    ?: throw IllegalStateException("Bundled sound/error.wav resource not found")
            val tempFile = Files.createTempFile("hasip-error-", ".wav").toFile()
            resource.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            cachedErrorWavPath = tempFile.absolutePath
            return tempFile.absolutePath
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObjectField(
    key: String,
    build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    put(key, buildJsonObject(build))
}
