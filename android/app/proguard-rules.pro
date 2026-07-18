# Sprint 21 e2ee-ap-v2 proguard rules
# R8 full mode is aggressive: manifest-only referenced classes can be removed.
# Explicitly keep wirebare-kernel service + supporting classes.

# Keep SimpleWireBareProxyService (manifest-referenced)
-keep class com.opene2ee.e2ee_ap_v2.vpn.service.SimpleWireBareProxyService { *; }

# Keep WireBareProxyService (parent abstract class, referenced via inheritance)
-keep class com.opene2ee.e2ee_ap_v2.vpn.service.WireBareProxyService { *; }

# Keep VpnPrepareActivity (uses ActivityResultContracts)
-keep class com.opene2ee.e2ee_ap_v2.vpn.common.VpnPrepareActivity { *; }

# Keep the entire vpn package (defense-in-depth — these classes are wired via
# reflection / class names that R8 cannot see statically)
-keep class com.opene2ee.e2ee_ap_v2.vpn.** { *; }

# Keep wirebare-kernel intent action strings (used in manifest)
-keepclassmembers class ** {
    public static final java.lang.String WIREBARE_ACTION_PROXY_VPN_START;
    public static final java.lang.String WIREBARE_ACTION_PROXY_VPN_STOP;
}

# BouncyCastle (used for TLS)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Brotli
-keep class org.brotli.dec.** { *; }

# kotlinx-coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Standard Android keep rules
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Activity
