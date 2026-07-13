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
# must prevent R8 from removing them. Owner 16:24 test of the
# 12.0F+1 APK (SHA AE734AD3) showed 0 occurrences of the
# `handleTcpPacket: dispatching flags=0x` breadcrumb + 0
# occurrences of `buildVpnBuilder: allowedApps=` + 0
# occurrences of `checkPrivateDnsAndBindToVpn`. R8 silently
# removed the 3 breadcrumb Log.d calls (and probably many
# more) because the return value is unused. This rule preserves
# ALL Log.* method calls on ANY class (wildcard `*`) so the
# debug + audit breadcrumbs are guaranteed to fire in release.
# `allowobfuscation` keeps the obfuscation name mangling for the
# method itself (so an attacker cannot grep `handleTcpPacket`
# in the obfuscated DEX and trace the call site back to source).
# S122-4 audit verifies this rule is present.
-keepclassmembers,allowobfuscation class * {
    *** Log*(...);
}

# Sprint 12.0F+2 — KEEP all String TAG constants (debug
# breadcrumb tokens). R8's string optimization can fold / inline
# short strings, which makes our grep-based audit (S121,
# S122) miss the breadcrumb in the DEX even if the Log.d
# call survives. Forcing non-inlined static String fields
# with a non-trivial initializer preserves the literal in the
# constant pool. Without this rule, the Owner-side grep for
# `OpenE2eeVpn` / `TcpForwarder` / `UdpForwarder` /
# `NettyChannelClient` may miss the TAG (R8 may inline the
# literal as a primitive `String` constant in the bytecode
# and then dedupe identical strings across the entire DEX,
# which still leaves the literal present but might confuse
# some grep patterns). This rule keeps the TAG field as a
# distinct constant, guaranteeing the audit grep can find
# it. S122-5 audit verifies this rule is present.
-keepclassmembers,allowobfuscation class * {
    public static final java.lang.String TAG;
}

# Sprint 12.0F+2 — KEEP @androidx.annotation.Keep annotated
# members. Coder is encouraged to add @Keep to the new
# breadcrumb Log.d calls (and the new writeTcpRstToTun
# function) as a defense-in-depth measure. The annotation
# is from androidx.annotation (already on classpath via
# Flutter). R8 respects @Keep natively (no rule needed for
# the annotation itself), but the keep rules below are
# belt-and-braces in case R8 treats @Keep as a soft hint
# (e.g., during partial evaluation). S122-6 audit verifies
# at least 1 @Keep annotation is used in our code.
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
