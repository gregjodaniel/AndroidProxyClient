package com.proxy.client

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class ProxyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            val bridgeClass = Class.forName("corebridge.Corebridge")
            val method = bridgeClass.getMethod("initCrashLogger", String::class.java)
            val logFile = File(filesDir, "core_panic.log").absolutePath
            method.invoke(null, logFile)
            Log.i("ProxyApp", "已初始化内核崩溃日志重定向: $logFile")
        } catch (t: Throwable) {
            Log.w("ProxyApp", "初始化内核崩溃日志失败: ${t.message}")
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()
                Log.e("ProxyApp", "全局未捕获崩溃异常: $stackTrace")
                
                val prefs = getSharedPreferences("proxy_client_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("KEY_LAST_CRASH", stackTrace).commit()
            } catch (e: Exception) {
                Log.e("ProxyApp", "保存崩溃日志失败", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}