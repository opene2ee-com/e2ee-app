package com.opene2ee.opene2ee.vpn.udp

import java.util.concurrent.ConcurrentHashMap

data class UdpDestination(val address: String, val port: Int)

interface UdpConnection {
    fun send(payload: ByteArray)
    fun close()
}

interface UdpConnectionListener {
    fun onData(payload: ByteArray) = Unit
    fun onFailure(cause: Throwable) = Unit
}

interface UdpConnector {
    fun connect(destination: UdpDestination, listener: UdpConnectionListener): UdpConnection
}

/** Translates IPv4 UDP datagrams between the TUN and protected remote sockets. */
class UdpForwarder(
    private val connector: UdpConnector,
    private val packetSink: (ByteArray) -> Unit,
    private val onEvent: (String) -> Unit = {},
) {
    private val flows = ConcurrentHashMap<FlowKey, Flow>()

    fun accept(packet: ByteArray) {
        if (packet.size < IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH) return
        val ipHeaderLength = (packet[0].toInt() and 0x0f) * 4
        if (ipHeaderLength < IPV4_HEADER_LENGTH || packet.size < ipHeaderLength + UDP_HEADER_LENGTH) return
        val totalLength = minOf(packet.u16(2), packet.size)
        val udpLength = packet.u16(ipHeaderLength + 4)
        val payloadOffset = ipHeaderLength + UDP_HEADER_LENGTH
        val payloadLength = minOf(totalLength - payloadOffset, udpLength - UDP_HEADER_LENGTH)
        if (payloadLength < 0) return

        val key = FlowKey(
            clientAddress = packet.i32(12),
            clientPort = packet.u16(ipHeaderLength),
            remoteAddress = packet.i32(16),
            remotePort = packet.u16(ipHeaderLength + 2),
        )
        val flow = flows[key] ?: createFlow(key)
        val payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
        onEvent("tun->udp clientPort=${key.clientPort} remotePort=${key.remotePort} length=$payloadLength")
        flow.connection.send(payload)
    }

    fun close() {
        flows.values.forEach { it.connection.close() }
        flows.clear()
    }

    private fun createFlow(key: FlowKey): Flow {
        val listener = object : UdpConnectionListener {
            override fun onData(payload: ByteArray) {
                val flow = flows[key] ?: return
                onEvent(
                    "udp->tun clientPort=${key.clientPort} remotePort=${key.remotePort} length=${payload.size}",
                )
                packetSink(createPacket(flow.key, payload))
            }

            override fun onFailure(cause: Throwable) {
                flows.remove(key)?.connection?.close()
            }
        }
        val connection = connector.connect(
            UdpDestination(ipv4Address(key.remoteAddress), key.remotePort),
            listener,
        )
        return Flow(key, connection).also { candidate ->
            val existing = flows.putIfAbsent(key, candidate)
            if (existing != null) candidate.connection.close()
        }.let { flows.getValue(key) }
    }

    private fun createPacket(key: FlowKey, payload: ByteArray): ByteArray {
        return ByteArray(IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH + payload.size).also { response ->
            response[0] = 0x45
            response.writeU16(2, response.size)
            response.writeU16(6, 0x4000)
            response[8] = 64
            response[9] = UDP_PROTOCOL.toByte()
            response.writeI32(12, key.remoteAddress)
            response.writeI32(16, key.clientAddress)
            response.writeU16(20, key.remotePort)
            response.writeU16(22, key.clientPort)
            response.writeU16(24, UDP_HEADER_LENGTH + payload.size)
            payload.copyInto(response, IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH)
            response.writeU16(10, checksum(response, 0, IPV4_HEADER_LENGTH))
            val udpChecksum = udpChecksum(response)
            response.writeU16(26, if (udpChecksum == 0) 0xffff else udpChecksum)
        }
    }

    private fun udpChecksum(packet: ByteArray): Int {
        var sum = 0L
        for (offset in 12 until 20 step 2) sum += packet.u16(offset)
        sum += UDP_PROTOCOL
        sum += packet.size - IPV4_HEADER_LENGTH
        sum += wordSum(packet, IPV4_HEADER_LENGTH, packet.size - IPV4_HEADER_LENGTH)
        return foldAndInvert(sum)
    }

    private fun checksum(packet: ByteArray, offset: Int, length: Int): Int =
        foldAndInvert(wordSum(packet, offset, length))

    private fun wordSum(packet: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var cursor = offset
        val end = offset + length
        while (cursor + 1 < end) {
            sum += packet.u16(cursor)
            cursor += 2
        }
        if (cursor < end) sum += (packet[cursor].toInt() and 0xff) shl 8
        return sum
    }

    private fun foldAndInvert(value: Long): Int {
        var sum = value
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun ipv4Address(address: Int): String = listOf(
        address ushr 24 and 0xff,
        address ushr 16 and 0xff,
        address ushr 8 and 0xff,
        address and 0xff,
    ).joinToString(".")

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.i32(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun ByteArray.writeU16(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.writeI32(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private data class FlowKey(
        val clientAddress: Int,
        val clientPort: Int,
        val remoteAddress: Int,
        val remotePort: Int,
    )

    private data class Flow(val key: FlowKey, val connection: UdpConnection)

    private companion object {
        const val IPV4_HEADER_LENGTH = 20
        const val UDP_HEADER_LENGTH = 8
        const val UDP_PROTOCOL = 17
    }
}
