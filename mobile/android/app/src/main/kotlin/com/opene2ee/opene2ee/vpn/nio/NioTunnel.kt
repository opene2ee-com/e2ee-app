package com.opene2ee.opene2ee.vpn.nio

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.AbstractSelectableChannel
import java.util.concurrent.LinkedBlockingQueue

abstract class NioTunnel<SC : AbstractSelectableChannel> : NioCallback, Closeable {
    abstract val channel: SC
    abstract val selector: Selector
    var isClosed: Boolean = false
        private set

    private val pendingBuffers = LinkedBlockingQueue<ByteBuffer>()
    private lateinit var key: SelectionKey

    protected abstract fun readByteBuffer(buffer: ByteBuffer): Int
    protected abstract fun writeByteBuffer(buffer: ByteBuffer): Int

    override fun onConnected() {}
    override fun onAccept() {}
    override fun onRead() {}
    override fun onException(t: Throwable) {}

    override fun onWrite(): Int {
        if (isClosed) return 0
        var total = 0
        while (pendingBuffers.isNotEmpty()) {
            val buffer = pendingBuffers.poll() ?: break
            val remaining = buffer.remaining()
            val length = writeByteBuffer(buffer)
            total += length
            if (length < remaining) {
                pendingBuffers.offer(buffer)
                return total
            }
        }
        interestRead()
        return total
    }

    fun read(buffer: ByteBuffer): Int {
        buffer.clear()
        val length = readByteBuffer(buffer)
        if (length > 0) buffer.flip()
        return length
    }

    fun write(buffer: ByteBuffer) {
        if (!isClosed && buffer.hasRemaining()) {
            pendingBuffers.offer(buffer)
            interestWrite()
        }
    }

    fun prepareRead() {
        if (channel.isBlocking) channel.configureBlocking(false)
        selector.wakeup()
        key = channel.register(selector, SelectionKey.OP_READ, this)
    }

    private fun interestWrite() {
        selector.wakeup()
        key.interestOps(SelectionKey.OP_WRITE)
    }

    private fun interestRead() {
        selector.wakeup()
        key.interestOps(SelectionKey.OP_READ)
    }

    override fun close() {
        pendingBuffers.clear()
        channel.close()
        isClosed = true
    }
}
