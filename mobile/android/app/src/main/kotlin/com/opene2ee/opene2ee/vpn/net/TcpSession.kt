package com.opene2ee.opene2ee.vpn.net

class TcpSession(
    sourceAddress: IpAddress,
    sourcePort: Port,
    destinationAddress: IpAddress,
    destinationPort: Port
) : Session(Protocol.TCP, sourceAddress, sourcePort, destinationAddress, destinationPort)
