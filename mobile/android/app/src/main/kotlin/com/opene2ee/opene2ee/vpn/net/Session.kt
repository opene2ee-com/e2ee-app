package com.opene2ee.opene2ee.vpn.net

abstract class Session(
    val protocol: Protocol,
    val sourceAddress: IpAddress,
    val sourcePort: Port,
    val destinationAddress: IpAddress,
    val destinationPort: Port
)
