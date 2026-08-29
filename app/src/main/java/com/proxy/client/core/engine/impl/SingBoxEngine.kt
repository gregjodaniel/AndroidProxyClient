package com.proxy.client.core.engine.impl

import android.util.Log
import com.proxy.client.core.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class SingBoxEngine(
    private val tunFdProvider: () -> Int
) : ProxyEngine {

    private val _state = MutableStateFlow(EngineState.STOPPED)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    override val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    private val isRunning = AtomicBoolean(false)

    companion object {
        private const val TAG = "SingBoxEngine"
        private const val BRIDGE_CLASS = "corebridge.Corebridge"
    }

    override suspend fun start(configJson: String) = withContext(Dispatchers.IO) {
        if (_state.value == EngineState.RUNNING || _state.value == EngineState.STARTING) return@withContext
        _state.value = EngineState.STARTING
        try {
            val tunFd = tunFdProvider()
            startNativeCore(configJson, tunFd)
            isRunning.set(true)
            _state.value = EngineState.RUNNING
        } catch (t: Throwable) {
            val errMsg = getRootErrorMessage(t)
            Log.e(TAG, "启动内核失败: $errMsg", t)
            _state.value = EngineState.ERROR
            isRunning.set(false)
            throw EngineException(errMsg, t)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (_state.value == EngineState.STOPPED || _state.value == EngineState.STOPPING) return@withContext
        _state.value = EngineState.STOPPING
        try {
            isRunning.set(false)
            stopNativeCore()
            _state.value = EngineState.STOPPED
            _trafficStats.value = TrafficStats()
        } catch (t: Throwable) {
            val errMsg = getRootErrorMessage(t)
            Log.e(TAG, "停止内核失败: $errMsg", t)
            _state.value = EngineState.ERROR
            throw EngineException(errMsg, t)
        }
    }

    override suspend fun reloadConfig(configJson: String) = withContext(Dispatchers.IO) {
        stop()
        start(configJson)
    }

    private fun startNativeCore(configJson: String, tunFd: Int) {
        val bridgeClass: Class<*>
        try {
            bridgeClass = Class.forName(BRIDGE_CLASS)
        } catch (e: ClassNotFoundException) {
            throw EngineException(
                "未找到原生内核库(corebridge)。请确认 app/libs/libcore.aar 是否正确打包。",
                e
            )
        }

        val startMethod = findMethod(bridgeClass, "startProxy", String::class.java, Long::class.javaPrimitiveType ?: Long::class.java)
            ?: findMethod(bridgeClass, "startProxy", String::class.java, Int::class.javaPrimitiveType ?: Int::class.java)
            ?: throw EngineException("在 $BRIDGE_CLASS 中未找到 startProxy 方法")

        if (startMethod.parameterTypes[1] == Long::class.javaPrimitiveType || startMethod.parameterTypes[1] == Long::class.java) {
            startMethod.invoke(null, configJson, tunFd.toLong())
        } else {
            startMethod.invoke(null, configJson, tunFd)
        }

        Log.i(TAG, "原生 SingBox + tun2socks 启动成功，TUN FD: $tunFd")
    }

    private fun stopNativeCore() {
        try {
            val bridgeClass = Class.forName(BRIDGE_CLASS)
            val stopMethod = findMethod(bridgeClass, "stopProxy")
            stopMethod?.invoke(null)
        } catch (e: Exception) {
            Log.w(TAG, "停止原生内核异常", e)
        }
    }

    private fun findMethod(clazz: Class<*>, baseName: String, vararg paramTypes: Class<*>): Method? {
        val candidates = listOf(
            baseName,
            baseName.replaceFirstChar { it.lowercase() },
            baseName.replaceFirstChar { it.uppercase() }
        ).distinct()

        for (name in candidates) {
            try {
                return clazz.getMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {}
        }

        for (m in clazz.methods) {
            if (m.name.equals(baseName, ignoreCase = true) && m.parameterTypes.size == paramTypes.size) {
                return m
            }
        }
        return null
    }

    private fun getRootErrorMessage(t: Throwable): String {
        var current: Throwable? = t
        while (current is InvocationTargetException || (current?.cause != null && current !is EngineException)) {
            current = if (current is InvocationTargetException) {
                current.targetException ?: current.cause
            } else {
                current.cause
            }
        }
        return current?.message ?: current?.toString() ?: t.toString()
    }
}