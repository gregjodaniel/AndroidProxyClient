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

        // 标准 SingBox 1.13.x DNS 配置 (通过 UnmarshalJSONContext 精准解析)
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
                // 注意:这里不再写 sniff 字段。
                // inbound.sniff 这类"legacy inbound fields"在sing-box 1.11
                // 就标记废弃、1.13.0直接从解析器里删掉了,写了就直接
                // 解析报错("legacy inbound fields are deprecated...")。
                // 官方迁移文档给的新写法是把sniff挪到route.rules里,
                // 作为一个"action": "sniff"的规则动作,放在route那边处理,
                // 见下面route.rules的第一条。
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
        root.put("outbounds", finalOutbounds)

        val finalOutbound = when (routeMode) {
            RouteMode.DIRECT -> "direct-out"
            RouteMode.GLOBAL, RouteMode.RULE -> activeOutboundTag
        }

        root.put("route", JSONObject().apply {
            put("final", finalOutbound)
            val rules = JSONArray().apply {
                // sniff必须放在规则列表最前面——官方迁移文档的原话是
                // "sniff and resolve rule actions are typically used
                // at the head of the rule list to ensure later rules
                // see useful metadata"。没有这一条,后面依赖协议嗅探
                // 结果的规则(比如下面的protocol:dns)会失效。
                put(JSONObject().apply {
                    put("action", "sniff")
                })
                // 原来是"匹配到53端口/dns协议就outbound到一个type:dns的
                // 特殊出站",现在改成"action: hijack-dns"直接在路由层
                // 劫持DNS请求交给dns模块处理,这是官方迁移文档给的
                // 一一对应替换写法。
                put(JSONObject().apply {
                    put("port", JSONArray().put(53))
                    put("action", "hijack-dns")
                })
                put(JSONObject().apply {
                    put("protocol", JSONArray().put("dns"))
                    put("action", "hijack-dns")
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