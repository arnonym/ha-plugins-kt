package io.github.arnonym

import io.github.arnonym.command.CommandHandler
import io.github.arnonym.config.AppConfig
import io.github.arnonym.config.GlobalOptions
import io.github.arnonym.config.SipOptions
import io.github.arnonym.config.convertToDouble
import io.github.arnonym.config.convertToInt
import io.github.arnonym.config.dotEnvLookup
import io.github.arnonym.event.EventSender
import io.github.arnonym.ha.HaClient
import io.github.arnonym.ha.HaConfig
import io.github.arnonym.ha.TtsConfig
import io.github.arnonym.log.log
import io.github.arnonym.menu.IncomingCallConfig
import io.github.arnonym.mqtt.MqttClient
import io.github.arnonym.sensor.SensorConfig
import io.github.arnonym.sensor.SensorEventHandler
import io.github.arnonym.sensor.SensorUpdater
import io.github.arnonym.sip.Account
import io.github.arnonym.sip.Call
import io.github.arnonym.sip.EndpointConfig
import io.github.arnonym.sip.MyAccountConfig
import io.github.arnonym.sip.createEndpoint
import io.github.arnonym.sip.makeCall
import io.github.arnonym.state.CallRegistry
import io.github.arnonym.yaml.parseYamlToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private fun loadMenuFromFile(
    fileName: String?,
    sipAccountIndex: Int,
): IncomingCallConfig? {
    if (fileName.isNullOrEmpty()) {
        log(sipAccountIndex, "No file name for incoming call config specified.")
        return null
    }
    return try {
        val content = parseYamlToJsonElement(File(fileName).readText()) as? JsonObject
        log(sipAccountIndex, "Loaded menu for incoming call from \"$fileName\".")
        IncomingCallConfig.fromJson(content)
    } catch (e: Exception) {
        log(sipAccountIndex, "Error loading menu for incoming call: ${e.message}")
        null
    }
}

private fun getCacheDir(rawCacheDir: String): String? {
    if (rawCacheDir.isEmpty()) {
        log(null, "No cache directory configured.")
        return null
    }
    if (!File(rawCacheDir).isDirectory) {
        log(null, "Error: Cache directory not found.")
        return null
    }
    log(null, "Found cache directory '$rawCacheDir'")
    return rawCacheDir
}

private fun logHostnameResolution(
    label: String,
    url: String,
) {
    if (url.isEmpty()) return
    val host =
        try {
            URI(url).host
        } catch (e: Exception) {
            null
        }
    if (host == null) {
        log(null, "Error: could not extract hostname from $label: $url")
        return
    }
    try {
        val ip = java.net.InetAddress.getByName(host).hostAddress
        log(null, "$label hostname $host resolves to $ip")
    } catch (e: Exception) {
        log(null, "Error: could not resolve $label hostname $host: ${e.message}")
    }
}

