package com.proxy.client.core.engine.impl

import android.util.Log
import com.proxy.client.core.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.InvocationHandler
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

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    private val isRunning = AtomicBoolean(false)

    private var nativeEngineInstance: Any? = null
    private var nativeStopMethod: Method? = null

    companion object {
        private const val TAG = "SingBoxEngine"
        private const val BRIDGE_CLASS = "corebridge.Corebridge"
        private const val BRIDGE_INTERFACE = "corebridge.PlatformBridge"
    }

    override suspend fun start(configJson: String) = withContext(Dispatchers.IO) {
        if (_state.value == EngineState.RUNNING || _state.value == EngineState.STARTING) return@withContext
        _state.value = EngineState.STARTING
        _lastErrorMessage.value = null
        try {
            startNativeCore(configJson)
            isRunning.set(true)
            _state.value = EngineState.RUNNING
        } catch (t: Throwable) {
            val errMsg = t.message ?: t.toString()
            Log.e(TAG, "启动sing-box内核失败: $errMsg", t)
            _lastErrorMessage.value = errMsg
            _state.value = EngineState.ERROR
            isRunning.set(false)
            throw EngineException("启动sing-box内核失败: $errMsg", t)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (_state.value == EngineState.STOPPED || _state.value == EngineState.STOPPING) return@withContext
        _state.value = EngineState.STOPPING
        try {
            isRunning.set(false)
            nativeStopMethod?.invoke(nativeEngineInstance)
            nativeEngineInstance = null
            nativeStopMethod = null
            _state.value = EngineState.STOPPED
            _trafficStats.value = TrafficStats()
        } catch (t: Throwable) {
            val errMsg = t.message ?: t.toString()
            Log.e(TAG, "停止sing-box内核失败: $errMsg", t)
            _lastErrorMessage.value = errMsg
            _state.value = EngineState.ERROR
            throw EngineException("停止sing-box内核失败: $errMsg", t)
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
        val engineInstance = newEngineMethod.invoke(null, bridgeProxy)
            ?: throw EngineException("newEngine 返回了null,原生库可能初始化失败")

        val startMethod = findMethod(engineInstance.javaClass, "start", String::class.java)
        startMethod.invoke(engineInstance, configJson)

        nativeEngineInstance = engineInstance
        nativeStopMethod = findMethod(engineInstance.javaClass, "stop")

        Log.i(TAG, "原生sing-box内核启动成功")
    }

    private fun findMethod(clazz: Class<*>, baseName: String, vararg paramTypes: Class<*>): Method {
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

        val available = clazz.methods.map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
        throw NoSuchMethodException("在类 ${clazz.name} 中找不到方法 $baseName(${paramTypes.joinToString { it.simpleName }})。可用方法: $available")
    }
}