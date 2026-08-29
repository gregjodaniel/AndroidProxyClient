package com.proxy.client.core.config

import com.proxy.client.core.config.model.ProxyNodeConfig
import org.json.JSONArray
import org.json.JSONObject

class SingBoxConfigBuilder {

    private var localSocksPort: Int = 2080
    private var localHttpPort: Int = 2081
    private val outbounds = mutableListOf<JSONObject>()
    private var routeMode: RouteMode = RouteMode.RULE

    fun setLocalInbound(socksPort: Int, httpPort: Int) = apply {
        this.localSocksPort = socksPort
        this.localHttpPort = httpPort
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

        root.put("dns", JSONObject().apply {
            val servers = JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "remote-dns")
                    put("address", "https://1.1.1.1/dns-query")
                    put("detour", activeOutboundTag)
                })
                put(JSONObject().apply {
                    put("tag", "direct-dns")
                    put("address", "223.5.5.5")
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
                put("type", "tun")
                put("tag", "tun-in")
                put("inet4_address", "172.19.0.1/30")
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "gvisor")
                put("sniff", true)
            })
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

        root.put("route", JSONObject().apply {
            put("auto_detect_interface", true)
            val rules = JSONArray().apply {
                put(JSONObject().apply {
                    put("port", JSONArray().put(53))
                    put("outbound", "dns-out")
                })
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                })
                put(JSONObject().apply {
                    put("ip_cidr", JSONArray().apply {
                        put("10.0.0.0/8")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                        put("127.0.0.0/8")
                    })
                    put("outbound", "direct-out")
                })

                when (routeMode) {
                    RouteMode.RULE, RouteMode.GLOBAL -> {
                        put(JSONObject().apply {
                            put("outbound", activeOutboundTag)
                        })
                    }
                    RouteMode.DIRECT -> {
                        put(JSONObject().apply {
                            put("outbound", "direct-out")
                        })
                    }
                }
            }
            put("rules", rules)
        })

        return root.toString(2)
    }
}