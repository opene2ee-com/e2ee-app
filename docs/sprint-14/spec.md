# Sprint 14 — VpnService Clean-Room Rewrite Implementation Spec

> **Status:** READ-ONLY design doc. Coder implements Birebir, section by section.
> **Worktree:** `C:\repos\e2ee-app-pr-s14item1` (branch `feat/sprint-14-vpn-rewrite`, base `main@4877098`)
> **Reference repo (source of truth):** https://github.com/huolizhuminh/NetWorkPacketCapture (master HEAD)
> **Reference PR (unmerged, must include):** https://github.com/huolizhuminh/NetWorkPacketCapture/pull/33 (mike006322)
> **Date:** 2026-07-15

---

## 0. KRİTİK DİSİPLİN — KODER'IN UYMASI GEREKEN 5 KESİN KURAL

Önceki 8 brief başarısız oldu (12.0F+1+2+3+4+5+6+7+8+9 + Sprint 13.0 + Sprint 13.0-fix). Hata: Coder spec'ten saptı, kendi yorumunu kattı. Bu spec Coder'ın copy-paste edebileceği şekilde yazıldı. **Kendin yorum katma. Her satırı bu spec'ten al. Aşağıdaki 5 kural ASLA çiğnenmez:**

| # | KURAL | YANLIŞ ÖRNEK (Sprint 13.0/13.0-fix hataları) | DOĞRU |
|---|-------|--------------------------------------------|-------|
| 1 | **MTU = 1400.** `setMtu(15000)` Android 14 ConnectivityService reject eder, VPN teardown. | `const val VPN_MTU = 15000` | `const val VPN_MTU = 1400` |
| 2 | **TcpProxyServer'da `readFirstPacket`/`parseFirstPacket` KULLANMA.** Kernel transparent proxy'de IP+TCP header'ları `Socket.getInputStream()`'a yazılmaz. Sadece uygulamanın HTTP payload'ı (veya SYN-ACK, no-data) gelir. | `readFirstPacket(socket)` ile SYN'den IP parse etmeye çalışmak (her zaman timeout/null) | `NatSessionManager.getSession(clientSocket.port)` ile NAT lookup, sonra `remoteSocket.connect()` |
| 3 | **portKey = `clientSocket.port`, ASLA `clientSocket.localPort`.** `java.net.Socket.getPort()` = remote/peer port = **app's source port** (NAT key). `getLocalPort()` = proxy's ephemeral port (her accept farklı). | `val portKey = clientSocket.localPort` | `val portKey = clientSocket.port` |
| 4 | **`channel.register(selector, OP_READ)` sonrası `key.attach(tunnel)` MUTLAKA çağrılır.** Tunnel oluşturulmadan register yapılırsa selector run loop `key.attachment() as? UdpTunnel` null alır, `receivePackets` hiç çağrılmaz, DNS 15s timeout. | `channel.register(selector, OP_READ)` sonra attach yok | `val key = channel.register(...); key.attach(tunnel)` |
| 5 | **PortHostService.refreshSessionInfo → `session.localPort` kullanır, ASLA `session.remotePort`.** `/proc/net/tcp` `local_address` kolonunda port var (kaynak), remote_port kolonunda değil. | `netFileManager?.getUid(session.remotePort)` | `netFileManager?.getUid(session.localPort)` |
| 6 | **`addDisallowedApplication` KULLANMA.** SecurityException (12.0F+9 ders). `addAllowedApplication` opsiyonel, varsayılan = tüm trafik. | `builder.addDisallowedApplication(pkg)` | `builder.addAllowedApplication(pkg)` (veya null = tüm trafik) |

**Self-check (her commit öncesi):**

```bash
# grep ile 6 kuralı doğrula
grep -rn "VPN_MTU = " mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/  # = 1400 olmalı
grep -rn "readFirstPacket\|parseFirstPacket" mobile/android/app/src/main/kotlin/  # YOK olmalı
grep -rn "clientSocket.localPort" mobile/android/app/src/main/kotlin/  # YOK olmalı
grep -rn "key.attach" mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/  # VAR olmalı
grep -rn "getUid(session.remotePort)" mobile/android/app/src/main/kotlin/  # YOK olmalı
grep -rn "addDisallowedApplication" mobile/android/app/src/main/kotlin/  # YOK olmalı
```

---

## 1. WORKTREE SETUP

### 1.1 Komutlar (sırasıyla)

```bash
# Mevcut e2ee-app repo'sundan yeni worktree
git -C C:\repos\e2ee-app worktree add C:\repos\e2ee-app-pr-s14item1 -b feat/sprint-14-vpn-rewrite main

# Yeni worktree'ye geç
cd C:\repos\e2ee-app-pr-s14item1

# Mevcut sprint-13 worktree'ine DOKUNMA
# C:\repos\e2ee-app-pr-s13item1 → OKUNMAMALI bile (sadece referans için)

# Branch doğrula
git branch --show-current
# Beklenen: feat/sprint-14-vpn-rewrite

git rev-parse HEAD
# Beklenen: 4877098c5361690e21fe67f07b1e42ccf21ece31 (main HEAD)
```

### 1.2 Worktree temizleme + Flutter setup

```bash
# Eski build artifact'lerini temizle
flutter clean

# Bağımlılıkları çek
flutter pub get
```

### 1.3 Spec dosyası yolu

Bu spec'in **kopyalanacağı** yer:
```
C:\repos\e2ee-app-pr-s14item1\docs\sprint-14\spec.md
```

Bunu oluştur:
```bash
mkdir -p docs/sprint-14
```

---

## 2. ANDROID MANIFEST.XML

### 2.1 Dosya yolu
`mobile/android/app/src/main/AndroidManifest.xml`

### 2.2 Güncellenecek bölüm

`<application>` tag'i içinde, mevcut `<service>` tag'lerinin yanına ekle:

```xml
<service
    android:name=".vpn.OpenE2eeVpnService"
    android:exported="false"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="specialUse">
  <property
      android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
      android:value="vpn_transparent_proxy_for_e2ee_tunnel" />
  <intent-filter>
    <action android:name="android.net.VpnService" />
  </intent-filter>
</service>
```

### 2.3 Önceki Sprint 12.0C/12.0F+ service tag'leri

**12.0C'de** raw `java.net.Socket` tabanlı TCP/UDP forwarder vardı. Bu Sprint 14'te **TAMAMEN SİLİNECEK** (clean-room rewrite). Aşağıdaki service tag'leri varsa **sil**:

```xml
<!-- SİL — clean-room rewrite kapsamı dışı -->
<service android:name=".vpn.tcp.TcpForwarderService" ... />
<service android:name=".vpn.udp.UdpForwarderService" ... />
<service android:name=".vpn.NettyChannelService" ... />
```

### 2.4 Mevcut service tag'leri korunacak

Eğer başka service tag'leri varsa (ör. notification, accessibility), onlara **dokunma**.

### 2.5 Build.gradle'da `applicationIdSuffix` / `minSdk` kontrol

`mobile/android/app/build.gradle` (veya `.kts`):

```kotlin
android {
    defaultConfig {
        // Sprint 14: minSdk = 26 (Android 8+). NotificationChannel + foreground service + startForegroundService için.
        minSdk = 26
        targetSdk = 34
    }
}
```

`minSdk` 26'dan düşükse VPN `Service.startForegroundService` (API 26+) kullanılamaz. **minSdk = 26 olduğunu doğrula.**

---

## 3. VPNCONSTANTS.KT

### 3.1 Dosya yolu
`mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/VPNConstants.kt`

### 3.2 Tam içerik (birebir kopyala)

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/VPNConstants.kt
//
// Sprint 14 — VpnService temel sabit değerler.
// Referans: huolizhuminh/NetWorkPacketCapture VPNConstants.java + Sprint 13.0-fix MTU/timeout dersleri.

package com.opene2ee.opene2ee.vpn.net

import androidx.annotation.Keep

@Keep
object VPNConstants {
    /** TUN IP (loopback). */
    const val TUN_ADDRESS = "10.0.0.2"
    const val TUN_PREFIX = 32

    /** Capture everything route. */
    const val VPN_ROUTE = "0.0.0.0"
    const val VPN_ROUTE_PREFIX = 0

    /**
     * MTU. **KESİNLİKLE 1400** — KURAL 1.
     * 15000 → Android 14 ConnectivityService "Unexpected mtu value: 15000, tun0" → VPN teardown.
     * 4G GTP trailer = 78 byte, Wi-Fi MTU = 1500. 1400 = WireGuard default, safe margin.
     */
    const val VPN_MTU = 1400

    /** DNS sunucuları. Cloudflare primary (1.1.1.1 hızlı + privacy), Google fallback. */
    const val PRIMARY_DNS = "1.1.1.1"
    const val SECONDARY_DNS = "8.8.8.8"

    /** TUN session name (system VPN UI'da görünür). */
    const val TUN_SESSION_NAME = "OpenE2eeTunnel"

    /** NAT session üst sınırı. */
    const val MAX_SESSION_COUNT = 64

    /** Idle session timeout (milisaniye — referans repo uyumlu). */
    const val SESSION_TIME_OUT_MS: Long = 60_000L  // 60 saniye

    /** Paket okuma/yazma buffer boyutu. 32767 = max IP packet + margin. */
    const val PACKET_SIZE = 32767

    /** Foreground notification. */
    const val NOTIFICATION_CHANNEL_ID = "opene2ee.vpn.tunnel"
    const val NOTIFICATION_ID = 0x5650_4E4E  // 'VPNN'

    /** Selector poll interval. */
    const val SELECTOR_TIMEOUT_MS = 50L
    const val READ_LOOP_SLEEP_MS = 10L

    /** SharedPreferences. */
    const val VPN_SP_NAME = "vpn_config"
    const val DEFAULT_PACKAGE_ID = "default_package_id"
}
```

---

## 4. MYLRUCACHE.KT

### 4.1 Dosya yolu
`mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/MyLRUCache.kt`

### 4.2 Tam içerik

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/MyLRUCache.kt
//
// Sprint 14 — LRU cache (portKey → session/tunnel).
// Referans: huolizhuminh/NetWorkPacketCache MyLRUCache.java (LinkedHashMap accessOrder=true).

package com.opene2ee.opene2ee.vpn.net

import androidx.annotation.Keep
import java.util.LinkedHashMap

/**
 * LinkedHashMap accessOrder=true LRU cache. Eldest entry evicted → callback.
 *
 * @param maxSize eviction threshold (entry count).
 * @param callback invoked when eldest entry is evicted.
 */
@Keep
class MyLRUCache<K, V>(
    private val maxSize: Int,
    private val callback: CleanupCallback<V> = NoOpCallback()
) : LinkedHashMap<K, V>(maxSize + 1, 1f, true) {

    @Keep
    interface CleanupCallback<V> {
        fun cleanUp(value: V)
    }

    @Keep
    private class NoOpCallback<V> : CleanupCallback<V> {
        override fun cleanUp(value: V) {}
    }

    @Keep
    override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean {
        if (size > maxSize) {
            callback.cleanUp(eldest.value)
            return true
        }
        return false
    }
}
```

