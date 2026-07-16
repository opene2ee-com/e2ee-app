package com.opene2ee.opene2ee.vpn.nio

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

abstract class SocketNioTunnel : NioTunnel<SocketChannel>(), Closeable {
    abstract override val channel: SocketChannel
    override fun readByteBuffer(buffer: ByteBuffer): Int = channel.read(buffer)
    override fun writeByteBuffer(buffer: ByteBuffer): Int = channel.write(buffer)
}
