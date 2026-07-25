package io.github.arnonym.event

sealed class CurrentPlayback {
    data class Message(val message: String) : CurrentPlayback()

    data class AudioFile(val audioFile: String) : CurrentPlayback()

    fun toPlaybackDoneEvent(): WebhookEvent =
        when (this) {
            is Message -> WebhookEvent.PlaybackDoneMessage(message)
            is AudioFile -> WebhookEvent.PlaybackDoneAudioFile(audioFile)
        }
}