**Not:** Sprint 13.0'da `MyLRUCache` `MyLRUCache<Int, TcpTunnel>` şeklinde, callback'siz kullanıldı. Bu Sprint 14'te her iki imza da geçerli (default `NoOpCallback`).

---

## 5. NATSESSION + NATSESSIONMANAGER

### 5.1 NatSession.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/nat/NatSession.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/nat/NatSession.kt
//
// Sprint 14 — NAT session data class.
// Referans: huolizhuminh/NetWorkPacketCapture NatSession.java.

package com.opene2ee.opene2ee.vpn.nat

import androidx.annotation.Keep

@Keep
class NatSession {
    @Keep
    companion object {
        const val TCP = 1
        const val UDP = 2
    }

    /** Remote IP (int — IPv4 4 byte packed). Reference repo ile uyumlu. */
    @Keep var remoteIp: Int = 0

    /** Remote port (Int 0..65535). */
    @Keep var remotePort: Int = 0

    /**
     * Local port (app's source port). **MUTLAKA** createSession'da
     * portKey ile set edilir (KURAL 5 — PortHostService UID lookup buna bağlı).
     */
    @Keep var localPort: Int = 0

    /** TCP veya UDP (NatSession.TCP / NatSession.UDP). */
    @Keep var type: Int = 0

    /** Son paket zamanı (clearExpiredSessions bu + timeout'a bakar). */
    @Keep var lastRefreshTime: Long = 0L

    /** Toplam gönderilen paket sayısı. */
    @Keep var packetSent: Int = 0

    /** Toplam gönderilen byte. */
    @Keep var bytesSent: Long = 0L

    /** HTTP ise true. */
    @Keep var isHttp: Boolean = false

    /** HTTPS ise true. */
    @Keep var isHttpsSession: Boolean = false

    /** Hostname (parse edilmiş — SNI veya Host header). */
    @Keep var remoteHost: String? = null

    /** HTTP method (GET/POST/etc). */
    @Keep var method: String? = null

    /** Full request URL. */
    @Keep var requestUrl: String? = null

    /** URL path. */
    @Keep var pathUrl: String? = null

    /** UID (kullanıcı uid'si — hangi app bu session'ı açtı). */
    @Keep var uid: Int = -1

    override fun toString(): String {
        return "NatSession{type=$type, remote=${remoteIp}:${remotePort}, local=${localPort}, packetSent=$packetSent}"
    }
}
```

### 5.2 NatSessionManager.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/nat/NatSessionManager.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/nat/NatSessionManager.kt
//
// Sprint 14 — NAT session tracking (portKey → NatSession).
// Referans: huolizhuminh/NetWorkPacketCapture NatSessionManager.java.

package com.opene2ee.opene2ee.vpn.nat

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.net.VPNConstants
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Keep
object NatSessionManager {
    @Keep
    private const val TAG = "NatSessionManager"

    /** portKey (Int) → NatSession. Thread-safe. */
    @Keep
    private val sessions = ConcurrentHashMap<Int, NatSession>()

    @Keep
    private val sweeper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NatSession-Sweeper").apply { isDaemon = true }
    }

    init {
        sweeper.scheduleWithFixedDelay(
            ::clearExpiredSessions,
            60_000, 60_000, TimeUnit.MILLISECONDS
        )
    }

    @Keep
    fun getSession(portKey: Int): NatSession? = sessions[portKey]

    /**
     * Yeni session oluştur veya mevcut session'ı güncelle.
     *
     * **KRİTİK:** `localPort = portKey` set et (KURAL 5). Bu olmadan
     * PortHostService.refreshSessionInfo `getUid(session.localPort)`
     * -1 döndürür.
     */
    @Keep
    fun createSession(
        portKey: Int,
        remoteIp: Int,
        remotePort: Int,
        type: Int
    ): NatSession {
        val session = NatSession().apply {
            this.remoteIp = remoteIp
            this.remotePort = remotePort
            this.localPort = portKey          // ← KURAL 5: ASLA atla
            this.type = type
            this.lastRefreshTime = System.currentTimeMillis()
        }
        sessions[portKey] = session
        VPNLog.d(TAG, "createSession: portKey=$portKey, remote=${ipToString(remoteIp)}:${remotePort}, type=$type")
        return session
    }

    @Keep
    fun removeSession(portKey: Int) {
        sessions.remove(portKey)
    }

    @Keep
    fun clearAllSession() {
        sessions.clear()
        VPNLog.d(TAG, "clearAllSession")
    }

    @Keep
    fun size(): Int = sessions.size

    @Keep
    fun snapshot(): Collection<NatSession> = sessions.values

    /**
     * 60 sn'den eski session'ları temizle.
     * Reference repo'daki clearExpiredSessions ile birebir aynı.
     */
    @Keep
    fun clearExpiredSessions() {
        val now = System.currentTimeMillis()
        val it = sessions.entries.iterator()
        var removed = 0
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastRefreshTime > VPNConstants.SESSION_TIME_OUT_MS) {
                it.remove()
                removed++
            }
        }
        if (removed > 0) {
            VPNLog.d(TAG, "clearExpiredSessions: removed=$removed, remaining=${sessions.size}")
        }
    }

    /** Helper — debug log için IP'yi human-readable string'e çevir. */
    @Keep
    private fun ipToString(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }
}
```

---

## 6. PACKET + IPHEADER + TCPHEADER + UDPHEADER

### 6.1 Packet.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/Packet.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/Packet.kt
//
// Sprint 14 — Paket veri yapısı (IP+TCP/UDP header'lar shared byte[] üzerinde).
// Referans: huolizhuminh/NetWorkPacketCapture Packet.java + IPHeader/TCPHeader/UDPHeader.java.

package com.opene2ee.opene2ee.vpn

import androidx.annotation.Keep

@Keep
class Packet {
    @Keep var mData: ByteArray = ByteArray(0)
    @Keep var mOffset: Int = 0

    @Keep constructor()

    @Keep
    constructor(data: ByteArray, offset: Int) {
        this.mData = data
        this.mOffset = offset
    }
}
```

### 6.2 IPHeader.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/IPHeader.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/IPHeader.kt
//
// Sprint 14 — IPv4 header parser/mutator.
// Referans: huolizhuminh/NetWorkPacketCapture IPHeader.java (byte[] tabanlı).

package com.opene2ee.opene2ee.vpn.tcpip

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.util.CommonMethods

@Keep
class IPHeader {
    @Keep
    companion object {
        const val TCP: Byte = 6
        const val UDP: Byte = 17
        const val ICMP: Byte = 1

        const val OFFSET_VER_IHL = 0      // version(4) + IHL(4)
        const val OFFSET_TOS = 1
        const val OFFSET_TLEN = 2         // total length (16)
        const val OFFSET_IDENT = 4
        const val OFFSET_FLAGS_FO = 6
        const val OFFSET_TTL = 8
        const val OFFSET_PROTO = 9        // protocol
        const val OFFSET_CRC = 10
        const val OFFSET_SRC_IP = 12      // 4 byte
        const val OFFSET_DST_IP = 16      // 4 byte
        const val OFFSET_OP_PAD = 20      // options
    }

    @Keep var mData: ByteArray = ByteArray(0)
    @Keep var mOffset: Int = 0

    @Keep constructor()

    @Keep
    constructor(data: ByteArray, offset: Int) {
        mData = data
        mOffset = offset
    }

    /** IHL * 4 = header length in bytes. */
    @Keep
    fun getHeaderLength(): Int {
        return (mData[mOffset + OFFSET_VER_IHL].toInt() and 0x0F) * 4
    }

    @Keep
    fun setHeaderLength(value: Int) {
        mData[mOffset + OFFSET_VER_IHL] = ((4 shl 4) or (value / 4)).toByte()
    }

    @Keep
    fun getTotalLength(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_TLEN).toInt() and 0xFFFF
    }

    @Keep
    fun setTotalLength(value: Int) {
        CommonMethods.writeShort(mData, mOffset + OFFSET_TLEN, value.toShort())
    }

    @Keep
    fun getProtocol(): Byte {
        return mData[mOffset + OFFSET_PROTO]
    }

    @Keep
    fun setProtocol(value: Byte) {
        mData[mOffset + OFFSET_PROTO] = value
    }

    /** Total length - header length = payload (TCP/UDP packet) length. */
    @Keep
    fun getDataLength(): Int {
        return getTotalLength() - getHeaderLength()
    }

    @Keep
    fun getSourceIP(): Int {
        return CommonMethods.readInt(mData, mOffset + OFFSET_SRC_IP)
    }

    @Keep
    fun setSourceIP(value: Int) {
        CommonMethods.writeInt(mData, mOffset + OFFSET_SRC_IP, value)
    }

    @Keep
    fun getDestinationIP(): Int {
        return CommonMethods.readInt(mData, mOffset + OFFSET_DST_IP)
    }

    @Keep
    fun setDestinationIP(value: Int) {
        CommonMethods.writeInt(mData, mOffset + OFFSET_DST_IP, value)
    }

    override fun toString(): String {
        return "IPHeader{src=${ipToString(getSourceIP())}, dst=${ipToString(getDestinationIP())}, proto=${getProtocol()}, hlen=${getHeaderLength()}}"
    }

    @Keep
    private fun ipToString(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }
}
```

### 6.3 TCPHeader.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/TCPHeader.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/TCPHeader.kt
//
// Sprint 14 — TCP header parser/mutator.
// Referans: huolizhuminh/NetWorkPacketCapture TCPHeader.java.

package com.opene2ee.opene2ee.vpn.tcpip

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.util.CommonMethods

@Keep
class TCPHeader {
    @Keep
    companion object {
        const val OFFSET_SRC_PORT = 0
        const val OFFSET_DST_PORT = 2
        const val OFFSET_SEQ = 4
        const val OFFSET_ACK = 8
        const val OFFSET_LEN_RES = 12      // 4-bit length + 4-bit reserved
        const val OFFSET_FLAG = 13
        const val OFFSET_WIN = 14
        const val OFFSET_CRC = 16
        const val OFFSET_URP = 18

        const val FIN: Int = 1
        const val SYN: Int = 2
        const val RST: Int = 4
        const val PSH: Int = 8
        const val ACK: Int = 16
        const val URG: Int = 32
    }

    @Keep var mData: ByteArray = ByteArray(0)
    @Keep var mOffset: Int = 0

    @Keep constructor()

    @Keep
    constructor(data: ByteArray, offset: Int) {
        mData = data
        mOffset = offset
    }

    @Keep
    fun getHeaderLength(): Int {
        val lenres = mData[mOffset + OFFSET_LEN_RES].toInt() and 0xFF
        return (lenres shr 4) * 4
    }

