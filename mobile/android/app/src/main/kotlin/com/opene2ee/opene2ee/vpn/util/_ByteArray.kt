package com.opene2ee.opene2ee.vpn.util

internal fun ByteArray.readByte(offset: Int): Byte = this[offset]
internal fun ByteArray.writeByte(value: Byte, offset: Int) { this[offset] = value }

internal fun ByteArray.readShort(offset: Int): Short =
    ((this[offset].toInt() and 0xFF shl 8) or (this[offset + 1].toInt() and 0xFF)).toShort()

internal fun ByteArray.writeShort(value: Short, offset: Int) {
    this[offset] = (value.toInt() shr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.readInt(offset: Int): Int =
    (this[offset].toInt() shl 24) or
    (this[offset + 1].toInt() and 0xFF shl 16) or
    (this[offset + 2].toInt() and 0xFF shl 8) or
    (this[offset + 3].toInt() and 0xFF)

internal fun ByteArray.writeInt(value: Int, offset: Int) {
    this[offset] = (value shr 24).toByte()
    this[offset + 1] = (value shr 16).toByte()
    this[offset + 2] = (value shr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.readLong(offset: Int): Long =
    (this[offset].toLong() shl 56) or
    (this[offset + 1].toLong() and 0xFF shl 48) or
    (this[offset + 2].toLong() and 0xFF shl 40) or
    (this[offset + 3].toLong() and 0xFF shl 32) or
    (this[offset + 4].toLong() and 0xFF shl 24) or
    (this[offset + 5].toLong() and 0xFF shl 16) or
    (this[offset + 6].toLong() and 0xFF shl 8) or
    (this[offset + 7].toLong() and 0xFF)

internal fun ByteArray.writeLong(value: Long, offset: Int) {
    this[offset] = (value shr 56).toByte()
    this[offset + 1] = (value shr 48).toByte()
    this[offset + 2] = (value shr 40).toByte()
    this[offset + 3] = (value shr 32).toByte()
    this[offset + 4] = (value shr 24).toByte()
    this[offset + 5] = (value shr 16).toByte()
    this[offset + 6] = (value shr 8).toByte()
    this[offset + 7] = value.toByte()
}

internal fun ByteArray.calculateSum(offset: Int, length: Int): Int {
    var start = offset
    var size = length
    var sum = 0
    while (size > 1) {
        sum += readShort(start).toInt() and 0xFFFF
        start += 2
        size -= 2
    }
    if (size > 0) sum += readByte(start).toInt() and 0xFF shl 8
    return sum
}
