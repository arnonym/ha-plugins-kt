package io.github.arnonym.log

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Minimal timestamped logger, tagged by SIP account index. Direct port of the
 * original Python `log(account_number, message)` helper (log.py) -- kept
 * intentionally simple (no log levels/frameworks) to preserve the exact log-line
 * shape operators are used to seeing in the Home Assistant add-on log viewer.
 *
 * Note: log line formatting is *not* part of the frozen external API (unlike MQTT
 * topics / webhook payloads / command schema), so this only needs to be
 * "close enough", not byte-identical.
 */
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS")

fun log(
    accountNumber: Int?,
    message: String,
) {
    val timestamp = LocalTime.now().format(timeFormatter)
    val accountTag = accountNumber?.toString() ?: " "
    println("| $timestamp [$accountTag] $message")
}