    @Keep
    fun getSourcePort(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_SRC_PORT).toInt() and 0xFFFF
    }

    @Keep
    fun setSourcePort(value: Int) {
        CommonMethods.writeShort(mData, mOffset + OFFSET_SRC_PORT, (value and 0xFFFF).toShort())
    }

    @Keep
    fun getDestinationPort(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_DST_PORT).toInt() and 0xFFFF
    }

    @Keep
    fun setDestinationPort(value: Int) {
        CommonMethods.writeShort(mData, mOffset + OFFSET_DST_PORT, (value and 0xFFFF).toShort())
    }

    @Keep
    fun getFlag(): Byte {
        return mData[mOffset + OFFSET_FLAG]
    }

    override fun toString(): String {
        val f = getFlag().toInt() and 0xFF
        return "TCPHeader{sp=${getSourcePort()}, dp=${getDestinationPort()}, flag=${
            (if (f and SYN != 0) "SYN" else "") +
            (if (f and ACK != 0) "ACK" else "") +
            (if (f and PSH != 0) "PSH" else "") +
            (if (f and FIN != 0) "FIN" else "") +
            (if (f and RST != 0) "RST" else "")
        }}"
    }
}
```

### 6.4 UDPHeader.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/UDPHeader.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/UDPHeader.kt
//
// Sprint 14 — UDP header parser/mutator.
// Referans: huolizhuminh/NetWorkPacketCapture UDPHeader.java.

package com.opene2ee.opene2ee.vpn.tcpip

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.util.CommonMethods

@Keep
class UDPHeader {
    @Keep
    companion object {
        const val OFFSET_SRC_PORT = 0
        const val OFFSET_DST_PORT = 2
        const val OFFSET_TLEN = 4
        const val OFFSET_CRC = 6
    }

    @Keep var mData: ByteArray = ByteArray(0)
    @Keep var mOffset: Int = 0

    @Keep constructor()

    @Keep
    constructor(data: ByteArray, offset: Int) {
        mData = data
        mOffset = offset
    }

    @Keep
    fun getSourcePort(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_SRC_PORT).toInt() and 0xFFFF
    }

    @Keep
    fun setSourcePort(value: Int) {
        CommonMethods.writeShort(mData, mOffset + OFFSET_SRC_PORT, (value and 0xFFFF).toShort())
    }

    @Keep
    fun getDestinationPort(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_DST_PORT).toInt() and 0xFFFF
    }

    @Keep
    fun setDestinationPort(value: Int) {
        CommonMethods.writeShort(mData, mOffset + OFFSET_DST_PORT, (value and 0xFFFF).toShort())
    }

    @Keep
    fun getHeaderLength(): Int = 8  // UDP header always 8 bytes

    @Keep
    fun getTotalLength(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_TLEN).toInt() and 0xFFFF
    }

    override fun toString(): String {
        return "UDPHeader{sp=${getSourcePort()}, dp=${getDestinationPort()}}"
    }
}
```

---

## 7. VPNLOG + DEBUGLOG

### 7.1 VPNLog.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/VPNLog.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/VPNLog.kt
//
// Sprint 14 — R8-safe log wrapper. R8 release build'de Log.d/v strip
// edebilir, ama wrapper'lar çağrıldığı için sınıf adları keep edilir.
// Referans: huolizhuminh/NetWorkPacketCapture VPNLog.java.

package com.opene2ee.opene2ee.vpn.util

import android.util.Log
import androidx.annotation.Keep

@Keep
object VPNLog {
    @Keep
    @JvmStatic
    var isMakeDebugLog: Boolean = true

    @Keep
    @JvmStatic
    fun d(tag: String, message: String) {
        if (isMakeDebugLog) Log.d(tag, message)
    }

    @Keep
    @JvmStatic
    fun i(tag: String, message: String) {
        if (isMakeDebugLog) Log.i(tag, message)
    }

    @Keep
    @JvmStatic
    fun w(tag: String, message: String) {
        if (isMakeDebugLog) Log.w(tag, message)
    }

    @Keep
    @JvmStatic
    fun w(tag: String, message: String, e: Throwable) {
        if (isMakeDebugLog) Log.w(tag, message, e)
    }

    @Keep
    @JvmStatic
    fun e(tag: String, message: String) {
        if (isMakeDebugLog) Log.e(tag, message)
    }
}
```

### 7.2 DebugLog.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/DebugLog.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/DebugLog.kt
//
// Sprint 14 — Formatlı debug log.
// Referans: huolizhuminh/NetWorkPacketCapture DebugLog.java.

package com.opene2ee.opene2ee.vpn.util

import androidx.annotation.Keep

@Keep
object DebugLog {
    @Keep
    private const val DEFAULT_TAG = "OpenE2eeVpn"

    @Keep
    @JvmStatic
    fun i(format: String, vararg args: Any?) {
        if (VPNLog.isMakeDebugLog) {
            android.util.Log.i(DEFAULT_TAG, formatString(format, args))
        }
    }

    @Keep
    @JvmStatic
    fun d(format: String, vararg args: Any?) {
        if (VPNLog.isMakeDebugLog) {
            android.util.Log.d(DEFAULT_TAG, formatString(format, args))
        }
    }

    @Keep
    @JvmStatic
    fun w(format: String, vararg args: Any?) {
        if (VPNLog.isMakeDebugLog) {
            android.util.Log.w(DEFAULT_TAG, formatString(format, args))
        }
    }

    @Keep
    @JvmStatic
    fun e(format: String, vararg args: Any?) {
        if (VPNLog.isMakeDebugLog) {
            android.util.Log.e(DEFAULT_TAG, formatString(format, args))
        }
    }

    @Keep
    private fun formatString(format: String, args: Array<out Any?>): String {
        return if (args.isEmpty()) format else String.format(format, *args)
    }
}
```

### 7.3 CommonMethods.kt (checksum hesaplama — KULLANILAN)

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/CommonMethods.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/CommonMethods.kt
//
// Sprint 14 — Byte yardımcıları + IP/TCP/UDP checksum.
// Referans: huolizhuminh/NetWorkPacketCapture CommonMethods.java.

package com.opene2ee.opene2ee.vpn.util

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.tcpip.IPHeader
import com.opene2ee.opene2ee.vpn.tcpip.TCPHeader
import com.opene2ee.opene2ee.vpn.tcpip.UDPHeader

@Keep
object CommonMethods {

    @Keep
    @JvmStatic
    fun readShort(data: ByteArray, offset: Int): Short {
        return (((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)).toShort()
    }

    @Keep
    @JvmStatic
    fun writeShort(data: ByteArray, offset: Int, value: Short) {
        data[offset] = ((value.toInt() shr 8) and 0xFF).toByte()
        data[offset + 1] = (value.toInt() and 0xFF).toByte()
    }

    @Keep
    @JvmStatic
    fun readInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    @Keep
    @JvmStatic
    fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    @Keep
    @JvmStatic
    fun ipStringToInt(ip: String): Int {
        val parts = ip.split(".")
        return (parts[0].toInt() shl 24) or
                (parts[1].toInt() shl 16) or
                (parts[2].toInt() shl 8) or
                parts[3].toInt()
    }

    @Keep
    @JvmStatic
    fun ipIntToString(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    @Keep
    @JvmStatic
    fun checksum(sum: Long, buf: ByteArray, offset: Int, len: Int): Short {
        var s = sum + getSum(buf, offset, len)
        while ((s shr 16) > 0) {
            s = (s and 0xFFFF) + (s shr 16)
        }
        return (s.inv() and 0xFFFF).toShort()
    }

    @Keep
    @JvmStatic
    fun getSum(buf: ByteArray, offset: Int, len: Int): Long {
        var sum = 0L
        var off = offset
        var l = len
        while (l > 1) {
            sum += readShort(buf, off).toInt() and 0xFFFF
            off += 2
            l -= 2
        }
        if (l > 0) {
            sum += (buf[off].toInt() and 0xFF) shl 8
        }
        return sum
    }

    @Keep
    @JvmStatic
    fun ComputeIPChecksum(ipHeader: IPHeader) {
        val oldCrc = CommonMethods.readShort(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_CRC)
        CommonMethods.writeShort(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_CRC, 0)
        val newCrc = checksum(0, ipHeader.mData, ipHeader.mOffset, ipHeader.getHeaderLength())
        CommonMethods.writeShort(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_CRC, newCrc)
    }

    @Keep
    @JvmStatic
    fun ComputeTCPChecksum(ipHeader: IPHeader, tcpHeader: TCPHeader) {
        ComputeIPChecksum(ipHeader)
        val ipDataLen = ipHeader.getDataLength()
        if (ipDataLen < 0) return
        // Pseudo-header sum: src+dst IP (8) + protocol (1) + TCP length (2)
        var sum = getSum(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_SRC_IP, 8)
        sum += ipHeader.getProtocol().toInt() and 0xFF
        sum += ipDataLen
        val oldCrc = CommonMethods.readShort(tcpHeader.mData, tcpHeader.mOffset + TCPHeader.OFFSET_CRC)
        CommonMethods.writeShort(tcpHeader.mData, tcpHeader.mOffset + TCPHeader.OFFSET_CRC, 0)
        val newCrc = checksum(sum, tcpHeader.mData, tcpHeader.mOffset, ipDataLen)
        CommonMethods.writeShort(tcpHeader.mData, tcpHeader.mOffset + TCPHeader.OFFSET_CRC, newCrc)
    }

    @Keep
    @JvmStatic
    fun ComputeUDPChecksum(ipHeader: IPHeader, udpHeader: UDPHeader) {
        ComputeIPChecksum(ipHeader)
        val ipDataLen = ipHeader.getDataLength()
        if (ipDataLen < 0) return
        var sum = getSum(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_SRC_IP, 8)
        sum += ipHeader.getProtocol().toInt() and 0xFF
        sum += ipDataLen
        val oldCrc = CommonMethods.readShort(udpHeader.mData, udpHeader.mOffset + UDPHeader.OFFSET_CRC)
        CommonMethods.writeShort(udpHeader.mData, udpHeader.mOffset + UDPHeader.OFFSET_CRC, 0)
        val newCrc = checksum(sum, udpHeader.mData, udpHeader.mOffset, ipDataLen)
        CommonMethods.writeShort(udpHeader.mData, udpHeader.mOffset + UDPHeader.OFFSET_CRC, newCrc)
    }
}
```

### 7.4 AppDebug.kt + ThreadProxy.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/AppDebug.kt`

```kotlin
package com.opene2ee.opene2ee.vpn.util

import androidx.annotation.Keep

@Keep
object AppDebug {
    @Keep
    const val IS_DEBUG: Boolean = true
}
```

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/ThreadProxy.kt`

```kotlin
package com.opene2ee.opene2ee.vpn.util

import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Keep
object ThreadProxy {
    @Keep
    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 4,
        10L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(1024),
        ThreadFactory { r ->
            Thread(r, "ThreadProxy-${ThreadCounter.incrementAndGet()}").apply { isDaemon = true }
        }
    )

    @Keep
    fun execute(r: Runnable) {
        executor.execute(r)
    }

    @Keep
    private object ThreadCounter {
        val c = AtomicInteger(0)
        fun incrementAndGet(): Int = c.incrementAndGet()
    }
}
```

---

## 8. PROCESSPARSE — PORTHOSTSERVICE + NETFILEMANAGER

### 8.1 NetInfo (yardımcı sınıf)

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/NetInfo.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/NetInfo.kt
//
// Sprint 14 — /proc/net/(tcp|tcp6|udp|udp6) entry holder.

package com.opene2ee.opene2ee.vpn.processparse

import androidx.annotation.Keep

@Keep
class NetInfo {
    @Keep var sourPort: Int = 0   // local port (kaynak)
    @Keep var port: Int = 0       // remote port
    @Keep var ip: Long = 0
    @Keep var address: String = ""
    @Keep var uid: Int = 0
    @Keep var type: Int = 0       // TYPE_TCP=0, TYPE_TCP6=1, TYPE_UDP=2, TYPE_UDP6=3
}
```

### 8.2 NetFileManager.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/NetFileManager.kt`

```kotlin
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
     * /proc/net/* dosyalarını oku, parse et, port → uid map'i doldur.
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
            // /proc/net/* read may fail on some devices; ignore.
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
```

### 8.3 PortHostService.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/PortHostService.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/PortHostService.kt
//
// Sprint 14 — UID lookup background service.
// Referans: huolizhuminh/NetWorkPacketCapture PortHostService.java + PR #33 fix.

package com.opene2ee.opene2ee.vpn.processparse

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.nat.NatSession
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Keep
class PortHostService : Service() {
    @Keep
    companion object {
        private const val TAG = "PortHostService"
        @Keep
        @Volatile
        private var instance: PortHostService? = null

        @Keep
        @JvmStatic
        fun getInstance(): PortHostService? = instance

        @Keep
        @JvmStatic
        fun startParse(context: Context) {
            val intent = Intent(context, PortHostService::class.java)
            context.startService(intent)
        }

        @Keep
        @JvmStatic
        fun stopParse(context: Context) {
            val intent = Intent(context, PortHostService::class.java)
            context.stopService(intent)
        }
    }

