# Xposed 入口类与配置通道必须保留（若未来开启 minify）
-keep class com.example.notificationsinking.MainHook { *; }
-keep class com.example.notificationsinking.ResourceHook { *; }
-keep class com.example.notificationsinking.PrefsProvider { *; }
-keep class com.example.notificationsinking.ConfigStore { *; }

# LSPosed 入口在 assets 里按全类名反射加载，禁止重命名
-keepnames class com.example.notificationsinking.** { *; }
