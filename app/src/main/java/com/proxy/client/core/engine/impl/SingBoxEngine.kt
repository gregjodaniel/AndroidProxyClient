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

/**
 * sing-box内核的ProxyEngine实现,通过反射调用 core/core_bridge.go
 * 经gomobile编译出的 libcore.aar。
 *
 * 为什么用反射而不是直接import corebridge包:
 * CI里 gomobile bind 那一步是允许失败的(见 .github/workflows/build.yml),
 * 如果直接写 import corebridge.Corebridge,一旦aar没编译出来,
 * 整个Gradle编译都会失败,而不只是内核功能不可用。反射的好处是
 * "aar不存在"这件事可以在运行时优雅降级成一个明确的错误提示,
 * 而不是让整个App都编译不了。
 *
 * 【和上一版最大的区别】上一版这里只是反射拿到了Method对象就结束了,
 * 从来没有调用.invoke(),所以不管aar在不在、编译对不对,
 * 都会直接跳到"标记为RUNNING+开始生成假流量数字"这一步。
 * 现在改成:反射拿到方法后必须真正调用它,拿不到或调用失败
 * 就明确抛异常、把状态设成ERROR,不再假装成功。
 */
class SingBoxEngine(
    private val protector: SocketProtector
) : ProxyEngine {

    private val _state = MutableStateFlow(EngineState.STOPPED)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    override val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    private val isRunning = AtomicBoolean(false)

    // 反射拿到的原生引擎实例(对应Go的*EngineWrapper)和它的Stop方法,
    // 保存下来是为了stop()的时候能调用到同一个实例上
    private var nativeEngineInstance: Any? = null
    private var nativeStopMethod: Method? = null

    companion object {
        private const val TAG = "SingBoxEngine"

        // gomobile bind 对Go包 "corebridge" 生成绑定时,包级别的导出函数
        // 会变成一个和包同名的Java类(首字母大写)上的静态方法,
        // 方法名保留Go那边的大小写(NewEngine,不是newEngine——
        // 上一版这里写的是小写"newEngine",如果真的编出了aar,
        // 这个大小写不匹配会直接导致反射失败,是另一个隐藏的bug)。
        private const val BRIDGE_CLASS = "corebridge.Corebridge"
        private const val PROTECTOR_INTERFACE = "corebridge.SocketProtector"
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

    /**
     * 真正通过反射调用原生库。
     * 找不到class(aar没放进app/libs/) → 明确抛 EngineException,
     * 不再静默降级成"标记为已连接"。
     */
    private fun startNativeCore(configJson: String) {
        val protectorInterfaceClass: Class<*>
        val bridgeClass: Class<*>
        try {
            protectorInterfaceClass = Class.forName(PROTECTOR_INTERFACE)
            bridgeClass = Class.forName(BRIDGE_CLASS)
        } catch (e: ClassNotFoundException) {
            throw EngineException(
                "未找到原生内核库(corebridge)。请确认 app/libs/libcore.aar 是否存在," +
                "以及是否由 core/ 目录下的Go代码通过 gomobile bind 正确编译生成。" +
                "参考 core/build_core.sh。",
                e
            )
        }

        // 用动态代理把Kotlin的protector.protect(fd)桥接成
        // gomobile生成的Java接口 corebridge.SocketProtector。
        // 这样不需要在编译期就依赖这个接口类型,和上面反射拿Class是同一套思路。
        val protectorProxy = Proxy.newProxyInstance(
            protectorInterfaceClass.classLoader,
            arrayOf(protectorInterfaceClass),
            InvocationHandler { _, method: Method, args: Array<out Any>? ->
                if (method.name.equals("Protect", ignoreCase = true) && args != null && args.isNotEmpty()) {
                    val fd = (args[0] as Number).toInt()
                    protector.protect(fd)
                } else {
                    null
                }
            }
        )

        val newEngineMethod = bridgeClass.getMethod(METHOD_NEW_ENGINE, protectorInterfaceClass)
        val engineInstance = newEngineMethod.invoke(null, protectorProxy)
            ?: throw EngineException("$METHOD_NEW_ENGINE 返回了null,原生库可能初始化失败")

        val startMethod = engineInstance.javaClass.getMethod(METHOD_START, String::class.java)
        // Go侧Start(configJSON string) error —— gomobile会把非nil的error
        // 转换成Java异常抛出,这里的invoke()失败会自然被下面的catch接住
        startMethod.invoke(engineInstance, configJson)

        nativeEngineInstance = engineInstance
        nativeStopMethod = engineInstance.javaClass.getMethod(METHOD_STOP)

        Log.i(TAG, "原生sing-box内核启动成功")

        // 注意:实时流量统计(TrafficStats)目前还没有接。
        // core/stats_collector.go 里的 TrafficMonitor 是一套可用的回调结构,
        // 但它依赖外部调用 RecordUplink/RecordDownlink 喂入真实字节数,
        // 而"真实字节数从哪来"取决于sing-box内部的流量统计API(通常是它的
        // Clash API/V2Ray API那一套),这部分还没有对接,所以trafficStats
        // 目前会一直是0,不会再显示假数据了。等这部分做完再更新这里。
    }
}