    @Keep
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "PortHostService-Refresh").apply { isDaemon = true }
    }

    @Keep
    private var isRefresh = false

    @Keep
    private val refreshRunnable = Runnable { refreshSessionInfo() }

    override fun onCreate() {
        super.onCreate()
        NetFileManager.getInstance().init(applicationContext)
        instance = this
        // Her 5 saniyede bir refresh (NAT session UID lookup)
        executor.scheduleWithFixedDelay(refreshRunnable, 5, 5, TimeUnit.SECONDS)
        VPNLog.d(TAG, "PortHostService started")
    }

    override fun onDestroy() {
        executor.shutdown()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Tüm session'ların UID'sini /proc/net'ten resolve et.
     *
     * **KRİTİK — KURAL 5:** `session.localPort` kullan (remotePort DEĞİL).
     * /proc/net/tcp "local_address:port" kolonunda port var, remote kolonunda değil.
     */
    @Keep
    fun refreshSessionInfo() {
        if (isRefresh) return
        val allSessions: List<NatSession> = NatSessionManager.snapshot().toList()
        if (allSessions.isEmpty()) return

        isRefresh = true
        try {
            NetFileManager.getInstance().refresh()
            for (session in allSessions) {
                // ───── KURAL 5: localPort kullan ─────
                val uid = NetFileManager.getInstance().getUid(session.localPort)
                if (uid != null && session.uid == -1) {
                    session.uid = uid
                    VPNLog.d(TAG, "UID lookup: localPort=${session.localPort}, uid=$uid")
                }
            }
        } catch (e: Exception) {
            VPNLog.e(TAG, "refreshSessionInfo failed: ${e.message}")
        } finally {
            isRefresh = false
        }
    }
}
```

---

## 9. PROXY — TCPPROXYSERVER + TCPTUNNEL

### 9.1 TcpTunnel.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/TcpTunnel.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/TcpTunnel.kt
//
// Sprint 14 — TCP tunnel (clientSocket ↔ remoteSocket bidirectional pipe).
// Referans: huolizhuminh/NetWorkPacketCapture TcpTunnel.java (NIO) → Sprint 14 raw Socket+Thread.

package com.opene2ee.opene2ee.vpn.proxy

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Keep
class TcpTunnel(
    private val clientSocket: Socket,
    private val remoteSocket: Socket,
    private val portKey: Int
) : Runnable {
    @Keep
    companion object {
        private const val TAG = "TcpTunnel"
    }

    @Keep
    private val stopped = AtomicBoolean(false)

    @Keep
    override fun run() {
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val remoteIn = remoteSocket.getInputStream()
            val remoteOut = remoteSocket.getOutputStream()

            // 2 thread: client→remote ve remote→client eşzamanlı
            val forwardExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "TcpTunnel-Forward-$portKey").apply { isDaemon = true }
            }
            val reverseExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "TcpTunnel-Reverse-$portKey").apply { isDaemon = true }
            }

            // Client → Remote (app bize yazıyor, biz remote'a yazıyoruz)
            forwardExecutor.execute {
                try {
                    val buf = ByteArray(8192)
                    while (!stopped.get()) {
                        val n = clientIn.read(buf)
                        if (n <= 0) break
                        remoteOut.write(buf, 0, n)
                        remoteOut.flush()
                    }
                } catch (e: Exception) {
                    VPNLog.d(TAG, "forward read/write exception: ${e.message}")
                } finally {
                    dispose()
                }
            }

            // Remote → Client (remote bize yazıyor, biz app'e yazıyoruz)
            reverseExecutor.execute {
                try {
                    val buf = ByteArray(8192)
                    while (!stopped.get()) {
                        val n = remoteIn.read(buf)
                        if (n <= 0) break
                        clientOut.write(buf, 0, n)
                        clientOut.flush()
                    }
                } catch (e: Exception) {
                    VPNLog.d(TAG, "reverse read/write exception: ${e.message}")
                } finally {
                    dispose()
                }
            }
        } catch (e: Exception) {
            VPNLog.e(TAG, "TcpTunnel run exception: ${e.message}", e)
            dispose()
        }
    }

    @Keep
    fun dispose() {
        if (stopped.compareAndSet(false, true)) {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket.close() } catch (_: Exception) {}
            NatSessionManager.removeSession(portKey)
            VPNLog.d(TAG, "TcpTunnel disposed: portKey=$portKey")
        }
    }
}
```

### 9.2 TcpProxyServer.kt

**Dosfa yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/TcpProxyServer.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/TcpProxyServer.kt
//
// Sprint 14 — Local TCP proxy (loopback ephemeral port).
// Referans: huolizhuminh/NetWorkPacketCapture TcpProxyServer.java + Sprint 13.0-fix portKey/port bug fix.

package com.opene2ee.opene2ee.vpn.proxy

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.nat.NatSession
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

@Keep
class TcpProxyServer(private val initialPort: Int) {

    @Keep
    companion object {
        private const val TAG = "TcpProxyServer"
    }

    @Keep
    @Volatile
    var isStopped: Boolean = false
        private set

    @Keep
    @Volatile
    var port: Int = 0
        private set

    @Keep
    private var serverSocket: ServerSocket? = null

    @Keep
    private val workerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "TcpTunnel-Worker-${System.nanoTime()}").apply { isDaemon = true }
    }

    @Keep
    fun start() {
        serverSocket = ServerSocket()
        serverSocket!!.bind(InetSocketAddress(initialPort))
        this.port = serverSocket!!.localPort
        VPNLog.d(TAG, "TcpProxyServer started, listening on 0.0.0.0:${this.port}")

        val acceptThread = Thread({
            try {
                while (!isStopped) {
                    val clientSocket: Socket = try {
                        serverSocket!!.accept()
                    } catch (e: SocketException) {
                        if (isStopped) break else throw e
                    }
                    handleNewClient(clientSocket)
                }
            } catch (e: Exception) {
                VPNLog.e(TAG, "accept loop exception: ${e.message}", e)
            }
        }, "TcpProxyServer-Accept").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Yeni accept edilen client'ı handle et.
     *
     * **KURAL 2 + 3** (KESİNLİKLE uyulacak):
     * - `readFirstPacket` / `parseFirstPacket` KULLANMA (kernel strip eder)
     * - `portKey = clientSocket.port` (remote/peer port = app's source port)
     *   ASLA `clientSocket.localPort` (proxy's ephemeral port, NAT key DEĞİL)
     */
    @Keep
    private fun handleNewClient(clientSocket: Socket) {
        val vpnService = OpenE2eeVpnService.activeInstance
        if (vpnService == null) {
            VPNLog.e(TAG, "OpenE2eeVpnService.activeInstance == null")
            try { clientSocket.close() } catch (_: Exception) {}
            return
        }

        // protect() — VPN TUN'u bypass, gerçek NIC kullan
        if (!vpnService.protect(clientSocket)) {
            VPNLog.e(TAG, "protect() returned false for client socket")
            try { clientSocket.close() } catch (_: Exception) {}
            return
        }
        VPNLog.d(TAG, "protect() returned true for client socket")

        // ───── KURAL 3: portKey = clientSocket.port ─────
        // java.net.Socket.getPort() = the remote port the socket is connected to.
        // Loopback'te proxy'nin "remote"'ı = app. Bu app'in source port'u.
        val portKey = clientSocket.port
        val session: NatSession? = NatSessionManager.getSession(portKey)
        if (session == null) {
            VPNLog.w(TAG, "No session for portKey=$portKey (VpnService did not register this TCP flow)")
            try { clientSocket.close() } catch (_: Exception) {}
            return
        }
        val remoteIp = session.remoteIp
        val remotePort = session.remotePort
        VPNLog.d(TAG, "handleNewClient: portKey=$portKey -> ${ipToString(remoteIp)}:$remotePort")

        // Remote'a bağlan (yine protect gerekli)
        val remoteSocket = Socket()
        if (!vpnService.protect(remoteSocket)) {
            VPNLog.e(TAG, "protect() returned false for remote socket")
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket.close() } catch (_: Exception) {}
            return
        }
        try {
            val remoteAddr = InetAddress.getByAddress(
                byteArrayOf(
                    ((remoteIp shr 24) and 0xFF).toByte(),
                    ((remoteIp shr 16) and 0xFF).toByte(),
                    ((remoteIp shr 8) and 0xFF).toByte(),
                    (remoteIp and 0xFF).toByte()
                )
            )
            remoteSocket.connect(InetSocketAddress(remoteAddr, remotePort), 10_000)
            VPNLog.d(TAG, "connect() returned for ${ipToString(remoteIp)}:$remotePort")
        } catch (e: IOException) {
            VPNLog.e(TAG, "connect to ${ipToString(remoteIp)}:$remotePort failed: ${e.message}")
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket.close() } catch (_: Exception) {}
            return
        }

        val tunnel = TcpTunnel(clientSocket, remoteSocket, portKey)
        workerPool.execute(tunnel)
    }

    @Keep
    fun stop() {
        isStopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        workerPool.shutdownNow()
        VPNLog.d(TAG, "TcpProxyServer stopped")
    }

    @Keep
    private fun ipToString(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }
}
```

---

## 10. PROXY — UDPSERVER + UDPTUNNEL

### 10.1 UdpTunnel.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/UdpTunnel.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/UdpTunnel.kt
//
// Sprint 14 — UDP tunnel (DatagramChannel-based NIO).
// Referans: huolizhuminh/NetWorkPacketCapture UDPTunnel.java.

package com.opene2ee.opene2ee.vpn.proxy

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.Packet
import com.opene2ee.opene2ee.vpn.tcpip.UDPHeader
import com.opene2ee.opene2ee.vpn.util.CommonMethods
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentLinkedQueue

@Keep
class UdpTunnel(
    private val channel: DatagramChannel,
    private val portKey: Int,
    private val responseQueue: ConcurrentLinkedQueue<Packet>,
    private val remoteIp: Int,
    private val remotePort: Int
) {
    @Keep
    companion object {
        private const val TAG = "UdpTunnel"
        private const val HEADER_SIZE = 28  // IP4 (20) + UDP (8)
    }

    @Keep
    @Volatile
    private var disposed = false

    /**
     * İlk paket'i gönder. Paket, IP+UDP header içeriyor (TUN'dan okunmuş).
     * Amacımız: UDP header'ı remote'a göndermek.
     */
    @Keep
    fun sendPacket(packet: Packet) {
        if (disposed) return
        try {
            // IP+UDP header'ı oku, UDP payload'u gönder
            val ipHeader = com.opene2ee.opene2ee.vpn.tcpip.IPHeader(packet.mData, packet.mOffset)
            val udpHeader = UDPHeader(packet.mData, packet.mOffset + ipHeader.getHeaderLength())
            val payloadOffset = packet.mOffset + ipHeader.getHeaderLength() + udpHeader.getHeaderLength()
            val payloadSize = packet.mData.size - payloadOffset
            if (payloadSize <= 0) return

            val buffer = ByteBuffer.wrap(packet.mData, payloadOffset, payloadSize)
            val sent = channel.send(buffer, InetSocketAddress(
                InetAddress.getByAddress(byteArrayOf(
                    ((remoteIp shr 24) and 0xFF).toByte(),
                    ((remoteIp shr 16) and 0xFF).toByte(),
                    ((remoteIp shr 8) and 0xFF).toByte(),
                    (remoteIp and 0xFF).toByte()
                )),
                remotePort
            ))
            VPNLog.d(TAG, "sendPacket: portKey=$portKey, sent=$sent")
        } catch (e: Exception) {
            VPNLog.e(TAG, "sendPacket failed: ${e.message}", e)
        }
    }

    /**
     * Selector thread'inden çağrılır. Remote'tan gelen UDP response'u al,
     * IP+UDP header oluştur, responseQueue'ya ekle (OpenE2eeVpnService TUN'a yazacak).
     *
     * **KRİTİK — KURAL 4:** Bu method key.attachment() olarak selector
     * tarafından çağrılır. Eğer initConnection'da `key.attach(this)`
     * yapılmadıysa bu method hiç çağrılmaz, DNS timeout oluşur.
     */
    @Keep
    fun receivePackets() {
        if (disposed) return
        val receiveBuffer = ByteBuffer.allocate(2048)
        try {
            val remoteAddr = channel.receive(receiveBuffer)
            if (remoteAddr == null) return
            receiveBuffer.flip()
            val payloadSize = receiveBuffer.remaining()
            if (payloadSize <= 0) return

            // IP+UDP header oluştur ve response packet olarak queue'ya ekle
            val packetData = ByteArray(HEADER_SIZE + payloadSize)
            val ipHeader = com.opene2ee.opene2ee.vpn.tcpip.IPHeader(packetData, 0)
            val udpHeader = UDPHeader(packetData, 20)

            ipHeader.setHeaderLength(20)
            ipHeader.setTotalLength(HEADER_SIZE + payloadSize)
            ipHeader.setProtocol(com.opene2ee.opene2ee.vpn.tcpip.IPHeader.UDP)
            // Src = local proxy (sentinel), Dst = client
            // (OpenE2eeVpnService.onUdpPacketReceived zaten reverse swap yapar,
            //  burada sadece TUN'a yazılacak formata getiriyoruz)
            ipHeader.setSourceIP(0)  // placeholder, OpenE2eeVpnService reverse yapacak
            ipHeader.setDestinationIP(0)
            udpHeader.setSourcePort(remotePort)
            udpHeader.setDestinationPort(portKey)

            // Payload'u kopyala
            System.arraycopy(receiveBuffer.array(), receiveBuffer.arrayOffset(), packetData, HEADER_SIZE, payloadSize)

            // Checksum (UDP checksum is optional in IPv4, ama Android bazı cihazlarda drop edebilir)
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader)

            responseQueue.offer(Packet(packetData, 0))
            VPNLog.d(TAG, "receivePackets: portKey=$portKey, bytes=$payloadSize")
        } catch (e: Exception) {
            VPNLog.e(TAG, "receivePackets failed: ${e.message}", e)
        }
    }

    @Keep
    fun dispose() {
        if (!disposed) {
            disposed = true
            try { channel.close() } catch (_: Exception) {}
        }
    }
}
```

