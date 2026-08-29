package com.proxy.client.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.proxy.client.core.config.AppProxyConfig
import com.proxy.client.core.config.RouteMode
import com.proxy.client.core.config.model.ProxyNodeConfig
import com.proxy.client.core.config.model.SecurityConfig
import com.proxy.client.core.config.model.TransportConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class NodeRepository private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("proxy_client_prefs", Context.MODE_PRIVATE)

    private val _nodeList = MutableStateFlow<List<ProxyNodeConfig>>(emptyList())
    val nodeList: StateFlow<List<ProxyNodeConfig>> = _nodeList.asStateFlow()

    private val _activeNodeTag = MutableStateFlow<String?>(null)
    val activeNodeTag: StateFlow<String?> = _activeNodeTag.asStateFlow()

    private val _latencyMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    val latencyMap: StateFlow<Map<String, Int>> = _latencyMap.asStateFlow()

    private val _appProxyConfig = MutableStateFlow(AppProxyConfig())
    val appProxyConfig: StateFlow<AppProxyConfig> = _appProxyConfig.asStateFlow()

    init {
        loadFromPrefs()
    }

    fun updateNodes(nodes: List<ProxyNodeConfig>) {
        _nodeList.value = ArrayList(nodes)
        if (_activeNodeTag.value == null && nodes.isNotEmpty()) {
            _activeNodeTag.value = nodes.first().tag
        }
        saveToPrefs()
    }

    fun addNodes(newNodes: List<ProxyNodeConfig>) {
        val current = _nodeList.value.toMutableList()
        newNodes.forEach { n ->
            current.removeAll { it.tag == n.tag }
            current.add(n)
        }
        _nodeList.value = ArrayList(current)
        if (_activeNodeTag.value == null && current.isNotEmpty()) {
            _activeNodeTag.value = current.first().tag
        }
        saveToPrefs()
    }

    fun selectNode(tag: String) {
        if (_nodeList.value.any { it.tag == tag }) {
            _activeNodeTag.value = tag
            prefs.edit().putString(KEY_ACTIVE_TAG, tag).apply()
        }
    }

    fun deleteNode(tag: String) {
        val list = _nodeList.value.filter { it.tag != tag }
        _nodeList.value = ArrayList(list)
        if (_activeNodeTag.value == tag) {
            _activeNodeTag.value = list.firstOrNull()?.tag
        }
        saveToPrefs()
    }

    fun clearAllNodes() {
        _nodeList.value = emptyList()
        _activeNodeTag.value = null
        _latencyMap.value = emptyMap()
        saveToPrefs()
    }

    fun updateLatencies(latencies: Map<String, Int>) {
        val merged = _latencyMap.value.toMutableMap()
        merged.putAll(latencies)
        _latencyMap.value = merged
    }

    fun setRouteMode(mode: RouteMode) {
        _appProxyConfig.value = _appProxyConfig.value.copy(routeMode = mode)
        prefs.edit().putString(KEY_ROUTE_MODE, mode.name).apply()
    }

    fun updateAppProxyConfig(config: AppProxyConfig) {
        _appProxyConfig.value = config
        prefs.edit().putString(KEY_ROUTE_MODE, config.routeMode.name).apply()
    }

    fun getActiveNode(): ProxyNodeConfig? {
        val currentTag = _activeNodeTag.value ?: return null
        return _nodeList.value.find { it.tag == currentTag }
    }

    private fun saveToPrefs() {
        val array = JSONArray()
        _nodeList.value.forEach { node ->
            array.put(serializeNode(node))
        }

        prefs.edit()
            .putString(KEY_ACTIVE_TAG, _activeNodeTag.value)
            .putString(KEY_ROUTE_MODE, _appProxyConfig.value.routeMode.name)
            .putString(KEY_NODES_JSON, array.toString())
            .apply()
    }

    private fun loadFromPrefs() {
        val savedTag = prefs.getString(KEY_ACTIVE_TAG, null)
        val savedRoute = prefs.getString(KEY_ROUTE_MODE, RouteMode.RULE.name)
        val nodesJson = prefs.getString(KEY_NODES_JSON, null)

        val routeMode = try {
            RouteMode.valueOf(savedRoute ?: RouteMode.RULE.name)
        } catch (_: Exception) {
            RouteMode.RULE
        }
        _appProxyConfig.value = AppProxyConfig(routeMode = routeMode)

        if (!nodesJson.isNullOrEmpty()) {
            val list = mutableListOf<ProxyNodeConfig>()
            try {
                val array = JSONArray(nodesJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val node = deserializeNode(obj)
                    if (node != null) {
                        list.add(node)
                    }
                }
            } catch (_: Exception) {}
            _nodeList.value = list
        }

        _activeNodeTag.value = savedTag ?: _nodeList.value.firstOrNull()?.tag
    }

    private fun serializeNode(node: ProxyNodeConfig): JSONObject {
        val json = JSONObject()
        json.put("tag", node.tag)
        json.put("server", node.server)
        json.put("port", node.serverPort)
        json.put("protocol", node.protocolType)

        when (node) {
            is ProxyNodeConfig.Vless -> {
                json.put("uuid", node.uuid)
                json.put("flow", node.flow ?: "")
                when (val sec = node.security) {
                    is SecurityConfig.Reality -> {
                        json.put("security_type", "reality")
                        json.put("sni", sec.serverName)
                        json.put("pbk", sec.publicKey)
                        json.put("sid", sec.shortId)
                    }
                    is SecurityConfig.Tls -> {
                        json.put("security_type", "tls")
                        json.put("sni", sec.serverName ?: "")
                    }
                    else -> json.put("security_type", "none")
                }
                when (val tr = node.transport) {
                    is TransportConfig.WebSocket -> {
                        json.put("transport_type", "ws")
                        json.put("path", tr.path)
                    }
                    is TransportConfig.Grpc -> {
                        json.put("transport_type", "grpc")
                        json.put("serviceName", tr.serviceName)
                    }
                    is TransportConfig.HttpUpgrade -> {
                        json.put("transport_type", "httpupgrade")
                        json.put("path", tr.path)
                    }
                    else -> json.put("transport_type", "tcp")
                }
            }
            is ProxyNodeConfig.Vmess -> {
                json.put("uuid", node.uuid)
                json.put("alterId", node.alterId)
                json.put("security", node.security)
                json.put("sni", node.tlsConfig.serverName ?: "")
            }
            is ProxyNodeConfig.Trojan -> {
                json.put("password", node.password)
                json.put("sni", node.security.serverName ?: "")
            }
            is ProxyNodeConfig.Hysteria2 -> {
                json.put("password", node.password)
                json.put("sni", node.security.serverName ?: "")
                json.put("obfsType", node.obfsType ?: "")
                json.put("obfsPassword", node.obfsPassword ?: "")
            }
            is ProxyNodeConfig.Tuic -> {
                json.put("uuid", node.uuid)
                json.put("password", node.password)
                json.put("sni", node.security.serverName ?: "")
                json.put("congestion_control", node.congestionControl)
                json.put("udp_relay_mode", node.udpRelayMode)
            }
            is ProxyNodeConfig.Shadowsocks -> {
                json.put("method", node.method)
                json.put("password", node.password)
            }
        }
        return json
    }

    private fun deserializeNode(json: JSONObject): ProxyNodeConfig? {
        val tag = json.optString("tag")
        val server = json.optString("server")
        val port = json.optInt("port", 443)
        val protocol = json.optString("protocol")

        return try {
            when (protocol) {
                "VLESS" -> {
                    val uuid = json.optString("uuid")
                    val flow = json.optString("flow").ifEmpty { null }
                    val secType = json.optString("security_type", "none")
                    val security = when (secType) {
                        "reality" -> SecurityConfig.Reality(
                            serverName = json.optString("sni"),
                            publicKey = json.optString("pbk"),
                            shortId = json.optString("sid")
                        )
                        "tls" -> SecurityConfig.Tls(serverName = json.optString("sni"))
                        else -> SecurityConfig.None
                    }
                    val trType = json.optString("transport_type", "tcp")
                    val transport = when (trType) {
                        "ws" -> TransportConfig.WebSocket(path = json.optString("path", "/"))
                        "grpc" -> TransportConfig.Grpc(serviceName = json.optString("serviceName"))
                        "httpupgrade" -> TransportConfig.HttpUpgrade(path = json.optString("path", "/"))
                        else -> TransportConfig.Tcp
                    }
                    ProxyNodeConfig.Vless(tag, server, port, uuid, flow, security, transport)
                }
                "VMess" -> {
                    val uuid = json.optString("uuid")
                    val alterId = json.optInt("alterId", 0)
                    val sec = json.optString("security", "auto")
                    val sni = json.optString("sni")
                    ProxyNodeConfig.Vmess(tag, server, port, uuid, alterId, sec, TransportConfig.Tcp, SecurityConfig.Tls(serverName = sni))
                }
                "Trojan" -> {
                    val pass = json.optString("password")
                    val sni = json.optString("sni")
                    ProxyNodeConfig.Trojan(tag, server, port, pass, SecurityConfig.Tls(serverName = sni))
                }
                "Hysteria2" -> {
                    val pass = json.optString("password")
                    val sni = json.optString("sni")
                    val obfs = json.optString("obfsType").ifEmpty { null }
                    val obfsPass = json.optString("obfsPassword").ifEmpty { null }
                    ProxyNodeConfig.Hysteria2(tag, server, port, pass, null, null, obfs, obfsPass, SecurityConfig.Tls(serverName = sni))
                }
                "TUIC" -> {
                    val uuid = json.optString("uuid")
                    val pass = json.optString("password")
                    val sni = json.optString("sni")
                    val cc = json.optString("congestion_control", "bbr")
                    val mode = json.optString("udp_relay_mode", "native")
                    ProxyNodeConfig.Tuic(tag, server, port, uuid, pass, cc, mode, SecurityConfig.Tls(serverName = sni))
                }
                "Shadowsocks" -> {
                    val method = json.optString("method", "aes-256-gcm")
                    val pass = json.optString("password")
                    ProxyNodeConfig.Shadowsocks(tag, server, port, method, pass)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val KEY_ACTIVE_TAG = "active_node_tag"
        private const val KEY_ROUTE_MODE = "route_mode"
        private const val KEY_NODES_JSON = "saved_nodes_json"

        @Volatile
        private var instance: NodeRepository? = null

        fun getInstance(context: Context): NodeRepository {
            return instance ?: synchronized(this) {
                instance ?: NodeRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}