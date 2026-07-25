package io.github.arnonym.audio

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AudioTest {
    @Test
    fun `file name to format`() {
        audioFormatFromFilename("https://localhost:8080/something/file.mp3") shouldBe AudioInputFormat.MP3
        audioFormatFromFilename("http://localhost:8080/something/file.wav") shouldBe AudioInputFormat.WAV
        audioFormatFromFilename("https://localhost:8080/something/file.abc") shouldBe null
    }
}