### 10.2 UdpServer.kt

**Dosfa yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/UdpServer.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/UdpServer.kt
//
// Sprint 14 — UDP relay (NIO Selector-based).
// Referans: huolizhuminh/NetWorkPacketCapture UDPServer.java + Sprint 13.0-fix key.attach fix.

package com.opene2ee.opene2ee.vpn.proxy

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.Packet
import com.opene2ee.opene2ee.vpn.net.MyLRUCache
import com.opene2ee.opene2ee.vpn.net.VPNConstants
import com.opene2ee.opene2ee.vpn.nat.NatSession
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue

@Keep
class UdpServer(
    private val vpnService: OpenE2eeVpnService,
    private val responseQueue: ConcurrentLinkedQueue<Packet>
) {

    @Keep
    companion object {
        private const val TAG = "UdpServer"
        private const val MAX_UDP_TUNNELS = 64
    }

    @Keep
    @Volatile
    private var isRunning: Boolean = false

    @Keep
    @Volatile
    private var selector: Selector? = null

    @Keep
    private val tunnelCache = MyLRUCache<Int, UdpTunnel>(MAX_UDP_TUNNELS)

    @Keep
    fun start() {
        isRunning = true
        selector = Selector.open()
        val thread = Thread({ runLoop() }, "UdpServer-Selector").apply {
            isDaemon = true
            start()
        }
        VPNLog.d(TAG, "UdpServer started")
    }

    /**
     * OpenE2eeVpnService.onUdpPacketReceived'dan çağrılır.
     * İlk paket: yeni tunnel oluştur. Sonraki paketler: mevcut tunnel'a yaz.
     */
    @Keep
    fun processUdpPacket(packet: Packet, portKey: Int) {
        val tunnel = tunnelCache[portKey]
        if (tunnel == null) {
            val session: NatSession = NatSessionManager.getSession(portKey) ?: return
            val newTunnel = initConnection(session, portKey, packet)
            if (newTunnel != null) {
                tunnelCache[portKey] = newTunnel
                selector?.wakeup()
            }
        } else {
            tunnel.sendPacket(packet)
        }
    }

    /**
     * Yeni UDP akışı başlat.
     *
     * **KURAL 4 (KESİNLİKLE):** `channel.register(...)` sonrası
     * `key.attach(tunnel)` MUTLAKA çağrılır. Yoksa selector runLoop
     * `key.attachment() as? UdpTunnel` null alır, `receivePackets()`
     * hiç çağrılmaz, DNS 15s timeout oluşur.
     */
    @Keep
    private fun initConnection(
        session: NatSession,
        portKey: Int,
        firstPacket: Packet
    ): UdpTunnel? {
        try {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)

            // protect() — TUN bypass
            if (!vpnService.protect(channel.socket())) {
                VPNLog.e(TAG, "protect() returned false for UDP channel")
                channel.close()
                return null
            }
            VPNLog.d(TAG, "protect() returned true for UDP channel, portKey=$portKey")

            val remoteAddress = InetAddress.getByAddress(
                byteArrayOf(
                    ((session.remoteIp shr 24) and 0xFF).toByte(),
                    ((session.remoteIp shr 16) and 0xFF).toByte(),
                    ((session.remoteIp shr 8) and 0xFF).toByte(),
                    (session.remoteIp and 0xFF).toByte()
                )
            )
            channel.connect(InetSocketAddress(remoteAddress, session.remotePort))

            // ───── KURAL 4: tunnel ÖNCE oluştur, sonra register + attach ─────
            val tunnel = UdpTunnel(
                channel, portKey, responseQueue,
                session.remoteIp, session.remotePort
            )
            val key: SelectionKey = channel.register(selector, SelectionKey.OP_READ)
            key.attach(tunnel)  // ← ASLA ATLA

            tunnel.sendPacket(firstPacket)  // ilk paketi hemen yaz
            return tunnel
        } catch (e: Exception) {
            VPNLog.e(TAG, "initConnection failed: ${e.message}", e)
            return null
        }
    }

    @Keep
    private fun runLoop() {
        val sel = selector ?: return
        while (isRunning) {
            try {
                val n = sel.select(VPNConstants.SELECTOR_TIMEOUT_MS)
                if (n > 0) {
                    val keys = sel.selectedKeys()
                    val iter = keys.iterator()
                    while (iter.hasNext()) {
                        val key = iter.next()
                        if (key.isReadable) {
                            // ───── KURAL 4: key.attachment() ASLA null olmamalı ─────
                            val tunnel = key.attachment() as? UdpTunnel
                            tunnel?.receivePackets()
                        }
                        iter.remove()
                    }
                }
                // Idle session cleanup
                NatSessionManager.clearExpiredSessions()
            } catch (e: Exception) {
                VPNLog.e(TAG, "selector loop exception: ${e.message}", e)
            }
        }
    }

    @Keep
    fun closeAllUdpConn() {
        isRunning = false
        selector?.wakeup()
        try { selector?.close() } catch (_: Exception) {}
        for (portKey in tunnelCache.keys.toList()) {
            tunnelCache[portKey]?.dispose()
        }
        tunnelCache.clear()
        VPNLog.d(TAG, "UdpServer closed all UDP conns")
    }
}
```

---

## 11. OPENE2EEVPNSERVICE (FirewallVpnService equivalent)

### 11.1 OpenE2eeVpnService.kt

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/OpenE2eeVpnService.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/OpenE2eeVpnService.kt
//
// Sprint 14 — Ana VpnService entry point.
// Referans: huolizhuminh/NetWorkPacketCapture FirewallVpnService.java + Sprint 13.0 tüm dersler.

package com.opene2ee.opene2ee.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.opene2ee.opene2ee.MainActivity
import com.opene2ee.opene2ee.R
import com.opene2ee.opene2ee.vpn.nat.NatSession
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.net.ProxyConfig
import com.opene2ee.opene2ee.vpn.net.VPNConstants
import com.opene2ee.opene2ee.vpn.processparse.PortHostService
import com.opene2ee.opene2ee.vpn.proxy.TcpProxyServer
import com.opene2ee.opene2ee.vpn.proxy.UdpServer
import com.opene2ee.opene2ee.vpn.tcpip.IPHeader
import com.opene2ee.opene2ee.vpn.tcpip.TCPHeader
import com.opene2ee.opene2ee.vpn.tcpip.UDPHeader
import com.opene2ee.opene2ee.vpn.util.CommonMethods
import com.opene2ee.opene2ee.vpn.util.DebugLog
import com.opene2ee.opene2ee.vpn.util.ThreadProxy
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

@Keep
class OpenE2eeVpnService : VpnService(), Runnable {

    @Keep
    companion object {
        private const val TAG = "OpenE2eeVpnService"

        @Keep
        @Volatile
        var activeInstance: OpenE2eeVpnService? = null
            internal set

        const val METHOD_CHANNEL = "opene2ee/vpn"
    }

    // ───── State (instance-level, companion object'te minimum) ─────
    @Keep
    @Volatile
    private var isRunning: Boolean = false

    @Keep
    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    @Keep
    @Volatile
    private var vpnOutputStream: FileOutputStream? = null

    @Keep
    @Volatile
    private var vpnInputStream: FileInputStream? = null

    @Keep
    @Volatile
    private var localIpInt: Int = 0

    @Keep
    private var vpnThread: Thread? = null

    @Keep
    private val packetBuffer: ByteArray = ByteArray(VPNConstants.PACKET_SIZE)
    @Keep
    private val ipHeader: IPHeader = IPHeader(packetBuffer, 0)
    @Keep
    private val tcpHeader: TCPHeader = TCPHeader(packetBuffer, 20)
    @Keep
    private val udpHeader: UDPHeader = UDPHeader(packetBuffer, 20)

    @Keep
    private val udpQueue: ConcurrentLinkedQueue<Packet> = ConcurrentLinkedQueue()

    @Keep
    private var tcpProxyServer: TcpProxyServer? = null
    @Keep
    private var udpServer: UdpServer? = null

    // ═══ Lifecycle ═══

    @Keep
    override fun onCreate() {
        super.onCreate()
        DebugLog.i("OpenE2eeVpnService onCreate, id=${hashCode()}")

        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, VPNConstants.NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(VPNConstants.NOTIFICATION_ID, notification)
        }

        isRunning = true
        vpnThread = Thread(this, "VPNServiceThread").also { it.start() }
    }

    @Keep
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.i("OpenE2eeVpnService onStartCommand, action=${intent?.action}")
        return START_STICKY
    }

    @Keep
    override fun onDestroy() {
        DebugLog.i("OpenE2eeVpnService onDestroy")
        isRunning = false
        vpnThread?.interrupt()
        dispose()
        super.onDestroy()
    }

    @Keep
    override fun onRevoke() {
        DebugLog.w("OpenE2eeVpnService onRevoke")
        stopSelf()
    }

    /**
     * Sprint 13.0-fix: External synchronous stop entry point.
     * MainActivity "stop" branch önce svc.stopVpn() çağırır, sonra stopService(intent).
     */
    @Keep
    fun stopVpn() {
        DebugLog.i("OpenE2eeVpnService stopVpn called")
        try { dispose() } catch (e: Exception) { DebugLog.e("stopVpn.dispose failed: $e") }
        try { stopSelf() } catch (e: Exception) { DebugLog.e("stopVpn.stopSelf failed: $e") }
    }

    // ═══ Main run loop ═══

    @Keep
    override fun run() {
        try {
            DebugLog.i("OpenE2eeVpnService work thread running")
            activeInstance = this
            waitUntilPrepared()

            udpQueue.clear()
            tcpProxyServer = TcpProxyServer(0).also { it.start() }
            udpServer = UdpServer(this, udpQueue).also { it.start() }
            NatSessionManager.clearAllSession()

            // PR #33 fix: PortHostService henüz instance yoksa startParse
            if (PortHostService.getInstance() == null) {
                PortHostService.startParse(applicationContext)
            }
            DebugLog.i("PortHostService started")

            ProxyConfig.Instance.onVpnStart(this)

            while (isRunning) {
                runVpn()
            }
        } catch (e: InterruptedException) {
            DebugLog.e("OpenE2eeVpnService run interrupted: $e")
        } catch (e: Exception) {
            DebugLog.e("OpenE2eeVpnService run exception: $e")
        } finally {
            DebugLog.i("OpenE2eeVpnService terminated")
            ProxyConfig.Instance.onVpnEnd(this)
            dispose()
        }
    }

    // ═══ VPN kurulumu ve okuma ═══

    @Keep
    @Throws(Exception::class)
    private fun runVpn() {
        vpnInterface = establishVpn()
        startStream()
    }

    @Keep
    @Throws(Exception::class)
    private fun startStream() {
        var size = 0
        vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
        vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)

        while (size != -1 && isRunning) {
            var hasWrite = false
            size = try {
                vpnInputStream!!.read(packetBuffer)
            } catch (e: Exception) {
                DebugLog.e("TUN read failed: $e")
                -1
            }
            if (size > 0) {
                if (tcpProxyServer?.isStopped == true) {
                    try { vpnInputStream!!.close() } catch (_: Exception) {}
                    throw Exception("LocalServer stopped.")
                }
                hasWrite = onIpPacketReceived(ipHeader, size)
            }
            if (!hasWrite) {
                val packet = udpQueue.poll()
                if (packet != null) {
                    val buffer = packet.mData
                    try {
                        vpnOutputStream!!.write(buffer, packet.mOffset, packet.mData.size - packet.mOffset)
                    } catch (e: Exception) {
                        DebugLog.e("TUN write (UDP response) failed: $e")
                    }
                }
            }
            try { Thread.sleep(VPNConstants.READ_LOOP_SLEEP_MS) } catch (_: InterruptedException) {}
        }

        try { vpnInputStream?.close() } catch (_: Exception) {}
        disconnectVpn()
    }

    // ═══ IP paket dispatch ═══

    @Keep
    @Throws(java.io.IOException::class)
    private fun onIpPacketReceived(ipHeader: IPHeader, size: Int): Boolean {
        return when (ipHeader.getProtocol()) {
            IPHeader.TCP -> onTcpPacketReceived(ipHeader, size)
            IPHeader.UDP -> {
                onUdpPacketReceived(ipHeader, size)
                false
            }
            else -> false
        }
    }

    @Keep
    @Throws(java.net.UnknownHostException::class)
    private fun onUdpPacketReceived(ipHeader: IPHeader, size: Int) {
        val portKey = udpHeader.getSourcePort()
        var session = NatSessionManager.getSession(portKey)

        if (session == null ||
            session.remoteIp != ipHeader.getDestinationIP() ||
            session.remotePort != udpHeader.getDestinationPort()
        ) {
            session = NatSessionManager.createSession(
                portKey, ipHeader.getDestinationIP(), udpHeader.getDestinationPort(), NatSession.UDP
            )
            ThreadProxy.getInstance().execute {
                PortHostService.getInstance()?.refreshSessionInfo()
            }
        }
        session.lastRefreshTime = System.currentTimeMillis()
        session.packetSent++

        // Paketi kopyala ve UdpServer'a gönder
        val data = packetBuffer.copyOf(size)
        val packet = Packet(data, 0)
        udpServer?.processUdpPacket(packet, portKey)
    }

    @Keep
    @Throws(java.io.IOException::class)
    private fun onTcpPacketReceived(ipHeader: IPHeader, size: Int): Boolean {
        var hasWrite = false
        tcpHeader.mOffset = ipHeader.getHeaderLength()

        if (tcpHeader.getSourcePort() == tcpProxyServer?.port) {
            // Reverse: local proxy bize yazıyor (remote → app)
            VPNLog.d(TAG, "process tcp packet from net")
            val session = NatSessionManager.getSession(tcpHeader.getDestinationPort())
            if (session != null) {
                ipHeader.setSourceIP(ipHeader.getDestinationIP())
                tcpHeader.setSourcePort(session.remotePort)
                ipHeader.setDestinationIP(localIpInt)
                CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader)
                try {
                    vpnOutputStream!!.write(packetBuffer, ipHeader.mOffset, size)
                } catch (e: Exception) {
                    DebugLog.e("TUN write (TCP reverse) failed: $e")
                }
            } else {
                DebugLog.i("NoSession: ${ipHeader} ${tcpHeader}")
            }
        } else {
            // Forward: app bize yazıyor (app → remote)
            VPNLog.d(TAG, "process tcp packet to net")
            val portKey = tcpHeader.getSourcePort()
            var session = NatSessionManager.getSession(portKey)

            if (session == null ||
                session.remoteIp != ipHeader.getDestinationIP() ||
                session.remotePort != tcpHeader.getDestinationPort()
            ) {
                session = NatSessionManager.createSession(
                    portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort(), NatSession.TCP
                )
                ThreadProxy.getInstance().execute {
                    PortHostService.getInstance()?.refreshSessionInfo()
                }
            }
            session.lastRefreshTime = System.currentTimeMillis()
            session.packetSent++

            val tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength()
            if (session.packetSent == 2 && tcpDataSize == 0) {
                return false  // TCP handshake 2. ACK no-data atla
            }

            // Paketi local proxy'ye yönlendir
            ipHeader.setSourceIP(ipHeader.getDestinationIP())
            ipHeader.setDestinationIP(localIpInt)
            tcpHeader.setDestinationPort(tcpProxyServer?.port ?: 0)
            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader)
            try {
                vpnOutputStream!!.write(packetBuffer, ipHeader.mOffset, size)
            } catch (e: Exception) {
                DebugLog.e("TUN write (TCP forward) failed: $e")
            }
            session.bytesSent += tcpDataSize
        }
        hasWrite = true
        return hasWrite
    }

    // ═══ VPN builder ═══

    @Keep
    @Throws(Exception::class)
    private fun establishVpn(): ParcelFileDescriptor {
        val builder = Builder()
        // KURAL 1: MTU = 1400
        builder.setMtu(VPNConstants.VPN_MTU)
        DebugLog.i("setMtu: ${VPNConstants.VPN_MTU}")

        val localIp = ProxyConfig.Instance.getDefaultLocalIp()
        localIpInt = CommonMethods.ipStringToInt(localIp.address)
        builder.addAddress(localIp.address, localIp.prefixLength)
        DebugLog.i("addAddress: ${localIp.address}/${localIp.prefixLength}")

        builder.addRoute(VPNConstants.VPN_ROUTE, VPNConstants.VPN_ROUTE_PREFIX)
        builder.addDnsServer(VPNConstants.PRIMARY_DNS)
        builder.addDnsServer(VPNConstants.SECONDARY_DNS)
        DebugLog.i("addDnsServer done")

        // KURAL 6: addDisallowedApplication KULLANMA.
        // Varsayılan: tüm trafik (null = no filter). İleride allowlist istense:
        val pkg = getSharedPreferences(VPNConstants.VPN_SP_NAME, Context.MODE_PRIVATE)
            .getString(VPNConstants.DEFAULT_PACKAGE_ID, null)
        try {
            if (pkg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addAllowedApplication(pkg)
                builder.addAllowedApplication(packageName)
            }
        } catch (e: Exception) {
            DebugLog.e("addAllowedApplication failed: $e")
        }

        builder.setSession(VPNConstants.TUN_SESSION_NAME)
        val pfd = builder.establish()
        if (pfd == null) throw Exception("VPN establish returned null")
        DebugLog.i("VPN established, pfd=$pfd")
        return pfd
    }

    // ═══ Helpers ═══

    @Keep
    private fun waitUntilPrepared() {
        while (prepare(this) != null) {
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
    }

    @Keep
    @Synchronized
    private fun dispose() {
        try {
            disconnectVpn()
            tcpProxyServer?.stop()
            tcpProxyServer = null
            DebugLog.i("TcpProxyServer stopped")
            udpServer?.closeAllUdpConn()
            udpServer = null
            ThreadProxy.getInstance().execute {
                PortHostService.getInstance()?.refreshSessionInfo()
                PortHostService.stopParse(applicationContext)
            }
            if (activeInstance === this) activeInstance = null
            stopSelf()
        } catch (e: Exception) {
            DebugLog.e("dispose exception: $e")
        }
    }

    @Keep
    private fun disconnectVpn() {
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        vpnOutputStream = null
    }

    // ═══ Foreground notification ═══

    @Keep
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(VPNConstants.NOTIFICATION_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                VPNConstants.NOTIFICATION_CHANNEL_ID,
                VPNConstants.TUN_SESSION_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN tunnel background relay"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    @Keep
    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, VPNConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(VPNConstants.TUN_SESSION_NAME)
            .setContentText("VPN tunnel active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
```

