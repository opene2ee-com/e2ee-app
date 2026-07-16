package com.opene2ee.opene2ee.vpn.nio

interface NioCallback {
    fun onConnected()
    fun onAccept()
    fun onRead()
    fun onWrite(): Int
    fun onException(t: Throwable)
}
