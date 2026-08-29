package com.proxy.client.core.config

import com.proxy.client.core.config.model.*
import org.json.JSONArray
import org.json.JSONObject

object OutboundJsonAdapter {

    fun toJson(node: ProxyNodeConfig): JSONObject {
        val json = JSONObject()
        json.put("tag", node.tag)
        json.put("server", node.server)
        json.put("server_port", node.serverPort)

        when (node) {
            is ProxyNodeConfig.Vless -> {
                json.put("type", "vless")
                json.put("uuid", node.uuid)
                node.flow?.let { if (it.isNotBlank()) json.put("flow", it) }
                applySecurity(json, node.security)
                applyTransport(json, node.transport)
            }
            is ProxyNodeConfig.Vmess -> {
                json.put("type", "vmess")
                json.put("uuid", node.uuid)
                json.put("alter_id", node.alterId)
                json.put("security", node.security)
                applySecurity(json, node.tlsConfig)
                applyTransport(json, node.transport)
            }
            is ProxyNodeConfig.Trojan -> {
                json.put("type", "trojan")
                json.put("password", node.password)
                applySecurity(json, node.security)
                applyTransport(json, node.transport)
            }
            is ProxyNodeConfig.Hysteria2 -> {
                json.put("type", "hysteria2")
                json.put("password", node.password)
                node.upMbps?.let { json.put("up_mbps", it) }
                node.downMbps?.let { json.put("down_mbps", it) }
                if (!node.obfsType.isNullOrBlank() && !node.obfsPassword.isNullOrBlank()) {
                    json.put("obfs", JSONObject().apply {
                        put("type", node.obfsType)
                        put("password", node.obfsPassword)
                    })
                }
                applySecurity(json, node.security)
            }
            is ProxyNodeConfig.Tuic -> {
                json.put("type", "tuic")
                json.put("uuid", node.uuid)
                json.put("password", node.password)
                json.put("congestion_control", node.congestionControl)
                json.put("udp_relay_mode", node.udpRelayMode)
                applySecurity(json, node.security)
            }
            is ProxyNodeConfig.Shadowsocks -> {
                json.put("type", "shadowsocks")
                json.put("method", node.method)
                json.put("password", node.password)
            }
        }
        return json
    }

    private fun applySecurity(json: JSONObject, security: SecurityConfig) {
        when (security) {
            is SecurityConfig.Tls -> {
                json.put("tls", JSONObject().apply {
                    put("enabled", true)
                    security.serverName?.let { if (it.isNotBlank()) put("server_name", it) }
                    put("alpn", JSONArray(security.alpn))
                    put("insecure", security.allowInsecure)
                })
            }
            is SecurityConfig.Reality -> {
                json.put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", security.serverName)
                    put("reality", JSONObject().apply {
                        put("enabled", true)
                        put("public_key", security.publicKey)
                        put("short_id", security.shortId)
                    })
                })
            }
            SecurityConfig.None -> {}
        }
    }

    private fun applyTransport(json: JSONObject, transport: TransportConfig) {
        when (transport) {
            is TransportConfig.WebSocket -> {
                json.put("transport", JSONObject().apply {
                    put("type", "ws")
                    put("path", transport.path)
                    if (transport.maxEarlyData > 0) {
                        put("max_early_data", transport.maxEarlyData)
                        put("early_data_header_name", transport.earlyDataHeaderName)
                    }
                    if (transport.headers.isNotEmpty()) {
                        put("headers", JSONObject(transport.headers))
                    }
                })
            }
            is TransportConfig.Grpc -> {
                json.put("transport", JSONObject().apply {
                    put("type", "grpc")
                    put("service_name", transport.serviceName)
                    put("idle_timeout", transport.idleTimeout)
                    put("ping_timeout", transport.pingTimeout)
                })
            }
            is TransportConfig.HttpUpgrade -> {
                json.put("transport", JSONObject().apply {
                    put("type", "httpupgrade")
                    transport.host?.let { put("host", it) }
                    put("path", transport.path)
                })
            }
            TransportConfig.Tcp -> {}
        }
    }
}
