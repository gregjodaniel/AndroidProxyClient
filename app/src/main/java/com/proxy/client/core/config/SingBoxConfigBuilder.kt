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

        // 代理节点的服务器地址, 用于 DNS bootstrap 规则
        val proxyServerHost = outbounds.firstOrNull()?.optString("server").orEmpty()

        // SingBox 1.13.x DNS 配置 (v1.2.7 修复):
        // 1. remote-dns: 客户端流量经 hijack-dns 劫持后走这里, 通过代理节点解析 (防 DNS 污染)
        // 2. direct-dns: 直连 UDP DNS, 只负责解析代理节点自身的域名 (bootstrap),
        //    打破 "remote-dns 经代理 -> 代理要先解析自身域名 -> 又走 remote-dns" 的死循环。
        //
        // 关键: 之前这里用的是 type:local, 但 sing-box 的 local 实现在 Android 上
        // 是读 /etc/resolv.conf, 而 Android 根本没有这个文件, 会回退到 127.0.0.1:53,
        // 导致所有域名节点的解析永远失败 (App 显示已连接但打不开网页)。
        // direct-dns 不设 detour, sing-box 会用系统网络直接拨号;
        // App 已通过 addDisallowedApplication 把自身流量排除出 VPN, 不会形成回路。
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
            }
            put("servers", servers)

            val rules = JSONArray().apply {
                // 节点服务器是域名时才需要这条: 用直连 DNS 解析它。
                // 精确的 domain 规则, 不再依赖 1.13 已废弃的 outbound:any 写法。
                if (isDomainName(proxyServerHost)) {
                    put(JSONObject().apply {
                        put("domain", JSONArray().put(proxyServerHost))
                        put("server", "direct-dns")
                    })
                }
            }
            put("rules", rules)
            // DIRECT 模式下 DNS 也直连, 其余模式经代理防污染
            put("final", if (routeMode == RouteMode.DIRECT) "direct-dns" else "remote-dns")
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
        })
        finalOutbounds.put(JSONObject().apply {
            put("type", "block")
            put("tag", "block-out")
        })
        root.put("outbounds", finalOutbounds)

        root.put("route", JSONObject().apply {
            val rules = JSONArray().apply {
                put(JSONObject().apply {
                    put("action", "sniff")
                })
                put(JSONObject().apply {
                    put("port", JSONArray().put(53))
                    put("action", "hijack-dns")
                })
                if (routeMode == RouteMode.RULE) {
                    // RULE 模式: 局域网/私有地址直连, 其余走代理。
                    // 注: 完整的大陆白名单分流需要 geoip/geosite 数据文件, 后续版本再接入。
                    put(JSONObject().apply {
                        put("ip_is_private", true)
                        put("action", "route")
                        put("outbound", "direct-out")
                    })
                }
            }
            put("rules", rules)
            // DIRECT 模式全部直连; RULE / GLOBAL 模式走代理节点
            put("final", if (routeMode == RouteMode.DIRECT) "direct-out" else activeOutboundTag)
        })

        return root.toString(2)
    }

    /**
     * 判断是否为域名 (而非 IPv4 / IPv6 字面量)。
     * 只有域名才需要 DNS bootstrap 规则; IP 字面量不需要解析。
     */
    private fun isDomainName(host: String): Boolean {
        if (host.isBlank()) return false
        if (host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return false // IPv4
        if (host.contains(":")) return false // IPv6
        return true
    }
}