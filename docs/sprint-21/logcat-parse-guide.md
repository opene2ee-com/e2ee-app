# Sprint 21 e2ee-ap-v2 — Logcat Parse Guide (Owner runtime test)

> **Audience:** Owner (Ali) testing on OnePlus 9 Pro.
> **Build:** Sprint 21 e2ee-ap-v2 fresh branch. wirebare-kernel komple kopyalandı, R8 keep rules eklendi.
> **Target runtime:** `com.opene2ee.e2ee_ap_v2` (Flutter app) + `com.opene2ee.e2ee_ap_v2.vpn.service.SimpleWireBareProxyService` (VpnService).

---

## 1. Adb komutları (initial setup)

```powershell
# Telefonu bağla, debug build'i yükle
adb install -r build\app\outputs\flutter-apk\app-debug.apk

# Logcat'i temizle, marka filtrele (yalnızca e2ee-ap-v2 process'inden)
adb logcat -c
adb logcat -v time | Select-String -Pattern "e2ee_ap_v2|opene2ee|wirebare|SimpleWireBare|PacketDispatcher|TcpProxyServer|UdpProxyServer|NioProxyServer|TcpProxyTunnel|TcpRealTunnel|UdpRealTunnel|TcpPacketInterceptor|UdpPacketInterceptor|HttpSSLCodecInterceptor|HttpHeaderParser|HttpFlushInterceptor|WireBareSSLEngine|IPHeader"
```

PowerShell'de canlı logcat için:
```powershell
adb logcat -v time > C:\Users\User\Downloads\e2ee-ap-v2-logcat.txt
# başka terminalde
Get-Content C:\Users\User\Downloads\e2ee-ap-v2-logcat.txt -Wait | Select-String "..."
```

---

## 2. Beklenen tag listesi (sprint 21 wirebare-kernel)

| TAG | Kaynak sınıf | Ne loglanır |
|---|---|---|
| `WireBare` | `com.opene2ee.e2ee_ap_v2.vpn.common.WireBare` | VPN start/stop, JNI ready, TUN setup |
| `WireBareProxyService` | `com.opene2ee.e2ee_ap_v2.vpn.service.WireBareProxyService` | "service startCommand" — start/stop intent received |
| `PacketDispatcher` | `com.opene2ee.e2ee_ap_v2.vpn.service.PacketDispatcher` | TUN read bytes, dispatch TCP/UDP |
| `TcpProxyServer` | `com.opene2ee.e2ee_ap_v2.vpn.tcp.TcpProxyServer` | TCP socket accept, port bind |
| `TcpProxyTunnel` | `com.opene2ee.e2ee_ap_v2.vpn.tcp.TcpProxyTunnel` | TCP packet exchange, client ↔ remote |
| `TcpRealTunnel` | `com.opene2ee.e2ee_ap_v2.vpn.tcp.TcpRealTunnel` | TCP real tunnel (outbound) |
| `TcpPacketInterceptor` | `com.opene2ee.e2ee_ap_v2.vpn.tcp.TcpPacketInterceptor` | TCP packet interception |
| `UdpProxyServer` | `com.opene2ee.e2ee_ap_v2.vpn.udp.UdpProxyServer` | UDP datagram receive |
| `UdpRealTunnel` | `com.opene2ee.e2ee_ap_v2.vpn.udp.UdpRealTunnel` | UDP real tunnel (outbound) |
| `UdpPacketInterceptor` | `com.opene2ee.e2ee_ap_v2.vpn.udp.UdpPacketInterceptor` | UDP packet interception |
| `NioProxyServer` | `com.opene2ee.e2ee_ap_v2.vpn.proxy.NioProxyServer` | NIO selector, channel registration |
| `HttpSSLCodecInterceptor` | `com.opene2ee.e2ee_ap_v2.vpn.interceptor.ssl.HttpSSLCodecInterceptor` | HTTP CONNECT SSL codec |
| `HttpHeaderParser` | `com.opene2ee.e2ee_ap_v2.vpn.interceptor.http.HttpHeaderParser` | HTTP headers parsed |
| `HttpFlushInterceptor` | `com.opene2ee.e2ee_ap_v2.vpn.interceptor.http.HttpFlushInterceptor` | HTTP body flush |
| `WireBareSSLEngine` | `com.opene2ee.e2ee_ap_v2.vpn.ssl.WireBareSSLEngine` | TLS handshake (BouncyCastle) |
| `IPHeader` | `com.opene2ee.e2ee_ap_v2.vpn.net.IPHeader` | IP packet parse (Sprint 18'deki UdpHeader.copy() eksik hikayesi buradan başlar) |

> **Sprint 17 teşhis hatırlatma:** Verifier Sprint 17'de `UdpHeader.copy()` eksik dedi — bu **yanlış** çıktı (Sprint 18'de minimal fix de çalışmadı). GERÇEK kök neden hâlâ bilinmiyor. Sprint 21 = TEMİZ RESET, custom service yazmadık, wirebare-kernel'in kendi service'i birebir kullanılıyor. Runtime test **ilk defa doğru zemin** üzerinde yapılıyor.

---

## 3. Pozitif senaryo (beklenen log akışı)

App aç → butona bas → VPN AÇ:

```
WireBareProxyService: service startCommand
WireBare: start vpn service
WireBare: create builder
NioProxyServer: bind 0.0.0.0:<port>
PacketDispatcher: tun read N bytes
TcpProxyServer: accept on TUN
TcpProxyTunnel: connecting to <remote>
TcpRealTunnel: socket opened
WireBareSSLEngine: TLS handshake begin
HttpHeaderParser: HTTP/1.1 200 OK
UdpProxyServer: receive <N bytes>
UdpRealTunnel: send to <remote>:<port>
```

App → VPN KAPAT:

```
WireBareProxyService: service startCommand
WireBare: stop vpn service
TcpProxyServer: close
UdpProxyServer: close
PacketDispatcher: stop
```

---

## 4. Negatif senaryo (Sprint 12-20'nin "OpenE2ee app DNS timeout" hata pattern'i)

Önceki 7+ sprint'te görülen semptom:

```
PacketDispatcher: tun read 60 bytes
IPHeader: parse IP packet (DNS query)
UdpPacketInterceptor: forward to <DNS>
UdpRealTunnel: send to 8.8.8.8:53
... (sessizlik, cevap gelmiyor)
```

**Yeni Sprint 21'de bu beklenmemeli** — wirebare-kernel kendi service'i kullanılıyor, custom OpenE2eeVpnService YOK. Eğer hâlâ aynı semptom görülürse, sorun:
1. wirebare-kernel config / DNS forwarder ayarı
2. android.net.VpnService.Builder DNS addres ayarı
3. Network security config

O zaman Sprint 22+ için yeni teşhis gerekir.

---

## 5. Hızlı test: DNS ping

App VPN AÇ iken telefonda:
```powershell
# Telefon üzerinden DNS çözümlemesi (VpnService üzerinden)
adb shell ping -c 3 api.opene2ee.com
# veya
adb shell nslookup api.opene2ee.com
```

Wirebare log'unda görmek istediğimiz:
```
UdpProxyServer: 8.8.8.8:53 <query>
UdpRealTunnel: send 8.8.8.8:53
... (cevap gelmeli)
UdpProxyServer: <response> bytes from 8.8.8.8:53
```

---

## 6. R8 keep rules etkinliği (release APK)

Release build'de R8 full mode agresif. Sprint 21'de eklediğimiz `proguard-rules.pro`:
- `com.opene2ee.e2ee_ap_v2.vpn.service.SimpleWireBareProxyService` (FQN korunur)
- `com.opene2ee.e2ee_ap_v2.vpn.service.WireBareProxyService` (parent)
- `com.opene2ee.e2ee_ap_v2.vpn.common.VpnPrepareActivity`
- `com.opene2ee.e2ee_ap_v2.vpn.**` (defense-in-depth, tüm paket)

**DEX scan sonucu (release 5.9 MB, classes.dex):**
- `sankokomi` hit: 0 (sed-rename başarılı)
- `opene2ee.e2ee_ap_v2.vpn` class refs: 3972
- `SimpleWireBareProxyService` class: mevcut
- `WireBareProxyService` class: mevcut
- `VpnPrepareActivity` class: mevcut
- `core.action.Start` string: mevcut
- `core.action.Stop` string: mevcut

→ Manifest'ten başlatılan intent'ler runtime'da sınıfı bulabilir.

---

## 7. Tuzaklar (Beklenmeyen durumlar)

1. **"ClassNotFoundException: SimpleWireBareProxyService"** — release APK'da R8 keep eksik olabilir. Debug APK'ya dön, eğer çalışıyorsa proguard-rules.pro'yu kontrol et.
2. **"ForegroundServiceStartNotAllowedException"** — Android 12+ foreground service restriction. Manifest'te `foregroundServiceType="dataSync"` ekledik, çözülmeli.
3. **"BIND_VPN_SERVICE permission denied"** — service permission eksik. Manifest'te `android:permission="android.permission.BIND_VPN_SERVICE"` ekledik.
4. **Logcat boş / WireBare TAG yok** — `WireBare.attach` çağrılmamış olabilir. `WireBareProxyService.onCreate()`'de çağrılır (orijinal wirebare'da). Sprint 21'de bu komple kopyalandı, doğru olmalı.

---

## 8. Test akışı (önerilen sıra)

1. **Debug build yükle** (153 MB, R8 yok, tüm sınıflar korunmuş):
   ```powershell
   adb install -r build\app\outputs\flutter-apk\app-debug.apk
   adb logcat -c
   adb shell am start -n com.opene2ee.e2ee_ap_v2/.MainActivity
   ```
2. **App'te VPN AÇ butonuna bas.** İlk seferde sistem VPN izni diyaloğu çıkar → "OK".
3. **logcat'i gözlemle:**
   ```powershell
   adb logcat -d | Select-String "WireBare|SimpleWireBare|PacketDispatcher"
   ```
4. **Telefon tarayıcısında** `https://api.opene2ee.com` (veya `https://example.com`) aç. Wirebare log'unda TCP tunnel + TLS handshake görmelisin.
5. **VPN KAPAT butonuna bas.** logcat'te close event'leri görmelisin.
6. **Release build test** (45 MB, R8 aktif, keep rules uygulanmış): aynı akışı tekrarla.
7. **Sorun varsa:** logcat dump'unu `docs/sprint-21/logcat-dump-<tarih>.txt` olarak kaydet ve Coder'a ilet.
