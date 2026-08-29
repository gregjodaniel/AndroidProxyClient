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

    private val isRunning = AtomicBoolean(false)

    private var nativeEngineInstance: Any? = null
    private var nativeStopMethod: Method? = null

    companion object {
        private const val TAG = "SingBoxEngine"
        private const val BRIDGE_CLASS = "corebridge.Corebridge"
        private const val BRIDGE_INTERFACE = "corebridge.PlatformBridge"
        private const val METHOD_NEW_ENGINE = "NewEngine"
        private const val METHOD_START = "Start"
        private const val METHOD_STOP = "Stop"
    }

    override suspend fun start(configJson: String) = withContext(Dispatchers.IO) {
        if (_state.value == EngineState.RUNNING || _state.value == EngineState.STARTING) return@withContext
        _state.value = EngineState.STARTING
        try {
            startNativeCore(configJson)
            isRunning.set(true)
            _state.value = EngineState.RUNNING
        } catch (t: Throwable) {
            _state.value = EngineState.ERROR
            isRunning.set(false)
            throw EngineException("启动sing-box内核失败: ${t.message}", t)
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
            _state.value = EngineState.ERROR
            throw EngineException("停止sing-box内核失败: ${t.message}", t)
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
                "未找到原生内核库(corebridge)。请确认 app/libs/libcore.aar 是否存在。",
                e
            )
        }

        val bridgeProxy = Proxy.newProxyInstance(
            bridgeInterfaceClass.classLoader,
            arrayOf(bridgeInterfaceClass),
            InvocationHandler { _, method: Method, args: Array<out Any>? ->
                val name = method.name.lowercase()
                when {
                    name.contains("opentun") -> {
                        openTunProvider()
                    }
                    name.contains("protect") -> {
                        val fd = (args!![0] as Number).toInt()
                        protector.protect(fd)
                    }
                    else -> null
                }
            }
        )

        val newEngineMethod = bridgeClass.getMethod(METHOD_NEW_ENGINE, bridgeInterfaceClass)
        val engineInstance = newEngineMethod.invoke(null, bridgeProxy)
            ?: throw EngineException("$METHOD_NEW_ENGINE 返回了null,原生库可能初始化失败")

        val startMethod = engineInstance.javaClass.getMethod(METHOD_START, String::class.java)
        startMethod.invoke(engineInstance, configJson)

        nativeEngineInstance = engineInstance
        nativeStopMethod = engineInstance.javaClass.getMethod(METHOD_STOP)

        Log.i(TAG, "原生sing-box内核启动成功")
    }
}