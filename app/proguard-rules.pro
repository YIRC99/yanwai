# DexKit 靠运行时读 dex，不能被混淆掉
-keep class org.luckypray.dexkit.** { *; }
-keep class com.highcapable.yukihookapi.** { *; }

# 入口类由 Xposed 框架按字符串反射加载，混淆后必须保留原始名字
# （java_init.list / xposed_init 里写的是全限定名，改了就找不到）
-keep class dev.jev.wechatmood.xposed.** { *; }

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
