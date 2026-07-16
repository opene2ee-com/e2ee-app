// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/NetFileManager.kt
//
// Sprint 14 — /proc/net parser, port → uid mapping.
// Referans: huolizhuminh/NetWorkPacketCapture NetFileManager.java + PR #33 fix.

package com.opene2ee.opene2ee.vpn.processparse

import android.content.Context
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Keep
class NetFileManager {
    @Keep
    companion object {
        const val TYPE_TCP = 0
        const val TYPE_TCP6 = 1
        const val TYPE_UDP = 2
        const val TYPE_UDP6 = 3
        const val TYPE_RAW = 4
        const val TYPE_RAW6 = 5
        const val TYPE_MAX = 6

        @Keep
        private val INSTANCE = NetFileManager()

        @Keep
        @JvmStatic
        fun getInstance(): NetFileManager = INSTANCE
    }

    @Keep
    private val processHost = ConcurrentHashMap<Int, Int>()  // localPort → uid

    @Keep
    private val file = arrayOfNulls<File>(TYPE_MAX)

    @Keep
    private val lastTime = LongArray(TYPE_MAX)

    @Keep
    fun init(context: Context) {
        file[TYPE_TCP] = File("/proc/net/tcp")
        file[TYPE_TCP6] = File("/proc/net/tcp6")
        file[TYPE_UDP] = File("/proc/net/udp")
        file[TYPE_UDP6] = File("/proc/net/udp6")
        file[TYPE_RAW] = File("/proc/net/raw")
        file[TYPE_RAW6] = File("/proc/net/raw6")
    }

    /**
     * /proc/net/ (tcp,udp,...) dosyalarını oku, parse et, port → uid map'i doldur.
     *
     * **PR #33 fix:** `/proc/net/tcp` ilk satırı `  sl  ...` (header).
     * Java readLine() içeriyor ama parse'da sTmp.startsWith("  sl")
     * kontrol edip atlamak GEREK (Kotlin/Java farkı). Burada
     * **scanner.hasNextLine() ile okurken ilk satırı skip etmek
     * gerekmez çünkü `/proc/net/tcp` başlık satırı zaten
     * boşlukla değil "  sl" ile başlar** — ama PR'nin fix'ine
     * sadık kal:
     */
    @Keep
    fun refresh() {
        for (i in 0 until TYPE_MAX) {
            val f = file[i] ?: continue
            val lm = f.lastModified()
            if (lm != lastTime[i]) {
                read(i)
                lastTime[i] = lm
            }
        }
    }

    @Keep
    private fun read(type: Int) {
        val path = when (type) {
            TYPE_TCP -> "/proc/net/tcp"
            TYPE_TCP6 -> "/proc/net/tcp6"
            TYPE_UDP -> "/proc/net/udp"
            TYPE_UDP6 -> "/proc/net/udp6"
            TYPE_RAW -> "/proc/net/raw"
            TYPE_RAW6 -> "/proc/net/raw6"
            else -> return
        }
        try {
            val process = ProcessBuilder("cat", path).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    // PR #33 fix: skip "  sl" header line
                    if (line.startsWith("  sl")) continue
                    val info = parseData(line) ?: continue
                    info.type = type
                    processHost[info.sourPort] = info.uid
                }
            }
        } catch (e: Exception) {
            // /proc/net/ read may fail on some devices; ignore.
        }
    }

    /**
     * /proc/net/tcp satır formatı:
     *   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid timeout inode
     *   0:  0100007F:0277 00000000:0000 0A 00000000:00000000 ...
     *
     * Columns (whitespace split):
     *   0: sl
     *   1: local_address:port (hex, little-endian IP)
     *   2: remote_address:port
     *   3: st (state)
     *   4: tx_queue
     *   5: rx_queue
     *   6: tr
     *   7: tm->when
     *   8: retrnsmt
     *   9: uid  ← BUNU ALIYORUZ
     */
    @Keep
    private fun parseData(line: String): NetInfo? {
        val parts = line.split("\\s+".toRegex())
        if (parts.size < 9) return null

        val info = NetInfo()

        // local_address:port
        val localSplit = parts[1].split(":")
        if (localSplit.size < 2) return null
        info.sourPort = localSplit[1].toInt(16)

        // remote_address:port
        val remoteSplit = parts[2].split(":")
        if (remoteSplit.size < 2) return null
        info.port = remoteSplit[1].toInt(16)

        // remote IP (hex, little-endian) — human readable
        val ipHex = remoteSplit[0]
        if (ipHex.length < 8) return null
        val ipReversed = ipHex.substring(ipHex.length - 8)
        info.address = "${ipReversed.substring(6, 8).toInt(16)}." +
                "${ipReversed.substring(4, 6).toInt(16)}." +
                "${ipReversed.substring(2, 4).toInt(16)}." +
                "${ipReversed.substring(0, 2).toInt(16)}"

        if (info.address == "0.0.0.0") return null

        // uid (decimal)
        info.uid = parts[9].toIntOrNull() ?: return null

        return info
    }

    @Keep
    fun getUid(port: Int): Int? {
        return processHost[port]
    }
}
