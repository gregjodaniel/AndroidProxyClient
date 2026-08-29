package com.proxy.client.core.config

import com.proxy.client.core.config.model.ProxyNodeConfig
import org.json.JSONArray
import org.json.JSONObject

class SingBoxConfigBuilder {

    private var localSocksPort: Int = 2080
    private val outbounds = mutableListOf<JSONObject>()
    private var routeMode: RouteMode = RouteMode.RULE

    fun setLocalInbound(socksPort: Int) = apply {
        this.localSocksPort = socksPort
    }

    fun setRouteMode(mode: RouteMode) = apply {
        this.routeMode = mode
    }

    fun addProxyNode(node: ProxyNodeConfig) = apply {
        outbounds.add(OutboundJsonAdapter.toJson(node))
    }

    fun build(activeOutboundTag: String): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("level", "info")
            put("timestamp", true)
        })

        // sing-box 1.12+ / 1.13+ 官方全新 DNS 服务器格式 (移除了已废弃的 address 字段)
        root.put("dns", JSONObject().apply {
            val servers = JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "remote-dns")
                    put("type", "https")
                    put("server", "1.1.1.1")
                    put("path", "/dns-query")
                    put("detour", activeOutboundTag)
                })
                put(JSONObject().apply {
                    put("tag", "direct-dns")
                    put("type", "udp")
                    put("server", "223.5.5.5")
                    put("detour", "direct-out")
                })
                put(JSONObject().apply {
                    put("tag", "local-dns")
                    put("type", "local")
                    put("detour", "direct-out")
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
                put("sniff", true)
            })
        }
        root.put("inbounds", inbounds)

        val finalOutbounds = JSONArray()
        outbounds.forEach { finalOutbounds.put(it) }
        finalOutbounds.put(JSONObject().apply {
            put("type", "direct")
            put("tag", "direct-out")
        })
        finalOutbounds.put(JSONObject().apply {
            put("type", "block")
            put("tag", "block-out")
        })
        finalOutbounds.put(JSONObject().apply {
            put("type", "dns")
            put("tag", "dns-out")
        })
        root.put("outbounds", finalOutbounds)

        val finalOutbound = when (routeMode) {
            RouteMode.DIRECT -> "direct-out"
            RouteMode.GLOBAL, RouteMode.RULE -> activeOutboundTag
        }

        root.put("route", JSONObject().apply {
            put("final", finalOutbound)
            val rules = JSONArray().apply {
                put(JSONObject().apply {
                    put("port", JSONArray().put(53))
                    put("outbound", "dns-out")
                })
                put(JSONObject().apply {
                    put("protocol", JSONArray().put("dns"))
                    put("outbound", "dns-out")
                })
                if (routeMode == RouteMode.RULE) {
                    put(JSONObject().apply {
                        put("ip_cidr", JSONArray().apply {
                            put("10.0.0.0/8")
                            put("172.16.0.0/12")
                            put("192.168.0.0/16")
                            put("127.0.0.0/8")
                        })
                        put("outbound", "direct-out")
                    })
                }
            }
            put("rules", rules)
        })

        return root.toString(2)
    }
}