### 11.2 ProxyConfig.kt (companion to OpenE2eeVpnService)

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/ProxyConfig.kt`

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/ProxyConfig.kt
//
// Sprint 14 — VPN session/MTU/IP config holder.

package com.opene2ee.opene2ee.vpn.net

import android.content.Context
import androidx.annotation.Keep

@Keep
object ProxyConfig {
    @Keep
    val Instance: ProxyConfig = ProxyConfig()

    @Keep
    fun getDefaultLocalIp(): IPAddress = IPAddress("10.0.0.2", 32)

    @Keep
    fun onVpnStart(context: Context) {
        // No-op for Sprint 14. Reserved for future VPN status listeners.
    }

    @Keep
    fun onVpnEnd(context: Context) {
        // No-op for Sprint 14.
    }

    @Keep
    class IPAddress(@Keep val address: String, @Keep val prefixLength: Int) {
        @Keep
        constructor(ipAddressString: String) : this(
            ipAddressString.substringBefore("/"),
            ipAddressString.substringAfter("/", "32").toInt()
        )

        override fun toString(): String = "$address/$prefixLength"
    }
}
```

---

## 12. MAINACTIVITY (stop branch + stopVpn() direct call)

### 12.1 Değişiklik

**Dosya yolu:** `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/MainActivity.kt`

