package com.proxy.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.proxy.client.MainActivity
import com.proxy.client.core.config.RouteMode
import com.proxy.client.core.config.SingBoxConfigBuilder
import com.proxy.client.core.config.model.ProxyNodeConfig
import com.proxy.client.core.engine.EngineState
import com.proxy.client.core.engine.ProxyEngine
import com.proxy.client.core.engine.TrafficStats
import com.proxy.client.core.engine.impl.SingBoxEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log

class LocalVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var proxyEngine: ProxyEngine

    override fun onCreate() {
        super.onCreate()
        proxyEngine = SingBoxEngine(protector = { fd -> this.protect(fd) })
        serviceScope.launch {
            proxyEngine.state.collect { state ->
                _vpnState.value = state
                when (state) {
                    EngineState.RUNNING -> updateNotification("VPN 已连接 - 保护中")
                    EngineState.STARTING -> updateNotification("VPN 正在启动...")
                    EngineState.STOPPING -> updateNotification("VPN 正在断开...")
                    EngineState.STOPPED -> updateNotification("VPN 已断开")
                    EngineState.ERROR -> updateNotification("VPN 连接异常")
                }
            }
        }
        serviceScope.launch {
            proxyEngine.trafficStats.collect { stats ->
                _vpnStats.value = stats
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val node = pendingNode
                if (node == null) {
                    Log.e(TAG, "没有选中的节点(pendingNode为空),无法启动VPN")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, createNotification("VPN 正在连接..."))

                serviceScope.launch {
                    try {
                        val fd = setupVpnInterface()
                        if (fd == null) {
                            Log.e(TAG, "建立TUN接口失败(builder.establish()返回null),可能是用户拒绝了VPN权限弹窗")
                            stopVpn()
                            return@launch
                        }

                        // 关键修复:build()现在必须传入真实的TUN fd。
                        // 之前的版本在MainActivity里提前拼好了configJson,
                        // 那时候TUN还没建立,自然拿不到fd,只能靠sing-box自己
                        // 的auto_route=true去尝试建TUN——这在非root应用里
                        // 是做不到的,是流量进TUN后没有下文的根本原因。
                        val configJson = SingBoxConfigBuilder()
                            .setRouteMode(pendingRouteMode)
                            .addProxyNode(node)
                            .build(node.tag, fd)

                        proxyEngine.start(configJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "启动VPN失败", e)
                        stopVpn()
                    }
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    /** @return 建立成功返回TUN的文件描述符,失败返回null */
    private fun setupVpnInterface(): Int? {
        if (vpnInterface != null) return vpnInterface?.fd

        val builder = Builder().apply {
            setSession("AndroidProxyClient")
            setMtu(1500)
            addAddress("172.19.0.1", 30)
            addRoute("0.0.0.0", 0)
            addDnsServer("1.1.1.1")
            addDnsServer("8.8.8.8")
            try {
                // 把本App自己排除在VPN路由之外。这样做还有一个好处:
                // sing-box内核运行在本App的进程里,它自己拨号连接代理服务器
                // 产生的socket,因为属于本App的UID,会天然不被TUN再次拦截,
                // 不会形成死循环——这是protect()机制之外的另一层保险,
                // 两者不冲突,同时生效更稳妥。
                addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "addDisallowedApplication失败,继续尝试建立VPN", e)
            }
        }
        vpnInterface = builder.establish()
        return vpnInterface?.fd
    }

    private fun stopVpn() {
        serviceScope.launch {
            try {
                proxyEngine.stop()
            } finally {
                vpnInterface?.close()
                vpnInterface = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        _vpnState.value = EngineState.STOPPED
    }

    private fun createNotification(content: String): Notification {
        val channelId = "vpn_status_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "VPN 运行状态", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocalVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Android Proxy 客户端")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开连接", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    companion object {
        private const val TAG = "LocalVpnService"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.proxy.client.ACTION_START"
        const val ACTION_STOP = "com.proxy.client.ACTION_STOP"

        // 用静态字段传递"选中的节点"和"分流模式"这两个决策结果,
        // 真正的配置JSON要等LocalVpnService建好TUN、拿到fd之后才拼装。
        // (和EXTRA_CONFIG_JSON方案相比的取舍:更简单,但进程被杀会丢失,
        // 后续如果要做"进程重启后自动恢复连接",这里要换成从NodeRepository
        // 的持久化存储里重新读取,而不是依赖内存里的静态变量)
        var pendingNode: ProxyNodeConfig? = null
        var pendingRouteMode: RouteMode = RouteMode.RULE

        private val _vpnState = MutableStateFlow(EngineState.STOPPED)
        val vpnState: StateFlow<EngineState> = _vpnState.asStateFlow()

        private val _vpnStats = MutableStateFlow(TrafficStats())
        val vpnStats: StateFlow<TrafficStats> = _vpnStats.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
