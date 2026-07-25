package io.github.arnonym.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Port of tests/test_global_options.py. */
class GlobalOptionsTest {
    @Test
    fun `parse transport default`() {
        val options = GlobalOptions.parse("")
        options.enableUdp shouldBe true
        options.enableTcp shouldBe true
        options.enableTls shouldBe false
        options.stunServer shouldBe null
    }

    @Test
    fun `parse transport udp enabled`() {
        GlobalOptions.parse("--udp enabled").enableUdp shouldBe true
    }

    @Test
    fun `parse transport udp disabled`() {
        GlobalOptions.parse("--udp=disabled").enableUdp shouldBe false
    }

    @Test
    fun `parse transport tcp enabled`() {
        GlobalOptions.parse("--tcp=enabled").enableTcp shouldBe true
    }

    @Test
    fun `parse transport tcp disabled`() {
        GlobalOptions.parse("--tcp disabled").enableTcp shouldBe false
    }

    @Test
    fun `parse transport tls enabled`() {
        GlobalOptions.parse("--tls=enabled").enableTls shouldBe true
    }

    @Test
    fun `parse transport tls disabled`() {
        GlobalOptions.parse("--tls=disabled").enableTls shouldBe false
    }

    @Test
    fun `parse stun server`() {
        GlobalOptions.parse("--stun-server stun.example.com").stunServer shouldBe "stun.example.com"
    }

    @Test
    fun `parse debug headers default`() {
        GlobalOptions.parse("").debugHeaders shouldBe false
    }

    @Test
    fun `parse debug headers enabled`() {
        GlobalOptions.parse("--debug-headers enabled").debugHeaders shouldBe true
    }

    @Test
    fun `parse debug headers disabled`() {
        GlobalOptions.parse("--debug-headers disabled").debugHeaders shouldBe false
    }

    @Test
    fun `parse mqtt defaults`() {
        val options = GlobalOptions.parse("")
        options.enableMqtt shouldBe false
        options.mqttAddress shouldBe ""
        options.mqttPort shouldBe 1883
        options.mqttUsername shouldBe ""
        options.mqttPassword shouldBe ""
        options.mqttTopic shouldBe "hasip/execute"
        options.mqttStateTopic shouldBe "hasip/state"
    }

    @Test
    fun `parse mqtt enabled`() {
        GlobalOptions.parse("--enable-mqtt").enableMqtt shouldBe true
    }

    @Test
    fun `parse mqtt full`() {
        val options =
            GlobalOptions.parse(
                "--enable-mqtt --mqtt-address 192.168.1.1 --mqtt-port 1884 " +
                    "--mqtt-username admin --mqtt-password secret " +
                    "--mqtt-topic=custom/execute --mqtt-state-topic custom/state",
            )
        options.enableMqtt shouldBe true
        options.mqttAddress shouldBe "192.168.1.1"
        options.mqttPort shouldBe 1884
        options.mqttUsername shouldBe "admin"
        options.mqttPassword shouldBe "secret"
        options.mqttTopic shouldBe "custom/execute"
        options.mqttStateTopic shouldBe "custom/state"
    }
}
