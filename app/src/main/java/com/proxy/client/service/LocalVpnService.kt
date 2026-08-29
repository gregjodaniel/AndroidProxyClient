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
        proxyEngine = SingBoxEngine(
            protector = { fd -> this.protect(fd) },
            openTunProvider = {
                val fd = setupVpnInterface() ?: throw RuntimeException("建立TUN接口失败: VpnService.Builder().establish() 返回了null，请检查系统VPN权限")
                fd
            }
        )
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
                    val err = "没有选中的节点(pendingNode为空),无法启动VPN"
                    Log.e(TAG, err)
                    _lastError.value = err
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, createNotification("VPN 正在连接..."))

                serviceScope.launch {
                    try {
                        _lastError.value = null
                        val configJson = SingBoxConfigBuilder()
                            .setRouteMode(pendingRouteMode)
                            .addProxyNode(node)
                            .build(node.tag)

                        Log.d(TAG, "生成的 SingBox 配置 JSON:\n$configJson")
                        proxyEngine.start(configJson)
                    } catch (e: Exception) {
                        val errMsg = e.message ?: e.toString()
                        Log.e(TAG, "启动VPN失败: $errMsg", e)
                        _lastError.value = errMsg
                        stopVpn()
                    }
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

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

        var pendingNode: ProxyNodeConfig? = null
        var pendingRouteMode: RouteMode = RouteMode.RULE

        private val _vpnState = MutableStateFlow(EngineState.STOPPED)
        val vpnState: StateFlow<EngineState> = _vpnState.asStateFlow()

        private val _vpnStats = MutableStateFlow(TrafficStats())
        val vpnStats: StateFlow<TrafficStats> = _vpnStats.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

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