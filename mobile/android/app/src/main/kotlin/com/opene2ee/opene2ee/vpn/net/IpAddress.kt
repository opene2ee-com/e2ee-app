package com.opene2ee.opene2ee.vpn.net

import com.opene2ee.opene2ee.vpn.util.convertIPv4ToInt
import com.opene2ee.opene2ee.vpn.util.convertIPv4ToString
import com.opene2ee.opene2ee.vpn.util.convertIPv6ToInt
import com.opene2ee.opene2ee.vpn.util.convertIPv6ToString

class IpAddress {
    val ipVersion: IPVersion
    val intIPv4: Int
    val intIPv6: IntIPv6
    val stringIP: String

    constructor(ipv4: Int) {
        this.ipVersion = IPVersion.IPv4
        this.intIPv4 = ipv4
        this.stringIP = ipv4.convertIPv4ToString
        this.intIPv6 = IntIPv6(0L, 0L)
    }

    constructor(ipv6: IntIPv6) {
        this.ipVersion = IPVersion.IPv6
        this.intIPv6 = ipv6
        this.stringIP = ipv6.convertIPv6ToString
        this.intIPv4 = 0
    }

    constructor(address: String, ipVersion: IPVersion) {
        this.ipVersion = ipVersion
        when (ipVersion) {
            IPVersion.IPv4 -> {
                this.intIPv4 = address.convertIPv4ToInt
                this.stringIP = address
                this.intIPv6 = IntIPv6(0L, 0L)
            }
            IPVersion.IPv6 -> {
                this.intIPv6 = address.convertIPv6ToInt
                this.stringIP = address
                this.intIPv4 = 0
            }
        }
    }

    override fun toString(): String = stringIP
    override fun equals(other: Any?): Boolean = (other is IpAddress) && stringIP == other.stringIP
    override fun hashCode(): Int = stringIP.hashCode()
}
