package com.opene2ee.opene2ee.vpn.proxy

import com.opene2ee.opene2ee.vpn.nio.NioCallback
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import kotlinx.coroutines.isActive
import java.nio.channels.Selector

abstract class NioProxyServer : ProxyServer() {
    private val tag = "NioProxyServer"
    protected abstract val selector: Selector

    final override suspend fun process() {
        var select = 0
        while (isActive) {
            select = selector.select()
            if (select != 0) break
        }
        if (select == 0) return
        val keys = selector.selectedKeys()
        var key = keys.firstOrNull()
        while (key != null) {
            val k = key
            keys.remove(k)
            key = keys.firstOrNull()
            val cb = k.attachment()
            if (!k.isValid || cb !is NioCallback) continue
            try {
                when {
                    k.isAcceptable -> cb.onAccept()
                    k.isConnectable -> cb.onConnected()
                    k.isReadable -> cb.onRead()
                    k.isWritable -> cb.onWrite()
                }
            } catch (e: Exception) {
                VPNLogger.e(tag, "nio key process failed", e)
                cb.onException(e)
            }
        }
    }
}
