package com.opene2ee.opene2ee.vpn.util

import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Keep
object ThreadProxy {
    @Keep
    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 4,
        10L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(1024),
        ThreadFactory { r ->
            Thread(r, "ThreadProxy-${ThreadCounter.incrementAndGet()}").apply { isDaemon = true }
        }
    )

    @Keep
    fun execute(r: Runnable) {
        executor.execute(r)
    }

    @Keep
    private object ThreadCounter {
        val c = AtomicInteger(0)
        fun incrementAndGet(): Int = c.incrementAndGet()
    }
}
