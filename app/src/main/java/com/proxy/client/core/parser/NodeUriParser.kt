package com.proxy.client.core.parser

import android.net.Uri
import android.util.Base64
import com.proxy.client.core.config.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object NodeUriParser {

    /**
     * 安全说明(这是本次修复重点之一):
     * 上一版这里用了一个信任所有证书、跳过主机名校验的OkHttpClient去拉取订阅,
     * 配合Manifest里的 usesCleartextTraffic="true",相当于订阅拉取这一步
     * 完全没有传输层安全保障——中间人可以随意篡改订阅内容,而订阅内容
     * 直接决定了用户的全部流量会被导向哪个"节点"。对一个处理全部网络流量
     * 的代理客户端来说这是真实的攻击面,不是可以将就的细节。
     * 现在改回OkHttp的默认行为:走系统信任的CA列表,正常校验证书链和主机名。
     *
     * 如果之后确实遇到某些机场用自签名证书导致订阅拉取失败,
     * 正确的做法是让用户在设置里显式为"这一条订阅"打开信任例外
     * (类似浏览器的"仍要访问"),而不是全局默认关闭校验。
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun parseUri(rawUri: String): ProxyNodeConfig? {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return null
        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
                trimmed.startsWith("hysteria2://", ignoreCase = true) || 
                trimmed.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(trimmed)
                trimmed.startsWith("tuic://", ignoreCase = true) -> parseTuic(trimmed)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseSubscription(content: String): List<ProxyNodeConfig> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Check if it's Clash YAML
        if (trimmed.contains("proxies:", ignoreCase = true) || trimmed.contains("Proxy:", ignoreCase = true)) {
            val yamlNodes = parseClashYaml(trimmed)
            if (yamlNodes.isNotEmpty()) return yamlNodes
        }

        // 2. Check if it's JSON (SingBox or Xray format)
        if (trimmed.startsWith("{") && (trimmed.contains("\"outbounds\"") || trimmed.contains("\"proxies\""))) {
            val jsonNodes = parseJsonConfig(trimmed)
            if (jsonNodes.isNotEmpty()) return jsonNodes
        }

        // 3. Try decoding as full Base64 subscription
        val decoded = decodeBase64Safe(trimmed)
        if (decoded != trimmed) {
            if (decoded.contains("proxies:", ignoreCase = true) || decoded.contains("Proxy:", ignoreCase = true)) {
                val yamlNodes = parseClashYaml(decoded)
                if (yamlNodes.isNotEmpty()) return yamlNodes
            }
            if (decoded.startsWith("{") && (decoded.contains("\"outbounds\"") || decoded.contains("\"proxies\""))) {
                val jsonNodes = parseJsonConfig(decoded)
                if (jsonNodes.isNotEmpty()) return jsonNodes
            }
        }

        val textToProcess = if (decoded.contains("://") || decoded.contains("{")) decoded else trimmed
        val results = mutableListOf<ProxyNodeConfig>()

        // 4. Parse line by line
        textToProcess.lines().forEach { line ->
            val clean = line.trim()
            if (clean.isNotEmpty() && !clean.startsWith("#") && !clean.startsWith("//")) {
                val node = parseUri(clean)
                if (node != null) {
                    results.add(node)
                }
            }
        }

        // 5. Regex scanner for embedded URIs
        if (results.isEmpty()) {
            val schemePattern = Pattern.compile("(?i)(vless|vmess|trojan|ss|hysteria2|hy2|tuic)://[^\\s\\r\\n\"\'<>]+")
            val matcher = schemePattern.matcher(textToProcess)
            while (matcher.find()) {
                val foundUri = matcher.group()
                val node = parseUri(foundUri)
                if (node != null) {
                    results.add(node)
                }
            }
        }

        // 6. Last fallback for Clash format
        if (results.isEmpty() && (textToProcess.contains("- name:") || textToProcess.contains("- {name:"))) {
            return parseClashYaml(textToProcess)
        }

        return results
    }

    suspend fun fetchSubscription(url: String): List<ProxyNodeConfig> = withContext(Dispatchers.IO) {
        val userAgents = listOf(
            "ClashMeta/v1.18.0",
            "Clash.Meta/1.18.0",
            "ClashforWindows/0.20.39",
            "v2rayNG/1.8.5",
            "Shadowrocket/1982",
            "sing-box/1.9.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        var lastException: Exception? = null

        for (ua in userAgents) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "text/plain, application/yaml, application/json, */*")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val nodes = parseSubscription(body)
                        if (nodes.isNotEmpty()) {
                            return@withContext nodes
                        }
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw lastException ?: Exception("未能从该订阅链接解析出有效节点，请确认订阅链接是否有效")
    }

    fun parseClashYaml(content: String): List<ProxyNodeConfig> {
        val results = mutableListOf<ProxyNodeConfig>()
        val lines = content.lines()
        var inProxiesSection = false
        var currentMap: MutableMap<String, String>? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            if (line.startsWith("proxies:", ignoreCase = true) || line.startsWith("Proxy:", ignoreCase = true)) {
                inProxiesSection = true
                continue
            }

            if (!inProxiesSection && !line.startsWith("- name:") && !line.startsWith("- {name:") && !line.startsWith("- { name:")) {
                continue
            }

            if (line.startsWith("- ") || line.startsWith("-{") || line.startsWith("- {")) {
                currentMap?.let { item ->
                    createNodeFromYamlMap(item)?.let { results.add(it) }
                }
                val newMap = mutableMapOf<String, String>()
                currentMap = newMap

                val afterDash = line.substring(2).trim()
                if (afterDash.startsWith("{") && afterDash.endsWith("}")) {
                    parseInlineYamlDict(afterDash.removeSurrounding("{", "}")).forEach { (k, v) ->
                        newMap[k.lowercase()] = v
                    }
                } else if (afterDash.contains(":")) {
                    val k = afterDash.substringBefore(":").trim().lowercase().removePrefix("-").trim()
                    val v = afterDash.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                    newMap[k] = v
                }
            } else if (inProxiesSection && line.contains(":")) {
                if (!rawLine.startsWith(" ") && !rawLine.startsWith("\t") && !rawLine.startsWith("-")) {
                    inProxiesSection = false
                    currentMap?.let { item ->
                        createNodeFromYamlMap(item)?.let { results.add(it) }
                    }
                    currentMap = null
                    continue
                }

                val targetMap = currentMap
                if (targetMap != null) {
                    val k = line.substringBefore(":").trim().lowercase()
                    val v = line.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                    targetMap[k] = v
                }
            }
        }

        currentMap?.let { item ->
            createNodeFromYamlMap(item)?.let { results.add(it) }
        }

        return results
    }

    private fun parseInlineYamlDict(dictStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pairs = dictStr.split(",")
        pairs.forEach { p ->
            val parts = p.split(":", limit = 2)
            if (parts.size == 2) {
                val k = parts[0].trim().removeSurrounding("\"").removeSurrounding("'").lowercase()
                val v = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                map[k] = v
            }
        }
        return map
    }

    private fun createNodeFromYamlMap(map: Map<String, String>): ProxyNodeConfig? {
        val tag = map["name"] ?: map["tag"] ?: "Node"
        val server = map["server"] ?: return null
        val port = map["port"]?.toIntOrNull() ?: 443
        val type = map["type"]?.lowercase() ?: return null

        return try {
            when (type) {
                "vless" -> {
                    val uuid = map["uuid"] ?: return null
                    val flow = map["flow"]
                    val tlsEnabled = map["tls"]?.equals("true", ignoreCase = true) == true
                    val hostHeader = map["host"] ?: map["servername"] ?: map["sni"] ?: server
                    val sni = map["servername"] ?: map["sni"] ?: hostHeader
                    val realityPub = map["public-key"] ?: map["pbk"] ?: ""
                    val realityShortId = map["short-id"] ?: map["sid"] ?: ""

                    val security = when {
                        realityPub.isNotEmpty() -> SecurityConfig.Reality(
                            serverName = sni,
                            publicKey = realityPub,
                            shortId = realityShortId
                        )
                        tlsEnabled -> SecurityConfig.Tls(serverName = sni)
                        else -> SecurityConfig.None
                    }

                    val network = map["network"]?.lowercase() ?: "tcp"
                    val path = map["path"] ?: "/"
                    val serviceName = map["grpc-service-name"] ?: map["servicename"] ?: ""

                    val transport = when (network) {
                        "ws" -> TransportConfig.WebSocket(
                            path = if (path.startsWith("/")) path else "/$path",
                            headers = if (hostHeader.isNotBlank()) mapOf("Host" to hostHeader) else emptyMap()
                        )
                        "grpc" -> TransportConfig.Grpc(serviceName = serviceName)
                        "httpupgrade" -> TransportConfig.HttpUpgrade(path = path, host = hostHeader.ifEmpty { null })
                        else -> TransportConfig.Tcp
                    }

                    ProxyNodeConfig.Vless(tag, server, port, uuid, flow, security, transport)
                }
                "vmess" -> {
                    val uuid = map["uuid"] ?: return null
                    val alterId = map["alterid"]?.toIntOrNull() ?: 0
                    val cipher = map["cipher"] ?: "auto"
                    val tlsEnabled = map["tls"]?.equals("true", ignoreCase = true) == true
                    val hostHeader = map["host"] ?: map["servername"] ?: map["sni"] ?: server
                    val sni = map["servername"] ?: map["sni"] ?: hostHeader
                    val network = map["network"]?.lowercase() ?: "tcp"
                    val path = map["path"] ?: "/"

                    val transport = when (network) {
                        "ws" -> TransportConfig.WebSocket(
                            path = if (path.startsWith("/")) path else "/$path",
                            headers = if (hostHeader.isNotBlank()) mapOf("Host" to hostHeader) else emptyMap()
                        )
                        "grpc" -> TransportConfig.Grpc(serviceName = path)
                        "httpupgrade" -> TransportConfig.HttpUpgrade(path = path, host = hostHeader.ifEmpty { null })
                        else -> TransportConfig.Tcp
                    }

                    val security = if (tlsEnabled) SecurityConfig.Tls(serverName = sni) else SecurityConfig.Tls()
                    ProxyNodeConfig.Vmess(tag, server, port, uuid, alterId, cipher, transport, security)
                }
                "hysteria2", "hy2" -> {
                    val password = map["password"] ?: map["auth"] ?: ""
                    val sni = map["sni"] ?: map["servername"] ?: server
                    val obfs = map["obfs"]
                    val obfsPassword = map["obfs-password"]
                    ProxyNodeConfig.Hysteria2(tag, server, port, password, null, null, obfs, obfsPassword, SecurityConfig.Tls(serverName = sni))
                }
                "tuic" -> {
                    val uuid = map["uuid"] ?: ""
                    val password = map["password"] ?: map["token"] ?: ""
                    val sni = map["sni"] ?: map["servername"] ?: server
                    val cc = map["congestion-controller"] ?: map["congestion_control"] ?: "bbr"
                    val mode = map["udp-relay-mode"] ?: "native"
                    ProxyNodeConfig.Tuic(tag, server, port, uuid, password, cc, mode, SecurityConfig.Tls(serverName = sni))
                }
                "trojan" -> {
                    val password = map["password"] ?: return null
                    val sni = map["sni"] ?: map["servername"] ?: server
                    ProxyNodeConfig.Trojan(tag, server, port, password, SecurityConfig.Tls(serverName = sni))
                }
                "ss", "shadowsocks" -> {
                    val cipher = map["cipher"] ?: "aes-256-gcm"
                    val password = map["password"] ?: ""
                    ProxyNodeConfig.Shadowsocks(tag, server, port, cipher, password)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseJsonConfig(jsonStr: String): List<ProxyNodeConfig> {
        val results = mutableListOf<ProxyNodeConfig>()
        try {
            val root = JSONObject(jsonStr)
            val outbounds = root.optJSONArray("outbounds") ?: root.optJSONArray("proxies") ?: JSONArray()
            for (i in 0 until outbounds.length()) {
                val obj = outbounds.getJSONObject(i)
                val type = obj.optString("type").lowercase()
                val tag = obj.optString("tag").ifEmpty { obj.optString("name", "Node-$i") }
                val server = obj.optString("server").ifEmpty { obj.optString("server_host") }
                val port = obj.optInt("server_port", obj.optInt("port", 443))

                if (server.isEmpty()) continue

                val node: ProxyNodeConfig? = when (type) {
                    "vless" -> {
                        val uuid = obj.optString("uuid")
                        val flow = obj.optString("flow").ifEmpty { null }
                        val tlsObj = obj.optJSONObject("tls")
                        val security = if (tlsObj != null && tlsObj.optBoolean("enabled", true)) {
                            val realityObj = tlsObj.optJSONObject("reality")
                            if (realityObj != null && realityObj.optBoolean("enabled", true)) {
                                SecurityConfig.Reality(
                                    serverName = tlsObj.optString("server_name", server),
                                    publicKey = realityObj.optString("public_key"),
                                    shortId = realityObj.optString("short_id")
                                )
                            } else {
                                SecurityConfig.Tls(serverName = tlsObj.optString("server_name", server))
                            }
                        } else {
                            SecurityConfig.None
                        }
                        ProxyNodeConfig.Vless(tag, server, port, uuid, flow, security)
                    }
                    "vmess" -> {
                        val uuid = obj.optString("uuid")
                        val alterId = obj.optInt("alter_id", 0)
                        val sec = obj.optString("security", "auto")
                        ProxyNodeConfig.Vmess(tag, server, port, uuid, alterId, sec)
                    }
                    "hysteria2" -> {
                        val password = obj.optString("password")
                        ProxyNodeConfig.Hysteria2(tag, server, port, password)
                    }
                    "tuic" -> {
                        val uuid = obj.optString("uuid")
                        val password = obj.optString("password")
                        ProxyNodeConfig.Tuic(tag, server, port, uuid, password)
                    }
                    "trojan" -> {
                        val password = obj.optString("password")
                        ProxyNodeConfig.Trojan(tag, server, port, password)
                    }
                    "shadowsocks" -> {
                        val method = obj.optString("method", "aes-256-gcm")
                        val password = obj.optString("password")
                        ProxyNodeConfig.Shadowsocks(tag, server, port, method, password)
                    }
                    else -> null
                }

                if (node != null) {
                    results.add(node)
                }
            }
        } catch (_: Exception) {}
        return results
    }

    fun decodeBase64Safe(input: String): String {
        val clean = input.replace("\r", "").replace("\n", "").replace(" ", "").trim()
        if (clean.isEmpty()) return input

        val flagsList = listOf(
            Base64.DEFAULT,
            Base64.URL_SAFE,
            Base64.NO_PADDING or Base64.DEFAULT,
            Base64.NO_PADDING or Base64.URL_SAFE,
            Base64.NO_WRAP
        )

        for (flag in flagsList) {
            try {
                val bytes = Base64.decode(clean, flag)
                val str = String(bytes, StandardCharsets.UTF_8)
                if (str.isNotBlank() && (str.contains("://") || str.contains("\"") || str.contains(":") || str.contains("proxies:"))) {
                    return str
                }
            } catch (_: Exception) {}
        }

        // Add padding if missing
        val mod = clean.length % 4
        if (mod != 0) {
            val padded = clean + "=".repeat(4 - mod)
            try {
                val bytes = Base64.decode(padded, Base64.DEFAULT)
                return String(bytes, StandardCharsets.UTF_8)
            } catch (_: Exception) {}
        }

        return input
    }

    private fun parseVless(uriStr: String): ProxyNodeConfig.Vless {
        val withoutScheme = uriStr.substringAfter("://")
        val tag = decodeFragment(withoutScheme.substringAfter("#", ""), "")
        val mainPart = withoutScheme.substringBefore("#")
        
        val userInfo = mainPart.substringBefore("@")
        val hostPortAndQuery = mainPart.substringAfter("@")
        val hostPort = hostPortAndQuery.substringBefore("?").removeSuffix("/")
        val queryString = if (hostPortAndQuery.contains("?")) hostPortAndQuery.substringAfter("?") else ""
        
        val host = if (hostPort.contains(":")) hostPort.substringBefore(":") else hostPort
        val port = if (hostPort.contains(":")) hostPort.substringAfter(":").toIntOrNull() ?: 443 else 443
        val queryParams = parseQueryParams(queryString)

        val hostHeader = queryParams["host"] ?: ""
        val securityParam = queryParams["security"] ?: "none"
        val flow = queryParams["flow"]
        val sni = queryParams["sni"] ?: (if (hostHeader.isNotBlank()) hostHeader else host)
        val pbk = queryParams["pbk"] ?: ""
        val sid = queryParams["sid"] ?: ""

        val security = when (securityParam.lowercase()) {
            "reality" -> SecurityConfig.Reality(
                serverName = sni,
                publicKey = pbk,
                shortId = sid
            )
            "tls" -> SecurityConfig.Tls(serverName = sni)
            else -> SecurityConfig.None
        }

        val typeParam = queryParams["type"] ?: "tcp"
        val rawPath = queryParams["path"] ?: "/"
        val path = try { URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name()) } catch (_: Exception) { rawPath }
        val serviceName = queryParams["serviceName"] ?: ""

        val transport = when (typeParam.lowercase()) {
            "ws" -> TransportConfig.WebSocket(
                path = if (path.startsWith("/")) path else "/$path",
                headers = if (hostHeader.isNotBlank()) mapOf("Host" to hostHeader) else emptyMap()
            )
            "grpc" -> TransportConfig.Grpc(serviceName = serviceName)
            "httpupgrade" -> TransportConfig.HttpUpgrade(path = path, host = hostHeader.ifEmpty { null })
            else -> TransportConfig.Tcp
        }

        val nodeName = if (tag.isNotBlank()) tag else "$host:$port"

        return ProxyNodeConfig.Vless(
            nodeTag = nodeName,
            serverHost = host,
            port = port,
            uuid = userInfo,
            flow = flow,
            security = security,
            transport = transport
        )
    }

    private fun parseVmess(uriStr: String): ProxyNodeConfig.Vmess {
        val base64Content = uriStr.substringAfter("vmess://").substringBefore("#")
        val jsonStr = decodeBase64Safe(base64Content)
        val json = JSONObject(jsonStr)

        val host = json.optString("add", "127.0.0.1")
        val port = json.optInt("port", 443)
        val uuid = json.optString("id", "")
        val aid = json.optInt("aid", 0)
        val net = json.optString("net", "tcp").lowercase()
        val type = json.optString("type", "none")
        val hostHeader = json.optString("host", "")
        val path = json.optString("path", "/")
        val tls = json.optString("tls", "")
        val sni = json.optString("sni", hostHeader.ifEmpty { host })
        val ps = json.optString("ps", "$host:$port")

        val transport = when (net) {
            "ws" -> TransportConfig.WebSocket(path = path, headers = if (hostHeader.isNotEmpty()) mapOf("Host" to hostHeader) else emptyMap())
            "grpc" -> TransportConfig.Grpc(serviceName = path)
            "httpupgrade" -> TransportConfig.HttpUpgrade(host = hostHeader, path = path)
            else -> TransportConfig.Tcp
        }

        val security = if (tls.equals("tls", ignoreCase = true)) {
            SecurityConfig.Tls(serverName = sni.ifEmpty { host })
        } else {
            SecurityConfig.Tls()
        }

        return ProxyNodeConfig.Vmess(
            nodeTag = ps.ifEmpty { "$host:$port" },
            serverHost = host,
            port = port,
            uuid = uuid,
            alterId = aid,
            security = type.ifEmpty { "auto" },
            transport = transport,
            tlsConfig = security
        )
    }

    private fun parseTrojan(uriStr: String): ProxyNodeConfig.Trojan {
        val withoutScheme = uriStr.substringAfter("://")
        val tag = decodeFragment(withoutScheme.substringAfter("#", ""), "")
        val mainPart = withoutScheme.substringBefore("#")

        val password = mainPart.substringBefore("@")
        val hostPortAndQuery = mainPart.substringAfter("@")
        val hostPort = hostPortAndQuery.substringBefore("?").removeSuffix("/")
        val queryString = if (hostPortAndQuery.contains("?")) hostPortAndQuery.substringAfter("?") else ""

        val host = if (hostPort.contains(":")) hostPort.substringBefore(":") else hostPort
        val port = if (hostPort.contains(":")) hostPort.substringAfter(":").toIntOrNull() ?: 443 else 443
        val queryParams = parseQueryParams(queryString)
        val sni = queryParams["sni"] ?: host
        val typeParam = queryParams["type"] ?: "tcp"
        val path = queryParams["path"] ?: "/"
        val serviceName = queryParams["serviceName"] ?: ""

        val transport = when (typeParam.lowercase()) {
            "ws" -> TransportConfig.WebSocket(path = path)
            "grpc" -> TransportConfig.Grpc(serviceName = serviceName)
            else -> TransportConfig.Tcp
        }

        val nodeName = if (tag.isNotBlank()) tag else "$host:$port"

        return ProxyNodeConfig.Trojan(
            nodeTag = nodeName,
            serverHost = host,
            port = port,
            password = password,
            security = SecurityConfig.Tls(serverName = sni),
            transport = transport
        )
    }

    private fun parseHysteria2(uriStr: String): ProxyNodeConfig.Hysteria2 {
        val withoutScheme = uriStr.substringAfter("://")
        val tag = decodeFragment(withoutScheme.substringAfter("#", ""), "")
        val mainPart = withoutScheme.substringBefore("#")

        val password = mainPart.substringBefore("@")
        val hostPortAndQuery = mainPart.substringAfter("@")
        val hostPort = hostPortAndQuery.substringBefore("?").removeSuffix("/")
        val queryString = if (hostPortAndQuery.contains("?")) hostPortAndQuery.substringAfter("?") else ""

        val host = if (hostPort.contains(":")) hostPort.substringBefore(":") else hostPort
        val port = if (hostPort.contains(":")) hostPort.substringAfter(":").toIntOrNull() ?: 443 else 443
        val queryParams = parseQueryParams(queryString)
        val sni = queryParams["sni"] ?: host
        val obfs = queryParams["obfs"]
        val obfsPassword = queryParams["obfs-password"]

        val nodeName = if (tag.isNotBlank()) tag else "$host:$port"

        return ProxyNodeConfig.Hysteria2(
            nodeTag = nodeName,
            serverHost = host,
            port = port,
            password = password,
            obfsType = obfs,
            obfsPassword = obfsPassword,
            security = SecurityConfig.Tls(serverName = sni)
        )
    }

    private fun parseTuic(uriStr: String): ProxyNodeConfig.Tuic {
        val withoutScheme = uriStr.substringAfter("://")
        val tag = decodeFragment(withoutScheme.substringAfter("#", ""), "")
        val mainPart = withoutScheme.substringBefore("#")

        val userInfo = mainPart.substringBefore("@")
        val parts = userInfo.split(":")
        val uuid = parts[0]
        val password = if (parts.size > 1) parts[1] else ""

        val hostPortAndQuery = mainPart.substringAfter("@")
        val hostPort = hostPortAndQuery.substringBefore("?").removeSuffix("/")
        val queryString = if (hostPortAndQuery.contains("?")) hostPortAndQuery.substringAfter("?") else ""

        val host = if (hostPort.contains(":")) hostPort.substringBefore(":") else hostPort
        val port = if (hostPort.contains(":")) hostPort.substringAfter(":").toIntOrNull() ?: 443 else 443
        val queryParams = parseQueryParams(queryString)
        val sni = queryParams["sni"] ?: host
        val cc = queryParams["congestion_control"] ?: "bbr"
        val mode = queryParams["udp_relay_mode"] ?: "native"

        val nodeName = if (tag.isNotBlank()) tag else "$host:$port"

        return ProxyNodeConfig.Tuic(
            nodeTag = nodeName,
            serverHost = host,
            port = port,
            uuid = uuid,
            password = password,
            congestionControl = cc,
            udpRelayMode = mode,
            security = SecurityConfig.Tls(serverName = sni)
        )
    }

    private fun parseShadowsocks(uriStr: String): ProxyNodeConfig.Shadowsocks {
        val withoutScheme = uriStr.substringAfter("://")
        val tag = decodeFragment(withoutScheme.substringAfter("#", "Shadowsocks"), "Shadowsocks")
        val mainPart = withoutScheme.substringBefore("#")

        val host: String
        val port: Int
        val method: String
        val password: String

        if (mainPart.contains("@")) {
            val userPart = mainPart.substringBefore("@")
            val hostPort = mainPart.substringAfter("@").removeSuffix("/")
            val decodedUser = decodeBase64Safe(userPart)
            if (decodedUser.contains(":")) {
                method = decodedUser.substringBefore(":")
                password = decodedUser.substringAfter(":")
            } else {
                method = "aes-256-gcm"
                password = decodedUser
            }
            host = if (hostPort.contains(":")) hostPort.substringBefore(":") else hostPort
            port = if (hostPort.contains(":")) hostPort.substringAfter(":").toIntOrNull() ?: 8388 else 8388
        } else {
            val decoded = decodeBase64Safe(mainPart)
            val parts = decoded.split("@")
            if (parts.size == 2) {
                val methodPass = parts[0].split(":", limit = 2)
                method = methodPass[0]
                password = methodPass.getOrNull(1) ?: ""
                val hostPort = parts[1].split(":", limit = 2)
                host = hostPort[0]
                port = hostPort.getOrNull(1)?.toIntOrNull() ?: 8388
            } else {
                method = "aes-256-gcm"
                password = ""
                host = "127.0.0.1"
                port = 8388
            }
        }

        return ProxyNodeConfig.Shadowsocks(
            nodeTag = tag,
            serverHost = host,
            port = port,
            method = method,
            password = password
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        query.split("&").forEach { param ->
            val pair = param.split("=", limit = 2)
            if (pair.isNotEmpty()) {
                val key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name())
                val value = if (pair.size > 1) URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()) else ""
                map[key] = value
            }
        }
        return map
    }

    private fun decodeFragment(fragment: String?, default: String): String {
        if (fragment.isNullOrEmpty()) return default
        return try {
            URLDecoder.decode(fragment, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            fragment
        }
    }
}