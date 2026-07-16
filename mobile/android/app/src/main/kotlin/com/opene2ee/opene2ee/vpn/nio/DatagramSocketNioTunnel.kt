package com.opene2ee.opene2ee.vpn.nio

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

abstract class DatagramSocketNioTunnel : NioTunnel<DatagramChannel>(), Closeable {
    abstract override val channel: DatagramChannel
    final override fun onConnected() { throw IllegalStateException("UDP is connectionless") }
    override fun readByteBuffer(buffer: ByteBuffer): Int = channel.read(buffer)
    override fun writeByteBuffer(buffer: ByteBuffer): Int = channel.write(buffer)
}
