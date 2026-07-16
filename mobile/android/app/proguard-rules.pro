# Sprint 11.0Z — user-space TCP/IP stack via Netty.
# Netty 4.1.x's all-in-one bundle (`netty-all`) ships
# `META-INF/INDEX.LIST` for every sub-module and also
# references several logger frameworks (Log4J, Log4J2,
# Slf4J) via its `InternalLoggerFactory`. The project
# does NOT use these loggers (it uses `android.util.Log`
# through `TcpForwarder`/`UdpForwarder` and the
# `NettyChannelClient` skeleton that only parses IP/TCP/
# UDP headers). Without these `-dontwarn` rules, R8 in
# release mode fails with:
#   "Missing class org.apache.log4j.Level
#    (referenced from: void io.netty.util.internal.logging.
#     Log4JLogger.debug(...))"
# Adding `-dontwarn` tells R8: "we know this class is
# missing; it is referenced but never called; do not
# abort the build". This is safe because Netty's
# InternalLoggerFactory falls back to `java.util.logging`
# (JUL) at runtime if Log4J/Slf4J are absent.
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**

# Sprint 12.0F+2 — KEEP all android.util.Log calls in our code.
# R8 (release minifier) sometimes removes Log.* calls because
# the return value is unused and the call has no obvious side
# effect on the program's data flow. The side effect (writing
# to logcat) is what we depend on for debug + audit, so we
# must prevent R8 from removing them.
-keepclassmembers,allowobfuscation class * {
    *** Log*(...);
}

# Sprint 12.0F+2 — KEEP all String TAG constants (debug
# breadcrumb tokens).
-keepclassmembers,allowobfuscation class * {
    public static final java.lang.String TAG;
}

# Sprint 12.0F+2 — KEEP @androidx.annotation.Keep annotated
# members.
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}


# ───── Sprint 14 — VpnService keep rules ─────
# (Sprint 13.0 + 12.0F+9 dersleri)

# 1. Tüm Log.* çağrıları (R8 release build'de strip edebilir)
-keepclassmembers,allowobfuscation class * {
    *** Log*(...);
}

# 2. public static final String TAG field'ları
-keepclassmembers,allowobfuscation class * {
    public static final java.lang.String TAG;
}

# 3. @Keep annotation ve annotated sınıflar
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# 4. VpnService companion object'leri (R8 strip etmesin)
-keepclassmembers class com.opene2ee.opene2ee.vpn.** {
    public static ** Companion;
    public static ** INSTANCE;
}

# 5. OpenE2eeVpnService native bridge (JNI yok ama yine de)
-keep class com.opene2ee.opene2ee.vpn.OpenE2eeVpnService { *; }

# 6. Tüm vpn/ paketi için Keep (defense-in-depth)
-keep class com.opene2ee.opene2ee.vpn.** { *; }


# ───── Sprint 16 — wirebare-tarzı eklemeler ─────

# 7. wirebare reflective/reflect olarak kullanılan data class'lar
# (Session, Port, IpAddress, IntIPv6, Protocol, IPVersion)
-keep class com.opene2ee.opene2ee.vpn.net.Session { *; }
-keep class com.opene2ee.opene2ee.vpn.net.TcpSession { *; }
-keep class com.opene2ee.opene2ee.vpn.net.UdpSession { *; }
-keep class com.opene2ee.opene2ee.vpn.net.Port { *; }
-keep class com.opene2ee.opene2ee.vpn.net.IpAddress { *; }
-keep class com.opene2ee.opene2ee.vpn.net.IntIPv6 { *; }
-keep class com.opene2ee.opene2ee.vpn.net.Protocol { *; }
-keep class com.opene2ee.opene2ee.vpn.net.IPVersion { *; }

# 8. kotlinx.coroutines (kotlinx-coroutines-android)
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# 9. ServiceCompat (Sprint 14 zaten vardı, korunur)
-keep class androidx.core.app.ServiceCompat { *; }
