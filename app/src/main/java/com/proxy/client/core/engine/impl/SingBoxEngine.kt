package com.proxy.client.core.engine.impl

import android.util.Log
import com.proxy.client.core.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class SingBoxEngine(
    private val protector: SocketProtector,
    private val openTunProvider: () -> Int
) : ProxyEngine {

    private val _state = MutableStateFlow(EngineState.STOPPED)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    override val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private var engineWrapperInstance: Any? = null
    private var nativeStopMethod: Method? = null

    companion object {
        private const val TAG = "SingBoxEngine"
        private const val BRIDGE_CLASS = "corebridge.Corebridge"
        private const val BRIDGE_INTERFACE = "corebridge.PlatformBridge"
    }

    override suspend fun start(configJson: String) = withContext(Dispatchers.IO) {
        if (_state.value == EngineState.RUNNING || _state.value == EngineState.STARTING) return@withContext
        _state.value = EngineState.STARTING
        try {
            startNativeCore(configJson)
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
            nativeStopMethod?.invoke(engineWrapperInstance)
            engineWrapperInstance = null
            nativeStopMethod = null
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

    private fun startNativeCore(configJson: String) {
        val bridgeInterfaceClass: Class<*>
        val bridgeClass: Class<*>
        try {
            bridgeInterfaceClass = Class.forName(BRIDGE_INTERFACE)
            bridgeClass = Class.forName(BRIDGE_CLASS)
        } catch (e: ClassNotFoundException) {
            throw EngineException(
                "未找到原生内核库(corebridge)。请确认 app/libs/libcore.aar 是否正确打包。",
                e
            )
        }

        val bridgeProxy = Proxy.newProxyInstance(
            bridgeInterfaceClass.classLoader,
            arrayOf(bridgeInterfaceClass),
            InvocationHandler { _, method: Method, args: Array<out Any>? ->
                val name = method.name.lowercase()
                Log.d(TAG, "PlatformBridge invoked: ${method.name}")
                when {
                    name.contains("opentun") -> {
                        val fd = openTunProvider()
                        Log.d(TAG, "PlatformBridge openTun returned fd: $fd")
                        fd
                    }
                    name.contains("protect") -> {
                        val fd = (args!![0] as Number).toInt()
                        val res = protector.protect(fd)
                        Log.d(TAG, "PlatformBridge protect fd $fd result: $res")
                        res
                    }
                    else -> null
                }
            }
        )

        val newEngineMethod = findMethod(bridgeClass, "newEngine", bridgeInterfaceClass)
            ?: throw EngineException("在 $BRIDGE_CLASS 中未找到 newEngine 方法")
        val instance = newEngineMethod.invoke(null, bridgeProxy)
            ?: throw EngineException("newEngine 返回为 null")

        val startMethod = findMethod(instance.javaClass, "start", String::class.java)
            ?: throw EngineException("在 EngineWrapper 中未找到 start 方法")
        startMethod.invoke(instance, configJson)

        engineWrapperInstance = instance
        nativeStopMethod = findMethod(instance.javaClass, "stop")

        Log.i(TAG, "SingBox SFA 官方架构内核启动成功")
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
                var match = true
                for (i in paramTypes.indices) {
                    if (!paramTypes[i].isAssignableFrom(m.parameterTypes[i])) {
                        match = false
                        break
                    }
                }
                if (match) return m
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