Eğer mevcut MainActivity'de `opene2ee/vpn` MethodChannel handler varsa, **"stop" branch'ini şu şekilde güncelle**:

```kotlin
// Eski stop branch (Sprint 12.0X):
"stop" -> {
    val stopIntent = Intent(this, OpenE2eeVpnService::class.java)
    stopService(stopIntent)
    result.success(mapOf("state" to "STOPPED"))
}

// YENİ stop branch (Sprint 14):
"stop" -> {
    val svc = OpenE2eeVpnService.activeInstance
    if (svc != null) {
        try {
            Log.d(TAG, "vpnDispatch: 'stop' branch, calling svc.stopVpn() directly")
            svc.stopVpn()
        } catch (e: Throwable) {
            Log.w(TAG, "vpnDispatch: svc.stopVpn() failed: ${e.message}")
        }
    } else {
        Log.d(TAG, "vpnDispatch: 'stop' branch, activeInstance is null (service not yet started?)")
    }
    try {
        val stopIntent = Intent(this, OpenE2eeVpnService::class.java)
        stopService(stopIntent)
    } catch (e: Throwable) {
        Log.w(TAG, "vpnDispatch: stopService failed: ${e.message}")
    }
    result.success(mapOf("state" to "STOPPED"))
}
```

### 12.2 "start" branch — yeni branch ekle (yoksa)

```kotlin
"start" -> {
    val svc = OpenE2eeVpnService.activeInstance
    if (svc == null) {
        Log.d(TAG, "vpnDispatch: 'start' branch, launching service")
        val intent = Intent(this, OpenE2eeVpnService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "vpnDispatch: startForegroundService failed: ${e.message}", e)
        }
        result.success(mapOf("state" to "STARTING"))
    } else {
        Log.d(TAG, "vpnDispatch: 'start' branch, service already active")
        result.success(mapOf("state" to "ACTIVE"))
    }
}
```

### 12.3 MethodChannel adı

- Class: `OpenE2eeVpnService` (Sprint 14'te yeni isim; Sprint 13.0'da `FirewallVpnService` idi)
- METHOD_CHANNEL: `"opene2ee/vpn"` (aynı)

### 12.4 Tam yeniden yazma (eğer MainActivity tamamen elden geçecekse)

```kotlin
// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/MainActivity.kt
//
// Sprint 14 — VpnService MethodChannel + permission handshake.

package com.opene2ee.opene2ee

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_REQUEST_CODE = 0x7B_50_4E
        private const val PERMISSIONS_CHANNEL = "opene2ee/vpn_permissions"
    }

    private var permissionsChannel: MethodChannel? = null
    private var vpnChannel: MethodChannel? = null
    private var pendingVpnResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d(TAG, "configureFlutterEngine: ENTER")

        vpnChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            OpenE2eeVpnService.METHOD_CHANNEL
        ).apply {
            setMethodCallHandler { call, result -> vpnDispatch(call, result) }
        }

        permissionsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            PERMISSIONS_CHANNEL
        ).apply {
            setMethodCallHandler(::onPermissionsCall)
        }
        Log.d(TAG, "configureFlutterEngine: DONE")
    }

    private fun vpnDispatch(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                val svc = OpenE2eeVpnService.activeInstance
                if (svc == null) {
                    val intent = Intent(this, OpenE2eeVpnService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    result.success(mapOf("state" to "STARTING"))
                } else {
                    result.success(mapOf("state" to "ACTIVE"))
                }
            }
            "stop" -> {
                val svc = OpenE2eeVpnService.activeInstance
                if (svc != null) {
                    try { svc.stopVpn() } catch (e: Throwable) {
                        Log.w(TAG, "svc.stopVpn() failed: ${e.message}")
                    }
                }
                try { stopService(Intent(this, OpenE2eeVpnService::class.java)) }
                catch (e: Throwable) { Log.w(TAG, "stopService failed: ${e.message}") }
                result.success(mapOf("state" to "STOPPED"))
            }
            "status" -> {
                val state = if (OpenE2eeVpnService.activeInstance != null) "ACTIVE" else "IDLE"
                result.success(mapOf("state" to state))
            }
            else -> result.notImplemented()
        }
    }

    private fun onPermissionsCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "requestVpnPermission" -> requestVpnPermission(result)
            "isVpnPrepared" -> result.success(isVpnPrepared())
            else -> result.notImplemented()
        }
    }

    @RequiresApi(21)
    private fun requestVpnPermission(result: MethodChannel.Result) {
        if (pendingVpnResult != null) {
            result.error("vpn_prepare_in_flight", "Already awaiting", null)
            return
        }
        when (val intent = VpnService.prepare(this)) {
            null -> result.success(true)
            else -> {
                pendingVpnResult = result
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, VPN_REQUEST_CODE)
                } catch (e: Throwable) {
                    pendingVpnResult = null
                    result.error("vpn_prepare_launch_failed", e.message, null)
                }
            }
        }
    }

    @RequiresApi(21)
    private fun isVpnPrepared(): Boolean = VpnService.prepare(this) == null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST_CODE) return
        val pending = pendingVpnResult ?: return
        pendingVpnResult = null
        when (resultCode) {
            Activity.RESULT_OK -> pending.success(true)
            Activity.RESULT_CANCELED -> pending.success(false)
            else -> pending.error("vpn_prepare_unknown_result", "resultCode=$resultCode", null)
        }
    }

    override fun onDestroy() {
        permissionsChannel?.setMethodCallHandler(null)
        permissionsChannel = null
        vpnChannel?.setMethodCallHandler(null)
        vpnChannel = null
        super.onDestroy()
    }
}
```

---

## 13. PROGUARD RULES

### 13.1 Dosya yolu
`mobile/android/app/proguard-rules.pro`

### 13.2 Eklenmesi gereken kurallar

Mevcut kurallar korunur, **bunları EKLE** (dosyanın sonuna):

```proguard
# ───── Sprint 14 — VpnService keep rules ─────
# (Sprint 13.0 + 12.0F+9 dersleri)

# 1. Tüm Log.* çağrıları (R8 release build'de strip edebilir)
-keepclassmembers,allowobfuscation class * {
    *** Log*(...);
}

# 2. public static final String TAG field'ları
-keepclassmembers,allowobfuscation class * {
    public static final java.lang.String TAG;
}

# 3. @Keep annotation ve annotated sınıflar
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# 4. VpnService companion object'leri (R8 strip etmesin)
-keepclassmembers class com.opene2ee.opene2ee.vpn.** {
    public static ** Companion;
    public static ** INSTANCE;
}

# 5. OpenE2eeVpnService native bridge (JNI yok ama yine de)
-keep class com.opene2ee.opene2ee.vpn.OpenE2eeVpnService { *; }

# 6. Tüm vpn/ paketi için Keep (defense-in-depth)
-keep class com.opene2ee.opene2ee.vpn.** { *; }
```

### 13.3 Doğrulama

Build sonrası:
```bash
flutter build apk --release
# APK unzip → classes.dex'leri dexdump ile kontrol et
# OpenE2eeVpnService sınıfı + tüm field'ları mevcut olmalı
unzip -p build/app/outputs/flutter-apk/app-release.apk classes.dex > /tmp/classes.dex
$ANDROID_HOME/build-tools/34.0.0/dexdump /tmp/classes.dex | grep "OpenE2eeVpnService"
# Beklenen: en az 5 method listelenmiş
```

---

## 14. BUILD + DOĞRULAMA

### 14.1 Build komutları

```bash
# 1. Temizle
cd C:\repos\e2ee-app-pr-s14item1
flutter clean

# 2. Bağımlılıklar
flutter pub get

# 3. Debug build
flutter build apk --debug
# Beklenen: "✓ Built build/app/outputs/flutter-apk/app-debug.apk"

# 4. Release build (R8 minify + shrink)
flutter build apk --release
# Beklenen: "✓ Built build/app/outputs/flutter-apk/app-release.apk"
# Beklenen: R8 "no missing classes" warning YOK

# 5. 4 APK SHA log
Get-FileHash -Algorithm SHA256 build/app/outputs/flutter-apk/app-debug.apk
Get-FileHash -Algorithm SHA1   build/app/outputs/flutter-apk/app-debug.apk
Get-FileHash -Algorithm SHA256 build/app/outputs/flutter-apk/app-release.apk
Get-FileHash -Algorithm SHA1   build/app/outputs/flutter-apk/app-release.apk
```

### 14.2 DEX doğrulama

DEX'te tüm yeni sınıflar mevcut, eski Sprint 12.0C/12.0F+/13.0 sınıfları YOK:

```bash
# Yeni sınıflar (VAR olmalı)
$ANDROID_HOME/build-tools/34.0.0/dexdump -f build/app/outputs/flutter-apk/app-release.apk | \
    grep -E "Class descriptor.*opene2ee.*vpn\." | \
    grep -E "OpenE2eeVpnService|TcpProxyServer|TcpTunnel|UdpServer|UdpTunnel|NatSessionManager|VPNConstants|MyLRUCache|IPHeader|TCPHeader|UDPHeader|DebugLog|VPNLog|CommonMethods|ThreadProxy|PortHostService|NetFileManager|ProxyConfig"
# Beklenen: ~16+ match

# Eski sınıflar (YOK olmalı)
$ANDROID_HOME/build-tools/34.0.0/dexdump -f build/app/outputs/flutter-apk/app-release.apk | \
    grep -E "Class descriptor.*opene2ee.*vpn\." | \
    grep -E "FirewallVpnService|TcpForwarder|UdpForwarder|NettyChannel"
# Beklenen: 0 match
```

