package com.proxy.client.core.speedtest

import com.proxy.client.core.config.model.ProxyNodeConfig
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

object LatencyTester {

    private const val DEFAULT_TIMEOUT = 2500

    suspend fun testAllNodes(
        nodes: List<ProxyNodeConfig>,
        maxConcurrency: Int = 10
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val semaphore = java.util.concurrent.Semaphore(maxConcurrency)
        
        nodes.map { node ->
            async {
                semaphore.acquire()
                try {
                    val delay = testTcpPing(node.server, node.serverPort, DEFAULT_TIMEOUT)
                    node.tag to delay
                } finally {
                    semaphore.release()
                }
            }
        }.awaitAll().toMap()
    }

    private fun testTcpPing(host: String, port: Int, timeoutMs: Int): Int {
        val start = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                (System.currentTimeMillis() - start).toInt()
            }
        } catch (e: Exception) {
            -1
        }
    }
}
