package com.opene2ee.opene2ee.vpn.tcpip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class TcpForwarderTest {
    @Test
    fun `SYN opens the remote target and emits a valid SYN ACK`() {
        val connector = RecordingConnector()
        val emitted = mutableListOf<ByteArray>()
        val forwarder = TcpForwarder(
            connector = connector,
            packetSink = emitted::add,
            initialSequence = { 0x1020_3040 },
        )

        forwarder.accept(ipv4TcpSyn())

        assertEquals(TcpDestination("93.184.216.34", 443), connector.destinations.single())
        val response = emitted.single()
        assertEquals("93.184.216.34", response.ipv4Source())
        assertEquals("10.1.10.1", response.ipv4Destination())
        assertEquals(443, response.u16(20))
        assertEquals(50_000, response.u16(22))
        assertEquals(0x1020_3040, response.i32(24))
        assertEquals(0x0102_0305, response.i32(28))
        assertEquals(0x12, response[33].toInt() and 0xff)
        assertTrue("IPv4 checksum must be valid", response.onesComplementSum(0, 20) == 0xffff)
        assertTrue("TCP checksum must be valid", response.tcpChecksumSum() == 0xffff)
    }

    @Test
    fun `established flow forwards client payload and acknowledges it`() {
        val connector = RecordingConnector()
        val emitted = mutableListOf<ByteArray>()
        val forwarder = TcpForwarder(
            connector = connector,
            packetSink = emitted::add,
            initialSequence = { 0x1020_3040 },
        )
        forwarder.accept(ipv4TcpSyn())
        emitted.clear()

        forwarder.accept(
            ipv4TcpPacket(
                sequence = 0x0102_0305,
                acknowledgment = 0x1020_3041,
                flags = 0x18,
                payload = "client hello".encodeToByteArray(),
            ),
        )

        assertEquals("client hello", connector.connection.writes.single().decodeToString())
        val acknowledgment = emitted.single()
        assertEquals(0x10, acknowledgment[33].toInt() and 0xff)
        assertEquals(0x1020_3041, acknowledgment.i32(24))
        assertEquals(0x0102_0311, acknowledgment.i32(28))
        assertTrue("TCP checksum must be valid", acknowledgment.tcpChecksumSum() == 0xffff)
    }

    @Test
    fun `remote payload is emitted to the app with flow sequence numbers`() {
        val connector = RecordingConnector()
        val emitted = mutableListOf<ByteArray>()
        val forwarder = TcpForwarder(
            connector = connector,
            packetSink = emitted::add,
            initialSequence = { 0x1020_3040 },
        )
        forwarder.accept(ipv4TcpSyn())
        emitted.clear()

        connector.listener.onData("server hello".encodeToByteArray())

        val response = emitted.single()
        assertEquals(52, response.size)
        assertEquals(0x18, response[33].toInt() and 0xff)
        assertEquals(0x1020_3041, response.i32(24))
        assertEquals(0x0102_0305, response.i32(28))
        assertEquals("server hello", response.copyOfRange(40, response.size).decodeToString())
        assertTrue("IPv4 checksum must be valid", response.onesComplementSum(0, 20) == 0xffff)
        assertTrue("TCP checksum must be valid", response.tcpChecksumSum() == 0xffff)
    }

    @Test
    fun `closing the forwarder closes active remote connections`() {
        val connector = RecordingConnector()
        val forwarder = TcpForwarder(connector, {}, { 7 })
        forwarder.accept(ipv4TcpSyn())

        forwarder.close()

        assertTrue(connector.connection.closed)
    }

    @Test
    fun `client FIN shuts down remote output and is acknowledged`() {
        val connector = RecordingConnector()
        val emitted = mutableListOf<ByteArray>()
        val forwarder = TcpForwarder(connector, emitted::add, { 0x1020_3040 })
        forwarder.accept(ipv4TcpSyn())
        emitted.clear()

        forwarder.accept(
            ipv4TcpPacket(
                sequence = 0x0102_0305,
                acknowledgment = 0x1020_3041,
                flags = 0x11,
            ),
        )

        assertTrue(connector.connection.outputShutdown)
        val response = emitted.single()
        assertEquals(0x10, response[33].toInt() and 0xff)
        assertEquals(0x0102_0306, response.i32(28))
    }

    @Test
    fun `remote close sends FIN ACK to the app`() {
        val connector = RecordingConnector()
        val emitted = mutableListOf<ByteArray>()
        val forwarder = TcpForwarder(connector, emitted::add, { 0x1020_3040 })
        forwarder.accept(ipv4TcpSyn())
        emitted.clear()

        connector.listener.onClosed()

        val response = emitted.single()
        assertEquals(0x11, response[33].toInt() and 0xff)
        assertEquals(0x1020_3041, response.i32(24))
        assertEquals(0x0102_0305, response.i32(28))
    }

    @Test
    fun `remote failure resets and removes the app flow`() {
        val connector = RecordingConnector()
        val emitted = mutableListOf<ByteArray>()
        val forwarder = TcpForwarder(connector, emitted::add, { 0x1020_3040 })
        forwarder.accept(ipv4TcpSyn())
        emitted.clear()

        connector.listener.onFailure(IllegalStateException("connect failed"))

        val response = emitted.single()
        assertEquals(0x14, response[33].toInt() and 0xff)
        assertTrue(connector.connection.closed)

        emitted.clear()
        forwarder.accept(
            ipv4TcpPacket(
                sequence = 0x0102_0305,
                acknowledgment = 0x1020_3041,
                flags = 0x18,
                payload = byteArrayOf(1),
            ),
        )
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `remote reader waits for client window and resumes after ACK`() {
        val connector = RecordingConnector()
        val emitted = mutableListOf<ByteArray>()
        val forwarder = TcpForwarder(connector, emitted::add, { 0x1020_3040 })
        forwarder.accept(ipv4TcpSyn(window = 4))
        emitted.clear()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val remoteRead = executor.submit {
                connector.listener.onData("12345".encodeToByteArray())
            }
            try {
                remoteRead.get(100, TimeUnit.MILLISECONDS)
                throw AssertionError("remote read should wait while the advertised window is full")
            } catch (_: TimeoutException) {
                // Expected: the connector reader is applying backpressure.
            }

            forwarder.accept(
                ipv4TcpPacket(
                    sequence = 0x0102_0305,
                    acknowledgment = 0x1020_3041,
                    flags = 0x10,
                    window = 10,
                ),
            )
            remoteRead.get(1, TimeUnit.SECONDS)

            assertEquals("12345", emitted.single().copyOfRange(40, 45).decodeToString())
        } finally {
            forwarder.close()
            executor.shutdownNow()
        }
    }

    private class RecordingConnector : TcpConnector {
        val destinations = mutableListOf<TcpDestination>()
        val connection = RecordingConnection()
        lateinit var listener: TcpConnectionListener

        override fun connect(destination: TcpDestination, listener: TcpConnectionListener): TcpConnection {
            destinations += destination
            this.listener = listener
            return connection
        }
    }

    private class RecordingConnection : TcpConnection {
        val writes = mutableListOf<ByteArray>()
        var closed = false
        var outputShutdown = false

        override fun write(payload: ByteArray) {
            writes += payload
        }

        override fun shutdownOutput() {
            outputShutdown = true
        }
        override fun close() {
            closed = true
        }
    }

    private fun ipv4TcpSyn(window: Int = 0xffff): ByteArray = ipv4TcpPacket(
        sequence = 0x0102_0304,
        acknowledgment = 0,
        flags = 0x02,
        window = window,
    )

    private fun ipv4TcpPacket(
        sequence: Int,
        acknowledgment: Int,
        flags: Int,
        window: Int = 0xffff,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray = ByteArray(40 + payload.size).also { packet ->
        packet[0] = 0x45
        packet[2] = (packet.size ushr 8).toByte()
        packet[3] = packet.size.toByte()
        packet[6] = 0x40
        packet[8] = 0x40
        packet[9] = 0x06
        byteArrayOf(10, 1, 10, 1).copyInto(packet, 12)
        byteArrayOf(93, 184.toByte(), 216.toByte(), 34).copyInto(packet, 16)
        packet[20] = 0xc3.toByte()
        packet[21] = 0x50
        packet[22] = 0x01
        packet[23] = 0xbb.toByte()
        packet.writeI32(24, sequence)
        packet.writeI32(28, acknowledgment)
        packet[32] = 0x50
        packet[33] = flags.toByte()
        packet[34] = (window ushr 8).toByte()
        packet[35] = window.toByte()
        payload.copyInto(packet, 40)
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.i32(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun ByteArray.writeI32(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun ByteArray.ipv4Source(): String =
        InetAddress.getByAddress(copyOfRange(12, 16)).hostAddress ?: error("no source address")

    private fun ByteArray.ipv4Destination(): String =
        InetAddress.getByAddress(copyOfRange(16, 20)).hostAddress ?: error("no destination address")

    private fun ByteArray.onesComplementSum(offset: Int, length: Int): Int {
        var sum = 0L
        var cursor = offset
        val end = offset + length
        while (cursor + 1 < end) {
            sum += u16(cursor).toLong()
            cursor += 2
        }
        if (cursor < end) sum += (this[cursor].toInt() and 0xff).toLong() shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt()
    }

    private fun ByteArray.tcpChecksumSum(): Int {
        val tcpLength = size - 20
        var sum = 0L
        for (offset in 12 until 20 step 2) sum += u16(offset)
        sum += 6
        sum += tcpLength
        for (offset in 20 until size step 2) {
            sum += if (offset + 1 < size) u16(offset) else (this[offset].toInt() and 0xff) shl 8
        }
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt()
    }
}
