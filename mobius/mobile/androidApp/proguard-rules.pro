# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class ** { *; }

# Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# Compose fallback; default R8 rules cover Compose runtime.
-dontwarn androidx.compose.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Optional compile-only annotations/bindings referenced by dependencies.
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# App models used by JSON decoding and cross-module serialization.
-keep class com.mobius.momo.domain.** { *; }
-keep class com.mobius.momo.data.** { *; }

# 极光推送（JPush / JCore）——官方混淆规则。
-dontoptimize
-dontpreverify
-dontwarn cn.jpush.**
-dontwarn cn.jiguang.**
-keep class cn.jpush.** { *; }
-keep class * extends cn.jpush.android.api.JPushMessageReceiver { *; }
-keep class cn.jiguang.** { *; }
# 兼容厂商通道（聚合推送）相关类。
-keep class com.huawei.** { *; }
-keep class com.xiaomi.** { *; }
-keep class com.vivo.** { *; }
-keep class com.coloros.** { *; }
-keep class com.meizu.** { *; }
-dontnote com.huawei.**
-dontwarn com.huawei.**
-dontnote com.xiaomi.**
-dontwarn com.xiaomi.**
-dontnote com.vivo.**
-dontwarn com.vivo.**
-dontnote com.coloros.**
-dontwarn com.coloros.**
-dontnote com.meizu.**
-dontwarn com.meizu.**
