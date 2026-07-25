package io.github.arnonym.sip

import io.github.arnonym.audio.AudioCache
import io.github.arnonym.audio.CacheType
import io.github.arnonym.audio.audioFormatFromFilename
import io.github.arnonym.audio.convertAudioStreamToWavFile
import io.github.arnonym.command.CallHandle
import io.github.arnonym.command.CommandHandler
import io.github.arnonym.command.DtmfMethod
import io.github.arnonym.config.Constants
import io.github.arnonym.event.CallDirection
import io.github.arnonym.event.CallInfo
import io.github.arnonym.event.CurrentPlayback
import io.github.arnonym.event.EventSender
import io.github.arnonym.event.WebhookEvent
import io.github.arnonym.event.WebhookToCall
import io.github.arnonym.event.triggerWebhook
import io.github.arnonym.ha.HaClient
import io.github.arnonym.ha.HaConfig
import io.github.arnonym.log.log
import io.github.arnonym.menu.Menu
import io.github.arnonym.menu.PostAction
import io.github.arnonym.menu.normalizeMenu
import io.github.arnonym.menu.prettyPrintMenu
import kotlinx.serialization.json.JsonObject
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AudioMediaRecorder
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.CallSendDtmfParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallReplaceRequestParam
import org.pjsip.pjsua2.OnCallReplacedParam
import org.pjsip.pjsua2.OnCallRxOfferParam
import org.pjsip.pjsua2.OnCallRxReinviteParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.OnCallTransferRequestParam
import org.pjsip.pjsua2.OnCallTransferStatusParam
import org.pjsip.pjsua2.OnCallTxOfferParam
import org.pjsip.pjsua2.OnDtmfDigitParam
import org.pjsip.pjsua2.ToneGenerator
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsua_call_media_status
import org.pjsip.pjsua2.pjsua_dtmf_method
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.pjsip.pjsua2.Call as PjCall

/** Direct port of call.py's top-level `make_call`. */
fun makeCall(
    endpoint: Endpoint,
    account: Account,
    uriToCall: String,
    menu: JsonObject?,
    commandHandler: CommandHandler<Call>,
    eventSender: EventSender,
    haConfig: HaConfig,
    haClient: HaClient,
    ringTimeout: Double,
    webhooks: WebhookToCall?,
): Call {
    val newCall =
        Call(
            endpoint, account, PJSUA_INVALID_ID, uriToCall, menu, commandHandler, eventSender, haConfig, haClient,
            ringTimeout, webhooks, emptyMap(),
        )
    val callParam = CallOpParam(true)
    newCall.makeCall(uriToCall, callParam)
    newCall.triggerWebhookEvent(WebhookEvent.OutgoingCallInitiated)
    return newCall
}

const val PJSUA_INVALID_ID = -1

