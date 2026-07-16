package com.opene2ee.opene2ee.vpn.net

import com.opene2ee.opene2ee.vpn.util.convertPortToString

data class Port(val port: Short) {
    override fun toString(): String = port.convertPortToString
}
