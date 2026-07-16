package com.opene2ee.opene2ee.vpn.net

class TcpSessionStore : SessionStore<Port, TcpSession>() {
    fun insert(
        sourceAddress: IpAddress,
        sourcePort: Port,
        destinationAddress: IpAddress,
        destinationPort: Port
    ): TcpSession {
        val origin = query(sourcePort)
        return if (origin != null && origin.destinationAddress == destinationAddress && origin.destinationPort == destinationPort) {
            origin
        } else {
            TcpSession(sourceAddress, sourcePort, destinationAddress, destinationPort)
                .also { insertSession(sourcePort, it) }
        }
    }
}
