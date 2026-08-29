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
            // 注意:这里故意不设置 auto_detect_interface。
            // 这个选项会让sing-box在初始化时立刻创建一个基于netlink的
            // 网络接口监控器,用来实时感知"当前默认网络是WiFi还是移动数据"。
            // 但netlink socket在Android上普通App(非root/非系统应用)
            // 是被禁止直接创建的——这正是报错
            // "netlink socket in Android is banned by Google" 的来源,
            // 这句话是sing-tun库里硬编码的原文,不是我们代码的问题。
            //
            // 官方sing-box-for-android能用这个功能,是因为它实现了完整的
            // PlatformInterface,让网络接口监控改走Android自己的
            // ConnectivityManager API,而不是走Linux的netlink。
            // 我们现在的架构(sing-box只跑本地mixed/socks,TUN由
            // tun2socks单独接管,见core_bridge.go)本身就不需要
            // sing-box自己感知网络接口变化——出站连接走系统默认路由
            // 就够了,所以直接不启用这个选项,而不是去实现一整套
            // PlatformInterface(那是官方GUI客户端级别的工作量)。
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