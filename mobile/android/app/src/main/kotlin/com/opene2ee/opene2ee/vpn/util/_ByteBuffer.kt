package com.opene2ee.opene2ee.vpn.util

import java.nio.ByteBuffer
import java.util.Queue

internal fun ByteBuffer.readUnsignedByte(index: Int): Int = this[index].toInt() and 0x0FF
internal fun ByteBuffer.readUnsignedShort(index: Int): Int = getShort(index).toInt() and 0x0FFFF

internal fun ByteBuffer.deepCopy(): ByteBuffer =
    ByteBuffer.wrap(array().copyOfRange(position(), remaining()))

internal fun Queue<ByteBuffer>.mergeBuffer(clear: Boolean = true): ByteBuffer {
    if (isEmpty()) return ByteBuffer.allocate(0)
    var total = 0
    for (b in this) total += b.remaining()
    val array = ByteArray(total)
    var offset = 0
    for (b in this) {
        b.array().copyInto(array, offset, b.position(), b.position() + b.remaining())
        offset += b.remaining()
    }
    if (clear) clear()
    return ByteBuffer.wrap(array)
}
