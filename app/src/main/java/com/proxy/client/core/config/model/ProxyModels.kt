package com.proxy.client.core.config.model

sealed class SecurityConfig {
    object None : SecurityConfig()
    data class Tls(
        val serverName: String? = null,
        val alpn: List<String> = listOf("h2", "http/1.1"),
        val allowInsecure: Boolean = false
    ) : SecurityConfig()
    data class Reality(
        val serverName: String,
        val publicKey: String,
        val shortId: String = "",
        val spiderX: String = ""
    ) : SecurityConfig()
}

sealed class TransportConfig {
    object Tcp : TransportConfig()
    data class WebSocket(
        val path: String = "/",
        val headers: Map<String, String> = emptyMap(),
        val maxEarlyData: Int = 0,
        val earlyDataHeaderName: String = "Sec-WebSocket-Protocol"
    ) : TransportConfig()
    data class Grpc(
        val serviceName: String,
        val idleTimeout: String = "15s",
        val pingTimeout: String = "15s"
    ) : TransportConfig()
    data class HttpUpgrade(
        val host: String? = null,
        val path: String = "/"
    ) : TransportConfig()
}

sealed class ProxyNodeConfig(
    open val tag: String,
    open val server: String,
    open val serverPort: Int,
    open val protocolType: String
) {
    data class Vless(
        val nodeTag: String,
        val serverHost: String,
        val port: Int,
        val uuid: String,
        val flow: String? = null,
        val security: SecurityConfig = SecurityConfig.None,
        val transport: TransportConfig = TransportConfig.Tcp
    ) : ProxyNodeConfig(nodeTag, serverHost, port, "VLESS")

    data class Vmess(
        val nodeTag: String,
        val serverHost: String,
        val port: Int,
        val uuid: String,
        val alterId: Int = 0,
        val security: String = "auto",
        val transport: TransportConfig = TransportConfig.Tcp,
        val tlsConfig: SecurityConfig.Tls = SecurityConfig.Tls()
    ) : ProxyNodeConfig(nodeTag, serverHost, port, "VMess")

    data class Trojan(
        val nodeTag: String,
        val serverHost: String,
        val port: Int,
        val password: String,
        val security: SecurityConfig.Tls = SecurityConfig.Tls(),
        val transport: TransportConfig = TransportConfig.Tcp
    ) : ProxyNodeConfig(nodeTag, serverHost, port, "Trojan")

    data class Hysteria2(
        val nodeTag: String,
        val serverHost: String,
        val port: Int,
        val password: String,
        val upMbps: Int? = null,
        val downMbps: Int? = null,
        val obfsType: String? = null,
        val obfsPassword: String? = null,
        val security: SecurityConfig.Tls = SecurityConfig.Tls()
    ) : ProxyNodeConfig(nodeTag, serverHost, port, "Hysteria2")

    data class Tuic(
        val nodeTag: String,
        val serverHost: String,
        val port: Int,
        val uuid: String,
        val password: String,
        val congestionControl: String = "bbr",
        val udpRelayMode: String = "native",
        val security: SecurityConfig.Tls = SecurityConfig.Tls()
    ) : ProxyNodeConfig(nodeTag, serverHost, port, "TUIC")

    data class Shadowsocks(
        val nodeTag: String,
        val serverHost: String,
        val port: Int,
        val method: String,
        val password: String
    ) : ProxyNodeConfig(nodeTag, serverHost, port, "Shadowsocks")
}
