package io.github.arnonym.sip

import io.github.arnonym.config.GlobalOptions
import io.github.arnonym.log.log
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.StringVector
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_transport_type_e

data class EndpointConfig(
    val port: Int,
    val logLevel: Int,
    val nameServer: List<String>,
    val globalOptions: GlobalOptions,
)

fun createEndpoint(
    config: EndpointConfig,
    threadCount: Int = 4,
): Endpoint {
    val epConfig = EpConfig()
    epConfig.logConfig.level = config.logLevel.toLong()
    epConfig.uaConfig.threadCnt = threadCount.toLong()
    epConfig.uaConfig.mainThreadOnly = false
    if (config.nameServer.isNotEmpty()) {
        val nameserver = StringVector()
        config.nameServer.forEach { nameserver.add(it) }
        epConfig.uaConfig.nameserver = nameserver
    }
    if (!config.globalOptions.stunServer.isNullOrEmpty()) {
        log(null, "STUN server enabled: ${config.globalOptions.stunServer}")
        epConfig.uaConfig.stunServer.add(config.globalOptions.stunServer)
    }

    val endpoint = Endpoint()
    endpoint.libCreate()
    endpoint.libInit(epConfig)
    val codecs = endpoint.codecEnum2()
    log(null, "Supported audio codecs: ${codecs.joinToString(", ") { it.codecId }}")
    endpoint.audDevManager().setNullDev()

    if (config.globalOptions.enableUdp) {
        log(null, "UDP transport enabled on port ${config.port}")
        val udpConfig = TransportConfig()
        udpConfig.port = config.port.toLong()
        endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, udpConfig)
    }
    if (config.globalOptions.enableTcp) {
        log(null, "TCP transport enabled on port ${config.port}")
        val tcpConfig = TransportConfig()
        tcpConfig.port = config.port.toLong()
        endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpConfig)
    }
    if (config.globalOptions.enableTls) {
        log(null, "TLS transport enabled on port ${config.globalOptions.tlsPort}")
        val tlsConfig = TransportConfig()
        tlsConfig.port = config.globalOptions.tlsPort.toLong()
        endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsConfig)
    }
    endpoint.libStart()
    return endpoint
}
