package com.opene2ee.opene2ee.vpn.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

abstract class ProxyServer : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
    protected abstract suspend fun process()
    protected abstract fun release()
    fun dispatch() {
        launch(Dispatchers.IO) {
            while (isActive) process()
            release()
        }
    }
}
