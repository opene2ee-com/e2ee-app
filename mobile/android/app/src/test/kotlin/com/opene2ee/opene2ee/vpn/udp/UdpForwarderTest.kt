package com.opene2ee.opene2ee.vpn.udp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class UdpForwarderTest {
    @Test
    fun `DNS query is sent to the original destination`() {
        val connector = RecordingConnector()
        val emitted = mutableListOf<ByteArray>()
        val forwarder = UdpForwarder(connector, emitted::add)

        forwarder.accept(ipv4UdpPacket("dns query".encodeToByteArray()))

        assertEquals(UdpDestination("8.8.8.8", 53), connector.destination)
        assertEquals("dns query", connector.connection.sent.single().decodeToString())
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `DNS response is emitted as a valid reverse UDP packet`() {
        val connector = RecordingConnector()
        val emitted = mutableListOf<ByteArray>()
        val forwarder = UdpForwarder(connector, emitted::add)
        forwarder.accept(ipv4UdpPacket("dns query".encodeToByteArray()))

        connector.listener.onData("dns response".encodeToByteArray())

        val response = emitted.single()
        assertEquals("8.8.8.8", response.address(12))
        assertEquals("10.1.10.1", response.address(16))
        assertEquals(53, response.u16(20))
        assertEquals(40_000, response.u16(22))
        assertEquals("dns response", response.copyOfRange(28, response.size).decodeToString())
        assertEquals(0xffff, response.onesComplementSum(0, 20))
        assertEquals(0xffff, response.udpChecksumSum())
    }

    @Test
    fun `closing UDP forwarder closes active datagram flows`() {
        val connector = RecordingConnector()
        val forwarder = UdpForwarder(connector, {})
        forwarder.accept(ipv4UdpPacket(byteArrayOf(1)))

        forwarder.close()

        assertTrue(connector.connection.closed)
    }

    private class RecordingConnector : UdpConnector {
        lateinit var destination: UdpDestination
        lateinit var listener: UdpConnectionListener
        val connection = RecordingConnection()

        override fun connect(
            destination: UdpDestination,
            listener: UdpConnectionListener,
        ): UdpConnection {
            this.destination = destination
            this.listener = listener
            return connection
        }
    }

    private class RecordingConnection : UdpConnection {
        val sent = mutableListOf<ByteArray>()
        var closed = false

        override fun send(payload: ByteArray) {
            sent += payload
        }

        override fun close() {
            closed = true
        }
    }

    private fun ipv4UdpPacket(payload: ByteArray): ByteArray = ByteArray(28 + payload.size).also { packet ->
        packet[0] = 0x45
        packet.writeU16(2, packet.size)
        packet[6] = 0x40
        packet[8] = 0x40
        packet[9] = 17
        byteArrayOf(10, 1, 10, 1).copyInto(packet, 12)
        byteArrayOf(8, 8, 8, 8).copyInto(packet, 16)
        packet.writeU16(20, 40_000)
        packet.writeU16(22, 53)
        packet.writeU16(24, 8 + payload.size)
        payload.copyInto(packet, 28)
    }

    private fun ByteArray.address(offset: Int): String =
        InetAddress.getByAddress(copyOfRange(offset, offset + 4)).hostAddress ?: error("no address")

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.writeU16(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.onesComplementSum(offset: Int, length: Int): Int {
        var sum = 0L
        var cursor = offset
        val end = offset + length
        while (cursor + 1 < end) {
            sum += u16(cursor)
            cursor += 2
        }
        if (cursor < end) sum += (this[cursor].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt()
    }

    private fun ByteArray.udpChecksumSum(): Int {
        var sum = 0L
        for (offset in 12 until 20 step 2) sum += u16(offset)
        sum += 17
        sum += size - 20
        for (offset in 20 until size step 2) {
            sum += if (offset + 1 < size) u16(offset) else (this[offset].toInt() and 0xff) shl 8
        }
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt()
    }
}
