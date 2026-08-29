# 保留 gomobile 生成的桥接类
-keep class corebridge.** { *; }
-keep interface corebridge.** { *; }
-keep class go.** { *; }

# 保留 ProxyEngine 数据模型
-keep class com.proxy.client.core.config.model.** { *; }
-keep class com.proxy.client.core.engine.** { *; }

# 保持 Native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}