### 14.3 Owner test akışı (5 dk, temiz cihaz)

```bash
# 1. APK yükle
adb install -r build/app/outputs/flutter-apk/app-debug.apk

# 2. Logcat temizle + app başlat
adb logcat -c
adb shell am force-stop com.opene2ee.opene2ee
adb shell am start -n com.opene2ee.opene2ee/.MainActivity

# 3. VPN izni ver (Dart UI'da "Start VPN" butonu, sistem dialog OK)

# 4. 10 sn bekle
sleep 10

# 5. Chrome aç → https://example.com
adb shell am start -a android.intent.action.VIEW -d https://example.com

# 6. 5 sn daha bekle
sleep 5

# 7. Logcat beklentileri
adb logcat -d -v threadtime > /tmp/logcat-sprint14.log

# A. VPN kurulum başarılı
grep "VPN established" /tmp/logcat-sprint14.log
# Beklenen: "VPN established, pfd=..."

# B. setMtu = 1400
grep "setMtu:" /tmp/logcat-sprint14.log
# Beklenen: "setMtu: 1400" (KURAL 1 doğrular)

# C. TcpProxyServer başladı
grep "TcpProxyServer started" /tmp/logcat-sprint14.log
# Beklenen: VAR

# D. protect() başarılı (en az 1)
grep "protect() returned true" /tmp/logcat-sprint14.log | head -3
# Beklenen: VAR (KURAL 2 doğrular)

# E. TCP forward + reverse eşit
grep "process tcp packet to net" /tmp/logcat-sprint14.log | wc -l
grep "process tcp packet from net" /tmp/logcat-sprint14.log | wc -l
# Beklenen: eşit sayıda (5+)

# F. UDP receivePackets (DNS response forward)
grep "receivePackets:" /tmp/logcat-sprint14.log | head -3
# Beklenen: VAR (KURAL 4 doğrular)

# G. UID lookup (Bug #4 fix)
grep "UID lookup" /tmp/logcat-sprint14.log | head -3
# Beklenen: "UID lookup: localPort=..., uid=10000+"

# H. PortHostService startParse (PR #33 fix)
grep "PortHostService started" /tmp/logcat-sprint14.log
# Beklenen: VAR

# 8. Stop butonu (Dart UI)
sleep 2
# (Dart'ta "Stop VPN" butonuna bas)
sleep 3

# I. stopVpn log
grep "stopVpn called" /tmp/logcat-sprint14.log
# Beklenen: VAR

# J. dispose + stopSelf
grep -E "TcpProxyServer stopped|dispose exception" /tmp/logcat-sprint14.log | head -3
# Beklenen: "TcpProxyServer stopped"

# K. TUN write NPE YOK
grep "TUN write" /tmp/logcat-sprint14.log
# Beklenen: "TUN write failed" YOK (sadece normal yazma log)

# L. DNS 45 sn timeout YOK
grep "DNS" /tmp/logcat-sprint14.log | grep -i "timeout\|45s"
# Beklenen: YOK
```

### 14.4 5-sprint başarısızlık SEMPTOM LISTESI — bunlar OLMAMALI

| Semptom | Kök neden | Bu spec'te nasıl engellendi |
|---------|-----------|------------------------------|
| TUN write NPE | `vpnOutputStream!!` null önce kontrol yok | Bölüm 11 `vpnOutputStream = FileOutputStream(...)` her döngüde set, null check try/catch |
| DNS 45 sn timeout | `key.attach(tunnel)` unutulmuş | Bölüm 10.2 `KURAL 4`, birebir kod |
| MTU 15000 → "Unexpected mtu" | `const val VPN_MTU = 15000` | Bölüm 3.2 `const val VPN_MTU = 1400` (KURAL 1) |
| 25 to net / 0 from net | `readFirstPacket` + yanlış portKey | Bölüm 9.2 `KURAL 2 + 3` |
| UID always -1 | `getUid(session.remotePort)` | Bölüm 8.3 `KURAL 5`, `getUid(session.localPort)` |
| Stop butonu no-op | `stopService()` foreground service'te may no-op | Bölüm 12.1 "stop" branch `svc.stopVpn()` direct call |
| Foreground crash on Android 14 | `startForeground` 5s içinde değil | Bölüm 11 `onCreate` içinde `ServiceCompat.startForeground` (atomik) |
| R8 missing classes (release build crash) | `@Keep` annotation eksik | Bölüm 13 Proguard kuralları |

### 14.5 Kendi commit'in öncesi son self-check

```bash
cd C:\repos\e2ee-app-pr-s14item1

# 6 kural + Proguard varlığı + main class varlığı
grep -rn "VPN_MTU = " mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/VPNConstants.kt | grep -v "//" | head -3
# Beklenen: 1 match, "= 1400"

grep -rn "readFirstPacket\|parseFirstPacket" mobile/android/app/src/main/kotlin/
# Beklenen: 0 match

grep -rn "clientSocket.localPort" mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/TcpProxyServer.kt
# Beklenen: 0 match

grep -rn "key.attach" mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/UdpServer.kt
# Beklenen: 1+ match

grep -rn "getUid(session.remotePort)" mobile/android/app/src/main/kotlin/
# Beklenen: 0 match

grep -rn "addDisallowedApplication" mobile/android/app/src/main/kotlin/
# Beklenen: 0 match

grep "OpenE2eeVpnService\|TcpProxyServer\|UdpServer" mobile/android/app/src/main/AndroidManifest.xml
# Beklenen: 1+ match

grep "vpn_opene2ee" mobile/android/app/proguard-rules.pro
# Beklenen: 1+ match (keep rules mevcut)
```

### 14.6 Commit (1 büyük commit, push YAPMA)

```bash
cd C:\repos\e2ee-app-pr-s14item1

git add -A
git status  # gözden geçir
git commit -m "Sprint 14: VpnService clean-room rewrite (huolizhuminh reference birebir)

12 dosya, copy-paste spec:
  - vpn/net/VPNConstants.kt: MTU=1400
  - vpn/net/MyLRUCache.kt: LinkedHashMap LRU
  - vpn/nat/NatSession.kt: data class
  - vpn/nat/NatSessionManager.kt: localPort=portKey
  - vpn/Packet.kt + tcpip/{IP,TCP,UDP}Header.kt: byte[] parser
  - vpn/util/{VPNLog,DebugLog,CommonMethods,AppDebug,ThreadProxy}.kt
  - vpn/processparse/{NetInfo,NetFileManager,PortHostService}.kt
  - vpn/proxy/{TcpProxyServer,TcpTunnel,UdpServer,UdpTunnel}.kt: key.attach + clientSocket.port
  - vpn/OpenE2eeVpnService.kt: MTU=1400, addAllowedApplication, stopVpn()
  - vpn/net/ProxyConfig.kt
  - AndroidManifest.xml: specialUse subtype
  - MainActivity.kt: stop branch svc.stopVpn() direct
  - proguard-rules.pro: @Keep + Log.* + vpn.* keep

6 KRİTİK KURAL spec'le uyumlu:
  1. MTU = 1400 (15000 değil)
  2. TcpProxyServer'da readFirstPacket/parseFirstPacket YOK
  3. portKey = clientSocket.port (localPort değil)
  4. key.attach(tunnel) selector register sonrası MUTLAKA
  5. PortHostService.getUid(session.localPort)
  6. addDisallowedApplication YOK, addAllowedApplication

Owner 5dk test akışı:
  - adb logcat 'VPN established, pfd=...'
  - 'setMtu: 1400'
  - 'process tcp packet to net' == 'process tcp packet from net'
  - 'receivePackets: portKey=..., bytes=...'
  - 'UID lookup: localPort=..., uid=10000+'
  - 'stopVpn called' (Stop butonu)"
```

**PUSH YAPMA.** Mimari §8 push kısıtı. push sırası user/Architect.

---

## 15. APPENDIX — Sınıf dosya yolları özeti

| Sınıf | Dosya yolu |
|-------|-----------|
| VPNConstants | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/VPNConstants.kt` |
| MyLRUCache | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/MyLRUCache.kt` |
| ProxyConfig | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/ProxyConfig.kt` |
| NatSession | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/nat/NatSession.kt` |
| NatSessionManager | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/nat/NatSessionManager.kt` |
| Packet | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/Packet.kt` |
| IPHeader | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/IPHeader.kt` |
| TCPHeader | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/TCPHeader.kt` |
| UDPHeader | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/UDPHeader.kt` |
| VPNLog | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/VPNLog.kt` |
| DebugLog | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/DebugLog.kt` |
| CommonMethods | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/CommonMethods.kt` |
| AppDebug | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/AppDebug.kt` |
| ThreadProxy | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/ThreadProxy.kt` |
| NetInfo | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/NetInfo.kt` |
| NetFileManager | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/NetFileManager.kt` |
| PortHostService | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/PortHostService.kt` |
| TcpProxyServer | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/TcpProxyServer.kt` |
| TcpTunnel | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/TcpTunnel.kt` |
| UdpServer | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/UdpServer.kt` |
| UdpTunnel | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/UdpTunnel.kt` |
| OpenE2eeVpnService | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/OpenE2eeVpnService.kt` |
| MainActivity | `mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/MainActivity.kt` |
| AndroidManifest | `mobile/android/app/src/main/AndroidManifest.xml` |
| Proguard | `mobile/android/app/proguard-rules.pro` |

**Toplam: 22 Kotlin dosya + 1 Manifest + 1 Proguard = 24 dosya.**

---

## 16. APPENDIX — Sprint 14 → Sprint 13.0 mapping

| Sprint 13.0 (eski) | Sprint 14 (yeni) | Not |
|--------------------|------------------|-----|
| `FirewallVpnService` | `OpenE2eeVpnService` | Yeniden adlandır (AndroidManifest service name ile eşleşmeli) |
| `net/VPNConstants.kt` (4 DNS, MTU=1400) | `net/VPNConstants.kt` (2 DNS, MTU=1400) | DNS sadeleştirildi |
| `proxy/TcpProxyServer.kt` (readFirstPacket hatalı fix edilmiş) | `proxy/TcpProxyServer.kt` (readFirstPacket yok) | KURAL 2 |
| `proxy/UdpServer.kt` (key.attach fix uygulanmış) | `proxy/UdpServer.kt` (key.attach birebir) | KURAL 4 |
| `nat/NatSessionManager.kt` (localPort atanmış) | `nat/NatSessionManager.kt` (localPort atanmış) | KURAL 5 |
| `MainActivity.kt` (stopVpn branch var) | `MainActivity.kt` (stopVpn branch kesin) | Aynı |
| 12.0C/12.0F+ service tag'leri | YOK (clean-room) | Sprint 12.0C/12.0F+ kod çıkarıldı |

---
