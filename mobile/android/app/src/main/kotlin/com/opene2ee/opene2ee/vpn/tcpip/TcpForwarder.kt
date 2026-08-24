package com.opene2ee.opene2ee.vpn.tcpip

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class TcpDestination(val address: String, val port: Int)

interface TcpConnection {
    fun write(payload: ByteArray)
    fun shutdownOutput()
    fun close()
}

interface TcpConnectionListener {
    fun onConnected() = Unit
    fun onData(payload: ByteArray) = Unit
    fun onClosed() = Unit
    fun onFailure(cause: Throwable) = Unit
}

interface TcpConnector {
    fun connect(destination: TcpDestination, listener: TcpConnectionListener): TcpConnection
}

/**
 * Owns the app-facing TCP endpoint for packets read from the TUN descriptor.
 * The remote half is a protected socket supplied by [TcpConnector].
 */
class TcpForwarder(
    private val connector: TcpConnector,
    private val packetSink: (ByteArray) -> Unit,
    private val initialSequence: () -> Int,
    private val onEvent: (String) -> Unit = {},
) {
    private val flows = ConcurrentHashMap<FlowKey, Flow>()

    fun accept(packet: ByteArray) {
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        val tcpOffset = headerLength
        val tcpHeaderLength = ((packet[tcpOffset + 12].toInt() and 0xf0) ushr 4) * 4
        val flags = packet[tcpOffset + 13].toInt() and 0xff
        val key = FlowKey(
            clientAddress = packet.i32(12),
            clientPort = packet.u16(tcpOffset),
            remoteAddress = packet.i32(16),
            remotePort = packet.u16(tcpOffset + 2),
        )
        val clientSequence = packet.i32(tcpOffset + 4)

        if (flags and TCP_SYN != 0 && flags and TCP_ACK == 0) {
            val existing = flows[key]
            if (existing != null) {
                packetSink(createPacket(existing, existing.serverSequence - 1, existing.clientSequence, TCP_SYN or TCP_ACK))
                return
            }

            val serverSequence = initialSequence()
            val destination = TcpDestination(ipv4Address(key.remoteAddress), key.remotePort)
            onEvent("tun->tcp syn clientPort=${key.clientPort} remotePort=${key.remotePort}")
            val flow = Flow(
                key = key,
                clientSequence = clientSequence + 1,
                serverSequence = serverSequence + 1,
                clientAcknowledgedServerSequence = serverSequence + 1,
                clientWindow = packet.u16(tcpOffset + 14),
            )
            flows[key] = flow
            flow.connection = connector.connect(destination, object : TcpConnectionListener {
                override fun onData(payload: ByteArray) {
                    emitRemotePayload(key, payload)
                }

                override fun onClosed() {
                    emitRemoteClose(key)
                }

                override fun onFailure(cause: Throwable) {
                    resetFlow(key)
                }
            })
            packetSink(createPacket(flow, serverSequence, flow.clientSequence, TCP_SYN or TCP_ACK))
            onEvent("tcp->tun synack clientPort=${key.clientPort} remotePort=${key.remotePort}")
            return
        }

        val flow = flows[key] ?: return
        if (flags and TCP_RST != 0) {
            flows.remove(key, flow)
            flow.closeWindow()
            flow.connection?.close()
            return
        }
        if (flags and TCP_ACK != 0) {
            flow.updateWindow(
                acknowledgment = packet.i32(tcpOffset + 8),
                advertisedWindow = packet.u16(tcpOffset + 14),
            )
        }
        val totalLength = minOf(packet.u16(2), packet.size)
        val payloadOffset = tcpOffset + tcpHeaderLength
        val payloadLength = (totalLength - payloadOffset).coerceAtLeast(0)
        onEvent(
            "tun->tcp flags=0x${flags.toString(16)} clientPort=${key.clientPort} " +
                "remotePort=${key.remotePort} length=$payloadLength expectedSeq=${flow.clientSequence}",
        )
        if (payloadLength > 0 && clientSequence == flow.clientSequence) {
            flow.connection?.write(packet.copyOfRange(payloadOffset, payloadOffset + payloadLength))
            flow.clientSequence += payloadLength
            packetSink(createPacket(flow, flow.serverSequence, flow.clientSequence, TCP_ACK))
        }
        if (
            flags and TCP_FIN != 0 &&
            !flow.clientFinReceived &&
            clientSequence + payloadLength == flow.clientSequence
        ) {
            synchronized(flow) {
                if (!flow.clientFinReceived) {
                    flow.clientFinReceived = true
                    flow.clientSequence += 1
                    flow.connection?.shutdownOutput()
                    packetSink(createPacket(flow, flow.serverSequence, flow.clientSequence, TCP_ACK))
                }
            }
        }
    }

    fun close() {
        flows.values.forEach {
            it.closeWindow()
            it.connection?.close()
        }
        flows.clear()
    }

    private fun emitRemotePayload(key: FlowKey, payload: ByteArray) {
        if (payload.isEmpty()) return
        val flow = flows[key] ?: return
        flow.windowLock.withLock {
            while (
                !flow.closed &&
                sequenceDistance(flow.clientAcknowledgedServerSequence, flow.serverSequence) + payload.size >
                flow.clientWindow
            ) {
                try {
                    flow.windowChanged.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            if (flow.closed) return
            onEvent(
                "tcp->tun clientPort=${key.clientPort} remotePort=${key.remotePort} length=${payload.size}",
            )
            packetSink(
                createPacket(
                    flow = flow,
                    serverSequence = flow.serverSequence,
                    clientAcknowledgment = flow.clientSequence,
                    flags = TCP_PSH or TCP_ACK,
                    payload = payload,
                ),
            )
            flow.serverSequence += payload.size
        }
    }

    private fun emitRemoteClose(key: FlowKey) {
        val flow = flows[key] ?: return
        flow.windowLock.withLock {
            if (flow.remoteFinSent) return
            flow.remoteFinSent = true
            packetSink(
                createPacket(
                    flow = flow,
                    serverSequence = flow.serverSequence,
                    clientAcknowledgment = flow.clientSequence,
                    flags = TCP_FIN or TCP_ACK,
                ),
            )
            flow.serverSequence += 1
        }
    }

    private fun resetFlow(key: FlowKey) {
        val flow = flows.remove(key) ?: return
        flow.windowLock.withLock {
            flow.closed = true
            flow.windowChanged.signalAll()
            packetSink(
                createPacket(
                    flow = flow,
                    serverSequence = flow.serverSequence,
                    clientAcknowledgment = flow.clientSequence,
                    flags = TCP_RST or TCP_ACK,
                ),
            )
            flow.connection?.close()
        }
    }

    private fun createPacket(
        flow: Flow,
        serverSequence: Int,
        clientAcknowledgment: Int,
        flags: Int,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        return ByteArray(IPV4_HEADER_LENGTH + TCP_HEADER_LENGTH + payload.size).also { response ->
            response[0] = 0x45
            response.writeU16(2, response.size)
            response.writeU16(6, 0x4000)
            response[8] = 64
            response[9] = TCP_PROTOCOL.toByte()
            response.writeI32(12, flow.key.remoteAddress)
            response.writeI32(16, flow.key.clientAddress)

            response.writeU16(20, flow.key.remotePort)
            response.writeU16(22, flow.key.clientPort)
            response.writeI32(24, serverSequence)
            response.writeI32(28, clientAcknowledgment)
            response[32] = 0x50
            response[33] = flags.toByte()
            response.writeU16(34, MAX_WINDOW)
            payload.copyInto(response, IPV4_HEADER_LENGTH + TCP_HEADER_LENGTH)

            response.writeU16(10, checksum(response, 0, IPV4_HEADER_LENGTH))
            response.writeU16(36, tcpChecksum(response))
        }
    }

    private fun tcpChecksum(packet: ByteArray): Int {
        var sum = 0L
        for (offset in 12 until 20 step 2) sum += packet.u16(offset)
        sum += TCP_PROTOCOL
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

    private fun sequenceDistance(start: Int, end: Int): Long =
        (end.toLong() - start.toLong()) and 0xffff_ffffL

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

    private companion object {
        const val IPV4_HEADER_LENGTH = 20
        const val TCP_HEADER_LENGTH = 20
        const val TCP_PROTOCOL = 6
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_PSH = 0x08
        const val TCP_ACK = 0x10
        const val MAX_WINDOW = 0xffff
    }

    private data class FlowKey(
        val clientAddress: Int,
        val clientPort: Int,
        val remoteAddress: Int,
        val remotePort: Int,
    )

    private class Flow(
        val key: FlowKey,
        var clientSequence: Int,
        var serverSequence: Int,
        var clientAcknowledgedServerSequence: Int,
        var clientWindow: Int,
        var connection: TcpConnection? = null,
        var clientFinReceived: Boolean = false,
        var remoteFinSent: Boolean = false,
    ) {
        val windowLock = ReentrantLock()
        val windowChanged = windowLock.newCondition()
        var closed: Boolean = false

        fun updateWindow(acknowledgment: Int, advertisedWindow: Int) = windowLock.withLock {
            clientWindow = advertisedWindow
            val acknowledgedBytes =
                (acknowledgment.toLong() - clientAcknowledgedServerSequence.toLong()) and 0xffff_ffffL
            val outstandingBytes =
                (serverSequence.toLong() - clientAcknowledgedServerSequence.toLong()) and 0xffff_ffffL
            if (acknowledgedBytes <= outstandingBytes) {
                clientAcknowledgedServerSequence = acknowledgment
            }
            windowChanged.signalAll()
        }

        fun closeWindow() = windowLock.withLock {
            closed = true
            windowChanged.signalAll()
        }
    }
}
