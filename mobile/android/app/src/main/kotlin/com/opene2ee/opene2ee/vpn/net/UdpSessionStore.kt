package com.opene2ee.opene2ee.vpn.net

class UdpSessionStore : SessionStore<Port, UdpSession>() {
    fun insert(
        sourceAddress: IpAddress,
        sourcePort: Port,
        destinationAddress: IpAddress,
        destinationPort: Port
    ): UdpSession {
        return UdpSession(sourceAddress, sourcePort, destinationAddress, destinationPort)
            .also { insertSession(sourcePort, it) }
    }
}
