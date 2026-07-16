package com.opene2ee.opene2ee.vpn.net

class UdpSession(
    sourceAddress: IpAddress,
    sourcePort: Port,
    destinationAddress: IpAddress,
    destinationPort: Port
) : Session(Protocol.UDP, sourceAddress, sourcePort, destinationAddress, destinationPort)
