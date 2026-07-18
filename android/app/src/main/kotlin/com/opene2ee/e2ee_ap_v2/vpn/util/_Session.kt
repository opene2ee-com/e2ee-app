package com.opene2ee.e2ee_ap_v2.vpn.kernel.util

import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import androidx.core.content.getSystemService
import com.opene2ee.e2ee_ap_v2.vpn.kernel.common.WireBare
import com.opene2ee.e2ee_ap_v2.vpn.kernel.net.Session
import java.net.InetSocketAddress

private val connectivityManager by lazy {
    WireBare.appContext.getSystemService<ConnectivityManager>()!!
}

/**
 * Returns the session owner's uid.
 * */
internal fun Session.sourceUid(): Int {
    val session = this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return Process.INVALID_UID
    }
    return connectivityManager.getConnectionOwnerUid(
        session.protocol.code.toUInt().toInt(),
        InetSocketAddress(
            session.sourceAddress.stringIP,
            session.sourcePort.port.convertPortToInt
        ),
        InetSocketAddress(
            session.destinationAddress.stringIP,
            session.destinationPort.port.convertPortToInt
        )
    )
}