class Call(
    private val endpoint: Endpoint,
    val account: Account,
    callId: Int,
    private val uriToCall: String?,
    initialMenu: JsonObject?,
    private val commandHandler: CommandHandler<Call>,
    private val eventSender: EventSender,
    val haConfig: HaConfig,
    private val haClient: HaClient,
    private val ringTimeout: Double,
    private var webhooks: WebhookToCall?,
    private var sipHeaders: Map<String, String?> = emptyMap(),
) : PjCall(account, callId), CallHandle {
    private val lock = ReentrantLock()

    val direction: CallDirection = if (uriToCall != null) CallDirection.OUTGOING else CallDirection.INCOMING

    private var player: Player? = null
    var audioMedia: AudioMedia? = null
        private set
    private var recorder: AudioMediaRecorder? = null
    private var toneGen: ToneGenerator? = null

    private var recordingFile: String? = null
    private var requestedRecordingFilename: String? = null
    private var connected = false
    private var currentInput = ""
    private var scheduledPostAction: PostAction? = null
    private var playbackIsDone = true
    private var waitForAudioToFinish = false
    private var lastSeenMillis = System.currentTimeMillis()
    private var callSettledAtMillis: Long? = null
    private var answerAtMillis: Long? = null
    private var callInfo: CallInfo? = null
    private val pressedDigitList = mutableListOf<String>()
    private var currentPlayback: CurrentPlayback? = null

    private var menu: Menu?
    private var menuMap: Map<String, Menu>

    override val callbackId: String

    init {
        val (normalizedMenu, map) = normalizeMenu(initialMenu, haConfig.ttsConfig.language, account.config.index)
        menu = normalizedMenu
        menuMap = map
        prettyPrintMenu(menu)
        val (id, otherIds) = getCallbackIds()
        callbackId = id
        log(account.config.index, "Registering call with id $callbackId")
        commandHandler.registerCall(callbackId, this, otherIds)
    }

    /** Periodic housekeeping tick -- called for every live call from the main loop. */
    fun handleEvents(): Unit =
        lock.withLock {
            val now = System.currentTimeMillis()
            if (!connected && now - lastSeenMillis > (ringTimeout * 1000).toLong()) {
                triggerWebhookEvent(WebhookEvent.RingTimeout)
                log(account.config.index, "Ring timeout of $ringTimeout triggered")
                hangupCall()
                return
            }
            val answerAt = answerAtMillis
            if (!connected && answerAt != null && answerAt < now) {
                log(account.config.index, "Call will be answered now.")
                answerAtMillis = null
                val callParam = CallOpParam()
                callParam.statusCode = 200
                answer(callParam)
                return
            }
            val settledAt = callSettledAtMillis
            if (!connected && settledAt != null && settledAt < now) {
                callSettledAtMillis = null
                handleConnectedState()
                return
            }
            if (!connected) return
            val timeoutSeconds = menu?.timeout ?: Constants.DEFAULT_RING_TIMEOUT
            if (now - lastSeenMillis > (timeoutSeconds * 1000).toLong()) {
                log(account.config.index, "Timeout of $timeoutSeconds triggered")
                menu?.let {
                    handleMenu(it.timeoutChoice)
                    triggerWebhookEvent(WebhookEvent.Timeout(it.id))
                }
                return
            }
            if (playbackIsDone && scheduledPostAction != null) {
                val postAction = scheduledPostAction!!
                scheduledPostAction = null
                handlePostAction(postAction)
                return
            }
            if (pressedDigitList.isNotEmpty()) {
                val nextDigit = pressedDigitList.removeAt(0)
                handleDtmfDigit(nextDigit)
                return
            }
        }

    private fun handlePostAction(postAction: PostAction) {
        log(account.config.index, "Scheduled post action: ${postAction.action}")
        when (postAction) {
            is PostAction.Noop -> {}
            is PostAction.Return -> {
                var m = menu
                if (m == null) {
                    log(account.config.index, "No menu to return to")
                    return
                }
                repeat(postAction.level) { m = m?.parentMenu }
                if (m != null) handleMenu(m) else log(account.config.index, "Could not return ${postAction.level} level in current menu")
            }
            is PostAction.Jump -> {
                val newMenu = menuMap[postAction.menuId]
                if (newMenu != null) {
                    handleMenu(newMenu)
                } else {
                    log(account.config.index, "Could not find menu_id: \"${postAction.menuId}\". Valid IDs are ${menuMap.keys}")
                }
            }
            is PostAction.Hangup -> hangupCall()
            is PostAction.RepeatMessage -> handleMenu(menu, sendWebhookEvent = false, handleAction = false, resetInput = false)
        }
    }

    fun triggerWebhookEvent(event: WebhookEvent) {
        triggerWebhook(event, callInfo, account.config.index, callbackId, eventSender, webhooks)
    }

    private fun handleConnectedState() {
        log(account.config.index, "Call is established.")
        connected = true
        resetTimeout()
        triggerWebhookEvent(WebhookEvent.CallEstablished)
        handleMenu(menu)
    }

    override fun onCallState(prm: OnCallStateParam): Unit =
        lock.withLock {
            if (callInfo == null) callInfo = getCallInfo()
            val ci = info
            when (ci.state) {
                pjsip_inv_state.PJSIP_INV_STATE_EARLY -> log(account.config.index, "Early")
                pjsip_inv_state.PJSIP_INV_STATE_CALLING -> log(account.config.index, "Calling")
                pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> log(account.config.index, "Call connecting...")
                pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> {
                    log(account.config.index, "Call connected")
                    extractHeadersFromResponse(prm)
                    callSettledAtMillis = System.currentTimeMillis() + (account.config.settleTime * 1000).toLong()
                }
                pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                    log(account.config.index, "Call disconnected (${ci.lastStatusCode} ${ci.lastReason})")
                    stopRecording()
                    triggerWebhookEvent(WebhookEvent.CallDisconnected)
                    connected = false
                    callSettledAtMillis = null
                    currentInput = ""
                    player = null
                    audioMedia = null
                    toneGen = null
                    commandHandler.forgetCall(callbackId)
                }
                else -> log(account.config.index, "Unknown state: ${ci.state}")
            }
        }

    override fun onCallMediaState(prm: OnCallMediaStateParam): Unit =
        lock.withLock {
            val ci = info
            log(account.config.index, "onCallMediaState call info state ${ci.state}")
            ci.media.forEachIndexed { mediaIndex, media ->
                if (media.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    (
                        media.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE ||
                            media.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD
                    )
                ) {
                    log(account.config.index, "Connected media ${media.status}")
                    audioMedia = getAudioMedia(mediaIndex)
                    val requested = requestedRecordingFilename
                    if (requested != null && recorder == null) {
                        startRecording(requested)
                    }
                }
            }
        }

    override fun onDtmfDigit(prm: OnDtmfDigitParam): Unit =
        lock.withLock {
            if (!playbackIsDone && waitForAudioToFinish) {
                resetTimeout()
                return
            }
            stopPlayback()
            resetTimeout()
            pressedDigitList.add(prm.digit)
        }

    private fun handleDtmfDigit(pressedDigit: String) {
        log(account.config.index, "onDtmfDigit: digit $pressedDigit")
        triggerWebhookEvent(WebhookEvent.DtmfDigit(pressedDigit))
        val currentMenu = menu ?: return
        currentInput += pressedDigit
        log(account.config.index, "Current input: $currentInput")
        val choices = currentMenu.choices
        if (currentInput in choices) {
            handleMenu(choices.getValue(currentInput))
            return
        }
        if (currentMenu.choicesArePin) {
            val maxChoiceLength = choices.keys.maxOfOrNull { it.length } ?: 0
            if (currentInput.length == maxChoiceLength) {
                log(account.config.index, "No PIN matched $currentInput")
                handleMenu(currentMenu.defaultChoice)
            }
        } else {
            val stillValid = choices.keys.any { it.startsWith(currentInput) }
            if (!stillValid) {
                log(account.config.index, "Invalid input $currentInput")
                handleMenu(currentMenu.defaultChoice)
            }
        }
    }

    override fun onCallTransferRequest(prm: OnCallTransferRequestParam) {
        log(account.config.index, "onCallTransferRequest")
    }

    override fun onCallTransferStatus(prm: OnCallTransferStatusParam) {
        log(account.config.index, "onCallTransferStatus. Status code: ${prm.statusCode} (${prm.reason})")
    }

    override fun onCallReplaceRequest(prm: OnCallReplaceRequestParam) {
        log(account.config.index, "onCallReplaceRequest")
    }

    override fun onCallReplaced(prm: OnCallReplacedParam) {
        log(account.config.index, "onCallReplaced")
    }

    override fun onCallRxOffer(prm: OnCallRxOfferParam) {
        log(account.config.index, "onCallRxOffer")
    }

    override fun onCallRxReinvite(prm: OnCallRxReinviteParam) {
        log(account.config.index, "onCallRxReinvite")
    }

    override fun onCallTxOffer(prm: OnCallTxOfferParam) {
        log(account.config.index, "onCallTxOffer")
    }

    private fun handleMenu(
        menu: Menu?,
        sendWebhookEvent: Boolean = true,
        handleAction: Boolean = true,
        resetInput: Boolean = true,
    ) {
        resetTimeout()
        if (menu == null) {
            log(account.config.index, "No menu supplied")
            return
        }
        this.menu = menu
        val menuId = menu.id
        if (menuId != null && sendWebhookEvent) {
            triggerWebhookEvent(WebhookEvent.EnteredMenu(menuId))
        }
        if (resetInput) currentInput = ""
        var message = menu.message
        val handleAsTemplate = menu.handleAsTemplate
        val audioFile = menu.audioFile
        val language = menu.language
        val action = menu.action
        val postAction = menu.postAction
        val shouldCache = menu.cacheAudio
        val waitForAudioToFinish = menu.waitForAudioToFinish
        if (!message.isNullOrEmpty()) {
            if (handleAsTemplate) message = haClient.renderTemplate(message)
            playMessage(message, language, shouldCache, waitForAudioToFinish)
        }
        if (!audioFile.isNullOrEmpty()) {
            playAudioFile(audioFile, shouldCache, waitForAudioToFinish)
        }
        if (handleAction) handleAction(action)
        scheduledPostAction = postAction
    }

    private fun handleAction(action: JsonObject?) {
        if (action == null) {
            log(account.config.index, "No action supplied")
            return
        }
        commandHandler.handleCommand(action, this)
    }

    override fun playMessage(
        message: String,
        language: String,
        cacheAudio: Boolean,
        waitForAudioToFinish: Boolean,
    ): Unit =
        lock.withLock {
            log(account.config.index, "Playing message: $message")
            val cachedFile = AudioCache.getCachedFile(cacheAudio, haConfig.cacheDir, CacheType.MESSAGE, message)
            if (cachedFile != null) {
                setCurrentPlayback(CurrentPlayback.Message(message))
                playWavFile(cachedFile, false, waitForAudioToFinish)
                return
            }
            val ttsResult = haClient.createAndGetTts(message, language)
            setCurrentPlayback(CurrentPlayback.Message(message))
            AudioCache.cacheFile(cacheAudio && ttsResult.wasSuccessful, haConfig.cacheDir, CacheType.MESSAGE, message, ttsResult.fileName)
            playWavFile(ttsResult.fileName, ttsResult.mustBeDeleted, waitForAudioToFinish)
        }

    override fun playAudioFile(
        audioFile: String,
        cacheAudio: Boolean,
        waitForAudioToFinish: Boolean,
    ): Unit =
        lock.withLock {
            log(account.config.index, "Playing audio file: $audioFile")
            val cachedFile = AudioCache.getCachedFile(cacheAudio, haConfig.cacheDir, CacheType.AUDIO_FILE, audioFile)
            if (cachedFile != null) {
                setCurrentPlayback(CurrentPlayback.AudioFile(audioFile))
                playWavFile(cachedFile, false, waitForAudioToFinish)
                return
            }
            val fileFormat = audioFormatFromFilename(audioFile)
            if (fileFormat == null) {
                log(null, "Error getting audio format from filename: $audioFile")
                return
            }
            val audioFileContent = File(audioFile).readBytes()
            val soundFileName = convertAudioStreamToWavFile(audioFileContent, fileFormat)
            if (soundFileName == null) {
                log(null, "Could not convert to wav: $audioFile")
                return
            }
            setCurrentPlayback(CurrentPlayback.AudioFile(audioFile))
            AudioCache.cacheFile(cacheAudio, haConfig.cacheDir, CacheType.AUDIO_FILE, audioFile, soundFileName)
            playWavFile(soundFileName, true, waitForAudioToFinish)
        }

    private fun playWavFile(
        soundFileName: String,
        mustBeDeleted: Boolean,
        waitForAudioToFinish: Boolean,
    ) {
        val media = audioMedia
        if (media != null) {
            playbackIsDone = false
            this.waitForAudioToFinish = waitForAudioToFinish
            val newPlayer = Player(::onPlaybackDone)
            player = newPlayer
            newPlayer.playFile(media, soundFileName)
        } else {
            log(account.config.index, "Audio media not connected. Cannot play audio stream!")
        }
        if (mustBeDeleted) File(soundFileName).delete()
    }

    private fun onPlaybackDone(): Unit =
        lock.withLock {
            log(account.config.index, "Playback done.")
            currentPlayback?.let { triggerWebhookEvent(it.toPlaybackDoneEvent()) }
            currentPlayback = null
            playbackIsDone = true
            player = null
        }

    override fun stopPlayback(): Unit =
        lock.withLock {
            if (!playbackIsDone) {
                log(account.config.index, "Playback interrupted.")
                player?.let { p -> audioMedia?.let { p.stopTransmit(it) } }
                player = null
                playbackIsDone = true
            }
        }

    override fun startRecording(recordingFile: String): Unit =
        lock.withLock {
            val existingRecorder = recorder
            if (existingRecorder != null) {
                log(account.config.index, "Recording already running -> reattaching")
                audioMedia?.let { media ->
                    try {
                        media.stopTransmit(existingRecorder)
                    } catch (_: Exception) {
                    }
                    try {
                        media.startTransmit(existingRecorder)
                    } catch (e: Exception) {
                        log(account.config.index, "Error: Could not reattach recorder: ${e.message}")
                    }
                }
                return
            }
            val media = audioMedia
            if (media == null) {
                log(account.config.index, "Audio media not connected yet. Recording will start once media is available")
                requestedRecordingFilename = recordingFile
                return
            }
            requestedRecordingFilename = null
            val targetDir = File(recordingFile).parentFile
            if (targetDir == null || !targetDir.isDirectory) {
                log(account.config.index, "Error: Call recordings directory not found: $targetDir")
                return
            }
            val newRecorder = AudioMediaRecorder()
            try {
                newRecorder.createRecorder(recordingFile)
                media.startTransmit(newRecorder)
            } catch (e: Exception) {
                log(account.config.index, "Error: Failed to start call recording: ${e.message}")
                recorder = newRecorder
                stopRecording()
                return
            }
            recorder = newRecorder
            this.recordingFile = recordingFile
            log(account.config.index, "Call recording started: $recordingFile")
            triggerWebhookEvent(WebhookEvent.RecordingStarted(recordingFile))
        }

    override fun stopRecording(): Unit =
        lock.withLock {
            requestedRecordingFilename = null
            val existingRecorder = recorder ?: return
            try {
                audioMedia?.stopTransmit(existingRecorder)
            } catch (e: Exception) {
                log(account.config.index, "Error: Failed to stop call recording: ${e.message}")
            }
            recordingFile?.let { file ->
                log(account.config.index, "Call recording stopped: $file")
                triggerWebhookEvent(WebhookEvent.RecordingStopped(file))
            }
            recorder = null
            recordingFile = null
        }

    fun accept(
        answerMode: io.github.arnonym.config.AnswerMode,
        answerAfter: Double,
    ): Unit =
        lock.withLock {
            if (answerMode == io.github.arnonym.config.AnswerMode.REJECT) {
                val sipCode = account.config.options.rejectSipCode
                log(account.config.index, "Rejecting call with SIP code $sipCode.")
                val callParam = CallOpParam()
                callParam.statusCode = sipCode
                callParam.reason = REASON_PHRASES[sipCode] ?: ""
                answer(callParam)
                return
            }
            val callParam = CallOpParam()
            callParam.statusCode = 180
            answer(callParam)
            if (answerMode == io.github.arnonym.config.AnswerMode.ACCEPT) {
                answerAtMillis = System.currentTimeMillis() + (answerAfter * 1000).toLong()
            }
        }

    override fun hangupCall(sipCode: Int): Unit =
        lock.withLock {
            log(account.config.index, "Hang-up.")
            val callParam = CallOpParam(true)
            if (sipCode != 0 && !connected) {
                callParam.statusCode = sipCode
                callParam.reason = REASON_PHRASES[sipCode] ?: ""
            }
            hangup(callParam)
        }

    override fun answerCall(
        newMenu: JsonObject?,
        overwriteWebhooks: WebhookToCall?,
    ): Unit =
        lock.withLock {
            log(account.config.index, "Trigger answer of call (if not established already)")
            if (newMenu != null) {
                val (normalized, map) = normalizeMenu(newMenu, haConfig.ttsConfig.language, account.config.index)
                menu = normalized
                menuMap = map
                prettyPrintMenu(menu)
            }
            if (overwriteWebhooks != null) webhooks = overwriteWebhooks
            if (connected) {
                if (newMenu != null) handleMenu(menu)
            } else {
                answerAtMillis = System.currentTimeMillis()
            }
        }

    override fun transfer(transferTo: String): Unit =
        lock.withLock {
            log(account.config.index, "Transfer call to $transferTo")
            val xferParam = CallOpParam(true)
            xfer(transferTo, xferParam)
        }

    override fun bridgeAudio(other: CallHandle) {
        require(other is Call) { "bridgeAudio requires another Call instance" }
        val (firstLock, secondLock) = if (callbackId <= other.callbackId) lock to other.lock else other.lock to lock
        if (!firstLock.tryLock(BRIDGE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log(account.config.index, "Could not acquire lock for bridge_audio within timeout; aborting.")
            return
        }
        try {
            if (!secondLock.tryLock(BRIDGE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log(account.config.index, "Could not acquire partner call's lock for bridge_audio within timeout; aborting.")
                return
            }
            try {
                val myMedia = audioMedia
                val otherMedia = other.audioMedia
                if (myMedia != null && otherMedia != null) {
                    log(account.config.index, "Connect audio stream of \"$callbackId\" and \"${other.callbackId}\"")
                    myMedia.startTransmit(otherMedia)
                    otherMedia.startTransmit(myMedia)
                    log(account.config.index, "Audio streams connected.")
                } else {
                    log(account.config.index, "At least one audio media is not connected. Cannot bridge audio between calls!")
                }
            } finally {
                secondLock.unlock()
            }
        } finally {
            firstLock.unlock()
        }
    }

    override fun sendDtmf(
        digits: String,
        method: DtmfMethod,
    ): Unit =
        lock.withLock {
            resetTimeout()
            log(account.config.index, "Sending DTMF $digits")
            when (method) {
                DtmfMethod.IN_BAND -> {
                    val media = audioMedia
                    if (media == null) {
                        log(account.config.index, "Audio media not connected. Cannot send DTMF in-band!")
                        return
                    }
                    var generator = toneGen
                    if (generator == null) {
                        generator = ToneGenerator()
                        generator.createToneGenerator()
                        generator.startTransmit(media)
                        toneGen = generator
                    }
                    generator.playDigits(createToneDigitVector(digits))
                }
                DtmfMethod.RFC2833 -> {
                    val dtmfParam = CallSendDtmfParam()
                    dtmfParam.method = pjsua_dtmf_method.PJSUA_DTMF_METHOD_RFC2833
                    dtmfParam.duration = Constants.DEFAULT_DTMF_ON
                    dtmfParam.digits = digits
                    sendDtmf(dtmfParam)
                }
                DtmfMethod.SIP_INFO -> {
                    val dtmfParam = CallSendDtmfParam()
                    dtmfParam.method = pjsua_dtmf_method.PJSUA_DTMF_METHOD_SIP_INFO
                    dtmfParam.duration = Constants.DEFAULT_DTMF_ON
                    dtmfParam.digits = digits
                    sendDtmf(dtmfParam)
                }
            }
        }

    override fun setScheduledPostAction(action: PostAction): Unit =
        lock.withLock {
            scheduledPostAction = action
        }

    private fun getCallbackIds(): Pair<String, List<String>> {
        val uri = uriToCall
        if (uri != null) {
            val parsedUri = parseSipUri(uri)
            return uri to listOfNotNull(parsedUri)
        }
        val info = getCallInfo()
        return info.remoteUri to listOfNotNull(info.parsedRemoteUri, info.callId)
    }

    fun getCallInfo(): CallInfo =
        lock.withLock {
            val ci = info
            val parsedRemoteUri = parseSipUri(ci.remoteUri)
            val parsedLocalUri = parseSipUri(ci.localUri)
            return CallInfo(
                localUri = ci.localUri,
                remoteUri = ci.remoteUri,
                parsedRemoteUri = parsedRemoteUri,
                parsedLocalUri = parsedLocalUri,
                callId = ci.callIdString,
                headers = sipHeaders,
                direction = direction,
            )
        }

    /** Direct port of call.py's `extract_headers_from_response`. */
    private fun extractHeadersFromResponse(prm: OnCallStateParam) {
        val extractHeaders = account.config.options.extractHeaders
        val debugHeaders = account.config.globalOptions.debugHeaders
        if (extractHeaders.isEmpty() && !debugHeaders) return
        if (sipHeaders.isNotEmpty()) return
        val wholeMsg =
            try {
                prm.e.body.tsxState.src.rdata.wholeMsg
            } catch (e: Exception) {
                null
            } ?: return
        if (debugHeaders) Account.logAllSipHeaders(account.config.index, wholeMsg)
        if (extractHeaders.isNotEmpty()) {
            sipHeaders = Account.parseSipHeaders(wholeMsg, extractHeaders)
            callInfo = callInfo?.copy(headers = sipHeaders)
        }
    }

    private fun resetTimeout() {
        lastSeenMillis = System.currentTimeMillis()
    }

    private fun setCurrentPlayback(playback: CurrentPlayback) {
        currentPlayback = playback
    }

    companion object {
        private const val BRIDGE_LOCK_TIMEOUT_SECONDS = 2L

        fun parseSipUri(sipUri: String): String? {
            val match = Regex("<sip:(.+?)[@;>]").find(sipUri)
            if (match != null) return match.groupValues[1]
            val fallback = Regex("sip:(.+?)($|[@;])").find(sipUri)
            if (fallback != null) return fallback.groupValues[1]
            return null
        }
    }
}
