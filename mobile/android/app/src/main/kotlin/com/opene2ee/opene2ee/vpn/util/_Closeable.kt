package com.opene2ee.opene2ee.vpn.util

import java.io.Closeable

internal fun closeSafely(vararg closeables: Closeable?) {
    for (c in closeables) c?.closeSafely()
}

internal fun Closeable?.closeSafely() {
    this ?: return
    try { close() } catch (e: Throwable) { VPNLogger.w("Closeable", "closeSafely", e) }
}
