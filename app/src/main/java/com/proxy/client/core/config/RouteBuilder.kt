package com.proxy.client.core.config

/**
 * 分流模式,对齐Clash系客户端的经典三段命名:
 *  - RULE:   按规则分流(局域网直连 + 国内直连 + 其余走代理),日常最常用
 *  - GLOBAL: 全局代理,所有流量都走代理节点,不做任何判断
 *  - DIRECT: 全局直连,所有流量都不走代理(相当于临时关闭代理但保留VPN开关状态)
 */
enum class RouteMode(val title: String) {
    RULE("规则"),
    GLOBAL("全局"),
    DIRECT("直连")
}

enum class AppProxyMode {
    ALL,
    ALLOW_LIST,
    DISALLOW_LIST
}

data class AppProxyConfig(
    val mode: AppProxyMode = AppProxyMode.ALL,
    val packageList: Set<String> = emptySet(),
    val routeMode: RouteMode = RouteMode.RULE
)
