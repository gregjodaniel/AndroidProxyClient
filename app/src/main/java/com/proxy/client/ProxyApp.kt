package com.proxy.client

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

class ProxyApp : Application() {

    override fun onCreate() {
        super.onCreate()
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