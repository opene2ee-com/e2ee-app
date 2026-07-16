// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/MyLRUCache.kt
//
// Sprint 14 — LRU cache (portKey → session/tunnel).
// Referans: huolizhuminh/NetWorkPacketCache MyLRUCache.java (LinkedHashMap accessOrder=true).

package com.opene2ee.opene2ee.vpn.net

import androidx.annotation.Keep
import java.util.LinkedHashMap

/**
 * LinkedHashMap accessOrder=true LRU cache. Eldest entry evicted → callback.
 *
 * @param maxSize eviction threshold (entry count).
 * @param callback invoked when eldest entry is evicted.
 */
@Keep
class MyLRUCache<K, V>(
    private val maxSize: Int,
    private val callback: CleanupCallback<V> = NoOpCallback()
) : LinkedHashMap<K, V>(maxSize + 1, 1f, true) {

    @Keep
    interface CleanupCallback<V> {
        fun cleanUp(value: V)
    }

    @Keep
    private class NoOpCallback<V> : CleanupCallback<V> {
        override fun cleanUp(value: V) {}
    }

    @Keep
    override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean {
        if (size > maxSize) {
            callback.cleanUp(eldest.value)
            return true
        }
        return false
    }
}
