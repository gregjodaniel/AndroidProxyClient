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

        // 标准 SingBox 1.13.x DNS 配置
        root.put("dns", JSONObject().apply {
            val servers = JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "remote-dns")
                    put("type", "udp")
                    put("server", "8.8.8.8")
                    put("detour", activeOutboundTag)
                })
                put(JSONObject().apply {
                    put("tag", "direct-dns")
                    put("type", "udp")
                    put("server", "223.5.5.5")
                })
                put(JSONObject().apply {
                    put("tag", "local-dns")
                    put("type", "local")
                })
            }
            put("servers", servers)

            val rules = JSONArray().apply {
                put(JSONObject().apply {
                    put("outbound", JSONArray().put("direct-out"))
                    put("server", "direct-dns")
                })
            }
            put("rules", rules)
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
            put("type", "direct")
            put("tag", "direct-out")
            put("domain_strategy", "prefer_ipv4")
        })
        finalOutbounds.put(JSONObject().apply {
            put("type", "block")
            put("tag", "block-out")
        })
        root.put("outbounds", finalOutbounds)

        val finalOutbound = when (routeMode) {
            RouteMode.DIRECT -> "direct-out"
            RouteMode.GLOBAL, RouteMode.RULE -> activeOutboundTag
        }

        root.put("route", JSONObject().apply {
            put("auto_detect_interface", true)
            put("final", finalOutbound)
            val rules = JSONArray().apply {
                put(JSONObject().apply {
                    put("action", "sniff")
                })
                put(JSONObject().apply {
                    put("port", JSONArray().put(53))
                    put("action", "hijack-dns")
                })
                if (routeMode == RouteMode.RULE) {
                    put(JSONObject().apply {
                        put("ip_is_private", true)
                        put("outbound", "direct-out")
                    })
                }
            }
            put("rules", rules)
        })

        return root.toString(2)
    }
}