fun main(args: Array<String>) {
    if (args.contains("--help") || args.contains("-h")) {
        GlobalOptions.printHelp()
        println()
        SipOptions.printHelp()
        exitProcess(0)
    }
    val config = AppConfig.fromEnv(dotEnvLookup())

    val globalOptions = GlobalOptions.parse(config.globalOptions)
    val nameServer = config.nameServer.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (nameServer.isNotEmpty()) log(null, "Setting name server: $nameServer")
    val cacheDir = getCacheDir(config.cacheDir)

    val endpointConfig =
        EndpointConfig(
            port = convertToInt(config.port, 5060),
            logLevel = convertToInt(config.logLevel, 5),
            nameServer = nameServer,
            globalOptions = globalOptions,
        )

    val ttsConfig = TtsConfig.from(config.tts)
    logHostnameResolution("HA_BASE_URL", config.ha.baseUrl)
    logHostnameResolution("HA_WEBSOCKET_URL", config.ha.websocketUrl)
    val haConfig =
        HaConfig(
            baseUrl = config.ha.baseUrl,
            websocketUrl = config.ha.websocketUrl,
            token = config.ha.token,
            ttsConfig = ttsConfig,
            webhookId = config.ha.webhookId,
            cacheDir = cacheDir,
        )
    val callRegistry = CallRegistry<Call>()
    val endpoint = createEndpoint(endpointConfig)
    val haClient = HaClient(haConfig)
    val eventSender = EventSender()
    if (haConfig.ttsConfig.debugPrint) {
        haClient.printTtsProviders()
    }
    val accounts = mutableMapOf<Int, Account>()

    // Declared before assignment (as `lateinit`) so the `dial` closure below can
    // capture a reference to it -- CommandHandler needs to be passed into every
    // `Call` it creates via `dial`, but a `Call` also needs a reference back to
    // the very `CommandHandler` that's still being constructed.
    lateinit var commandHandler: CommandHandler<Call>
    commandHandler =
        CommandHandler(
            callRegistry = callRegistry,
            haClient = haClient,
            defaultTtsLanguage = ttsConfig.language,
            dial = { sipAccountNumber, number, menu, ringTimeout, webhooks ->
                val account = accounts[sipAccountNumber] ?: accounts.values.firstOrNull()
                if (account == null) {
                    log(null, "Error: no SIP account available to place call")
                } else {
                    makeCall(endpoint, account, number, menu, commandHandler, eventSender, haConfig, haClient, ringTimeout, webhooks)
                }
            },
            quit = {
                endpoint.libDestroy()
                exitProcess(0)
            },
        )

    val accountConfigs =
        (1..3).associateWith { index ->
            val env = config.sipAccounts.getValue(index)
            MyAccountConfig(
                enabled = env.enabled.equals("true", ignoreCase = true),
                index = index,
                idUri = env.idUri,
                registrarUri = env.registrarUri,
                realm = env.realm,
                userName = env.userName,
                password = env.password,
                mode = io.github.arnonym.config.AnswerMode.getOrElse(env.answerMode, io.github.arnonym.config.AnswerMode.LISTEN),
                settleTime = convertToDouble(env.settleTime, 1.0),
                incomingCallConfig = loadMenuFromFile(env.incomingCallFile, index),
                options = SipOptions.parse(env.options, index),
                globalOptions = globalOptions,
            )
        }
    val enabledAccountIndices = accountConfigs.filterValues { it.enabled }.keys.toList()

    val sensorEntityPrefix = config.sensor.entityPrefix.ifEmpty { "ha_sip" }
    val sensorConfig =
        SensorConfig(
            enabled = config.sensor.enabled.equals("true", ignoreCase = true),
            entityPrefix = sensorEntityPrefix,
        )
    val sensorUpdater = SensorUpdater(haClient, sensorConfig, enabledAccountIndices)

    var isFirstEnabledAccount = true
    for ((index, accountConfig) in accountConfigs) {
        if (!accountConfig.enabled) continue
        val account =
            Account(
                endpoint = endpoint,
                config = accountConfig,
                commandHandler = commandHandler,
                eventSender = eventSender,
                haConfig = haConfig,
                haClient = haClient,
                makeDefault = isFirstEnabledAccount,
                onRegStateCallback = { accountIndex, code, reason ->
                    sensorUpdater.updateRegistrationStatus(accountIndex, code, reason)
                },
            )
        account.init()
        accounts[index] = account
        isFirstEnabledAccount = false
    }

    val mqttClient =
        if (globalOptions.enableMqtt) {
            MqttClient(globalOptions, onCommand = { command -> commandHandler.handleCommand(command, null) }).also { it.connect() }
        } else {
            null
        }

    eventSender.registerSender { event, webhookId -> haClient.triggerWebhook(event, webhookId) }
    eventSender.registerSender { event, _ -> mqttClient?.sendEvent(event) }
    val sensorEventHandler = SensorEventHandler(sensorUpdater)
    eventSender.registerSender { event, _ -> sensorEventHandler.handleEvent(event) }
    sensorUpdater.initializeSensors()

    thread(isDaemon = true, name = "stdin-command-reader") {
        System.`in`.bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val parsed = Json.parseToJsonElement(line) as? JsonObject
                if (parsed != null) commandHandler.handleCommand(parsed, null) else println("Could not deserialize JSON: $line")
            } catch (e: Exception) {
                println("Could not deserialize JSON: $line")
            }
        }
    }

    val eventsExecutor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "call-events-ticker") }
    eventsExecutor.scheduleWithFixedDelay({
        callRegistry.currentCalls().forEach { call ->
            try {
                call.handleEvents()
            } catch (e: Exception) {
                log(null, "Error handling call events: ${e.message}")
            }
        }
    }, 0, 10, TimeUnit.MILLISECONDS)
}
