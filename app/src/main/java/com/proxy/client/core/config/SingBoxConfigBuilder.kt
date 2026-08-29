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

    /**
     * @param activeOutboundTag 当前选中节点的outbound tag
     * @param tunFd 已经由 VpnService.Builder().establish() 建立好的TUN文件描述符。
     *              之前的版本没有这个参数,inbound里也没写fd字段,
     *              配合 auto_route=true 会导致sing-box尝试自己创建TUN设备——
     *              这在没有root权限的普通App里是做不到的,是TUN转发链路
     *              完全不通的根本原因。现在改成直接复用已经建立好的fd,
     *              并把 auto_route/strict_route 都关掉,因为地址、路由、DNS
     *              这些已经在 LocalVpnService 的 Builder 里配置过一次了,
     *              不需要sing-box自己再配置一遍(两边都配反而容易冲突)。
     */
    fun build(activeOutboundTag: String, tunFd: Int): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("level", "warn")
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
                    put("tag", "local-dns")
                    put("address", "223.5.5.5")
                    put("detour", "direct-out")
                })
            }
            put("servers", servers)
            val dnsRules = JSONArray().apply {
                put(JSONObject().apply {
                    put("geosite", JSONArray().put("cn"))
                    put("server", "local-dns")
                })
            }
            put("rules", dnsRules)
        })

        val inbounds = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("fd", tunFd)          // 关键修复:复用已建立的fd,而不是让sing-box自己创建TUN设备
                put("inet4_address", "172.19.0.1/30")
                put("auto_route", false)  // 路由已经由LocalVpnService的Builder配置过了
                put("strict_route", false)
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
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                })
                put(JSONObject().apply {
                    put("geoip", JSONArray().put("private"))
                    put("outbound", "direct-out")
                })

                when (routeMode) {
                    RouteMode.RULE -> {
                        put(JSONObject().apply {
                            put("geosite", JSONArray().put("cn"))
                            put("outbound", "direct-out")
                        })
                        put(JSONObject().apply {
                            put("geoip", JSONArray().put("cn"))
                            put("outbound", "direct-out")
                        })
                        put(JSONObject().apply {
                            put("outbound", activeOutboundTag)
                        })
                    }
                    RouteMode.GLOBAL -> {
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
