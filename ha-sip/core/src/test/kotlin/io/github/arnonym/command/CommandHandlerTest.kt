package io.github.arnonym.command

import io.github.arnonym.event.WebhookToCall
import io.github.arnonym.ha.HaClient
import io.github.arnonym.ha.HaConfig
import io.github.arnonym.ha.TtsConfig
import io.github.arnonym.menu.PostAction
import io.github.arnonym.state.CallRegistry
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class CommandHandlerTest {
    private class FakeCall(override val callbackId: String) : CallHandle {
        var hungUpWithCode: Int? = null
        var answeredMenu: JsonObject? = null
        var transferredTo: String? = null
        var bridgedWith: CallHandle? = null
        var dtmfSent: Pair<String, DtmfMethod>? = null
        var playedAudioFile: String? = null
        var playedMessage: String? = null
        var stoppedPlayback = false
        var startedRecording: String? = null
        var stoppedRecording = false
        var lastScheduledPostAction: PostAction? = null

        override fun hangupCall(sipCode: Int) {
            hungUpWithCode = sipCode
        }

        override fun answerCall(
            newMenu: JsonObject?,
            overwriteWebhooks: WebhookToCall?,
        ) {
            answeredMenu = newMenu
        }

        override fun transfer(transferTo: String) {
            transferredTo = transferTo
        }

        override fun bridgeAudio(other: CallHandle) {
            bridgedWith = other
        }

        override fun sendDtmf(
            digits: String,
            method: DtmfMethod,
        ) {
            dtmfSent = digits to method
        }

        override fun playAudioFile(
            audioFile: String,
            cacheAudio: Boolean,
            waitForAudioToFinish: Boolean,
        ) {
            playedAudioFile = audioFile
        }

        override fun playMessage(
            message: String,
            language: String,
            cacheAudio: Boolean,
            waitForAudioToFinish: Boolean,
        ) {
            playedMessage = message
        }

        override fun stopPlayback() {
            stoppedPlayback = true
        }

        override fun startRecording(recordingFile: String) {
            startedRecording = recordingFile
        }

        override fun stopRecording() {
            stoppedRecording = true
        }

        override fun setScheduledPostAction(action: PostAction) {
            lastScheduledPostAction = action
        }
    }

    private fun newHandler(
        registry: CallRegistry<FakeCall> = CallRegistry(),
        dial: (Int, String, JsonObject?, Double, WebhookToCall?) -> Unit = { _, _, _, _, _ -> },
        quit: () -> Unit = {},
    ): CommandHandler<FakeCall> {
        val haConfig =
            HaConfig(
                baseUrl = "http://example.invalid",
                websocketUrl = "ws://example.invalid",
                token = "token",
                ttsConfig = TtsConfig(platform = null, engineId = "cloud", language = "en", voice = null, debugPrint = false),
                webhookId = "",
                cacheDir = null,
            )
        return CommandHandler(registry, HaClient(haConfig), "en", dial, quit)
    }

    private fun command(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

    @Test
    fun `dial command invokes dial callback with defaults`() {
        var invoked: List<Any?>? = null
        val handler =
            newHandler(dial = { acc, number, menu, ringTimeout, webhooks ->
                invoked = listOf(acc, number, menu, ringTimeout, webhooks)
            })
        handler.handleCommand(
            command {
                put("command", "dial")
                put("number", "sip:bob@example.com")
            },
            null,
        )
        val result = requireNotNull(invoked)
        result[0] shouldBe -1
        result[1] shouldBe "sip:bob@example.com"
        result[3] shouldBe 300.0
    }

    @Test
    fun `dial ignored when number already active`() {
        var invokedCount = 0
        val registry = CallRegistry<FakeCall>()
        registry.registerCall("sip:bob@example.com", FakeCall("sip:bob@example.com"), emptyList())
        val handler = newHandler(registry = registry, dial = { _, _, _, _, _ -> invokedCount++ })
        handler.handleCommand(
            command {
                put("command", "dial")
                put("number", "sip:bob@example.com")
            },
            null,
        )
        invokedCount shouldBe 0
    }

    @Test
    fun `hangup dispatches to the registered call`() {
        val registry = CallRegistry<FakeCall>()
        val call = FakeCall("123")
        registry.registerCall("123", call, emptyList())
        val handler = newHandler(registry = registry)
        handler.handleCommand(
            command {
                put("command", "hangup")
                put("number", "123")
                put("sip_code", 486)
            },
            null,
        )
        call.hungUpWithCode shouldBe 486
    }

    @Test
    fun `hangup on unknown number logs and does not throw`() {
        val handler = newHandler()
        handler.handleCommand(
            command {
                put("command", "hangup")
                put("number", "unknown")
            },
            null,
        )
    }

    @Test
    fun `transfer dispatches transfer_to`() {
        val registry = CallRegistry<FakeCall>()
        val call = FakeCall("123")
        registry.registerCall("123", call, emptyList())
        val handler = newHandler(registry = registry)
        handler.handleCommand(
            command {
                put("command", "transfer")
                put("number", "123")
                put("transfer_to", "sip:alice@example.com")
            },
            null,
        )
        call.transferredTo shouldBe "sip:alice@example.com"
    }

    @Test
    fun `bridge_audio with self resolves from-call`() {
        val registry = CallRegistry<FakeCall>()
        val other = FakeCall("other")
        registry.registerCall("other", other, emptyList())
        val fromCall = FakeCall("self-call")
        val handler = newHandler(registry = registry)
        handler.handleCommand(
            command {
                put("command", "bridge_audio")
                put("number", "self")
                put("bridge_to", "other")
            },
            fromCall,
        )
        fromCall.bridgedWith shouldBe other
    }

    @Test
    fun `send_dtmf rejects invalid method`() {
        val registry = CallRegistry<FakeCall>()
        val call = FakeCall("123")
        registry.registerCall("123", call, emptyList())
        val handler = newHandler(registry = registry)
        handler.handleCommand(
            command {
                put("command", "send_dtmf")
                put("number", "123")
                put("digits", "123")
                put("method", "invalid")
            },
            null,
        )
        call.dtmfSent shouldBe null
    }

    @Test
    fun `send_dtmf defaults to in_band`() {
        val registry = CallRegistry<FakeCall>()
        val call = FakeCall("123")
        registry.registerCall("123", call, emptyList())
        val handler = newHandler(registry = registry)
        handler.handleCommand(
            command {
                put("command", "send_dtmf")
                put("number", "123")
                put("digits", "159")
            },
            null,
        )
        call.dtmfSent shouldBe ("159" to DtmfMethod.IN_BAND)
    }

    @Test
    fun `play_audio_file with post_action hangup schedules hangup`() {
        val registry = CallRegistry<FakeCall>()
        val call = FakeCall("123")
        registry.registerCall("123", call, emptyList())
        val handler = newHandler(registry = registry)
        handler.handleCommand(
            command {
                put("command", "play_audio_file")
                put("number", "123")
                put("audio_file", "/tmp/test.wav")
                put("post_action", "hangup")
            },
            null,
        )
        call.playedAudioFile shouldBe "/tmp/test.wav"
        call.lastScheduledPostAction shouldBe PostAction.Hangup
    }

    @Test
    fun `state and quit do not throw`() {
        var quitCalled = false
        val handler = newHandler(quit = { quitCalled = true })
        handler.handleCommand(command { put("command", "state") }, null)
        handler.handleCommand(command { put("command", "quit") }, null)
        quitCalled shouldBe true
    }

    @Test
    fun `unknown command is logged and ignored`() {
        val handler = newHandler()
        handler.handleCommand(command { put("command", "not_a_real_command") }, null)
    }
}
