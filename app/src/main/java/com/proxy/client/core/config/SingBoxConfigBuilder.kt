package com.proxy.client.core.config

import com.proxy.client.core.config.model.ProxyNodeConfig
import org.json.JSONArray
import org.json.JSONObject

class SingBoxConfigBuilder {

    private var localSocksPort: Int = 2080
    private var routeMode: RouteMode = RouteMode.RULE
    private val outbounds = mutableListOf<JSONObject>()

    fun setLocalSocksPort(port: Int): SingBoxConfigBuilder {
        this.localSocksPort = port
        return this
    }

    fun setRouteMode(mode: RouteMode): SingBoxConfigBuilder {
        this.routeMode = mode
        return this
    }

    fun addProxyNode(node: ProxyNodeConfig): SingBoxConfigBuilder {
        val outbound = OutboundJsonAdapter.toJson(node)
        outbounds.add(outbound)
        return this
    }

    fun build(activeOutboundTag: String): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("level", "warn")
            put("timestamp", true)
        })

        // 标准 SingBox 1.13.x DNS 配置 (通过代理节点进行远程加密解析)
        root.put("dns", JSONObject().apply {
            val servers = JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "remote-dns")
                    put("type", "udp")
                    put("server", "8.8.8.8")
                    put("detour", activeOutboundTag)
                })
            }
            put("servers", servers)
            put("final", "remote-dns")
            put("strategy", "prefer_ipv4")
        })

        val inbounds = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "mixed")
                put("tag", "mixed-in")
                put("listen", "127.0.0.1")
                put("listen_port", localSocksPort)
            })
        }
        root.put("inbounds", inbounds)

        val finalOutbounds = JSONArray()
        outbounds.forEach { finalOutbounds.put(it) }
        finalOutbounds.put(JSONObject().apply {
            put("type", "block")
            put("tag", "block-out")
        })
        root.put("outbounds", finalOutbounds)

        root.put("route", JSONObject().apply {
            put("final", activeOutboundTag)
            val rules = JSONArray().apply {
                put(JSONObject().apply {
                    put("action", "sniff")
                })
                put(JSONObject().apply {
                    put("port", JSONArray().put(53))
                    put("action", "hijack-dns")
                })
            }
            put("rules", rules)
        })

        return root.toString(2)
    }
}