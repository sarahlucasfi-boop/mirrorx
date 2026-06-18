# MirrorX Android ProGuard Rules
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# WebSocket
-keep class com.mirrorx.app.network.** { *; }