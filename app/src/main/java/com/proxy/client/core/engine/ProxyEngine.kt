package com.proxy.client.core.engine

import kotlinx.coroutines.flow.StateFlow

enum class EngineState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

data class TrafficStats(
    val uplinkSpeed: Long = 0L,
    val downlinkSpeed: Long = 0L,
    val totalUplink: Long = 0L,
    val totalDownlink: Long = 0L
)

interface ProxyEngine {
    val state: StateFlow<EngineState>
    val trafficStats: StateFlow<TrafficStats>

    @Throws(EngineException::class)
    suspend fun start(configJson: String)

    suspend fun stop()

    suspend fun reloadConfig(configJson: String)
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
