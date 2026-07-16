package com.opene2ee.opene2ee.vpn.net

class Protocol private constructor(val name: String, val code: Byte) {
    companion object {
        val TCP = Protocol("TCP", 6.toByte())
        val UDP = Protocol("UDP", 17.toByte())
        val END = Protocol("END", 59.toByte())
        val NULL = Protocol("NULL", 0.toByte())

        private val protocols = hashMapOf(TCP.code to TCP, UDP.code to UDP, END.code to END)
        fun parse(code: Byte): Protocol = protocols[code] ?: NULL
    }
}
