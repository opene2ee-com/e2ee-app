# Sprint 15 — Dart VPN Araştırması (Mavis, 2026-07-16)

Owner direktifi: "java(kotlin) ile yapılan kodun dart ile nasıl yapıldığını internetten bir daha araştır"

## TL;DR

**Java/Kotlin VPN kodu → Dart'a taşınamaz. Endüstri standardı: VpnService (Java/Kotlin) + Dart MethodChannel wrapper + native core (C/Go/Sing-box). Mevcut Sprint 14 yaklaşımı doğru mimari.**

## Araştırma Bulguları (4 ana sonuç)

### 1. VpnService her zaman Java/Kotlin

Android `VpnService` API native (android.net.VpnService). Hiçbir production Flutter VPN çözümü VpnService'i Dart'ta implement etmiyor. Hepsi:
- VpnService Java/Kotlin sınıfı (System tarafından başlatılır)
- TUN file descriptor OS-level
- MethodChannel/EventChannel ile Dart UI'ya state push edilir
- TUN → user-space TCP/IP stack conversion her zaman native (C/Go/Java)

**Kaynaklar:**
- Android docs (developer.android.com/develop/connectivity/vpn): "Your VPN service inherits from `VpnService`"
- go-tun2socks-android: "VpnService should be implemented in Java/Kotlin"
- Tüm Flutter VPN pluginleri: `flutter_vpn_service`, `flutter_v2ray_plus`, `vpn_plugin`, `flutter_singbox_client`, `proxy_core` — hepsi native VpnService + Dart wrapper

### 2. User-space TCP/IP stack — 3 endüstri yaklaşımı

| Yaklaşım | Açıklama | Performans | Karmaşıklık | Sprint 14'te kullanılan |
|----------|----------|------------|-------------|-------------------------|
| **NDK tun2socks (C)** | libtun2socks.so + JNI binding, TUN fd → SOCKS proxy | Yüksek | Orta | ❌ |
| **Go tun2socks (lwIP)** | Go-based lwIP stack, gomobile, V2Ray/Sing-box core | Yüksek | Yüksek | ❌ |
| **Pure Java/Kotlin + raw Socket** | Sprint 11/12/13/14 yaklaşımı, custom TCP state machine + Selector | Orta | Çok yüksek | ✅ |
| **Flutter Dart HTTP/TCP stack** | `dart:io` HttpClient, pure Dart TCP | Düşük (mobile için yeterli) | Orta | ❌ (KULLANILAMAZ — system proxy bypass) |

**Sprint 14 = pure Java/Kotlin raw Socket + Selector pattern (huolizhuminh bazlı).** Sprint 11.0Z'de Netty denenmişti, Sprint 14'te çıkarıldı (kod çok karmaşık, debug zor).

### 3. Flutter HttpClient — system proxy bypass

**Kritik bulgu:** Flutter `dart:io` HttpClient kendi HTTP/TCP stack'ini pure Dart'ta implement eder. **System-wide proxy'yi bypass eder, OS-level VpnService transparent proxy'yi görmezden gelebilir.**

Kaynak (MagiskModule blog):
> "Flutter uygulamaları native Android `HttpURLConnection` veya deprecated `OkHttp` client'ı doğrudan KULLANMAZ. Bunun yerine Dart `dart:io` kütüphanesini, özellikle `HttpClient` sınıfını kullanır — pure Dart'ta kendi HTTP protocol stack'ini implement eder."

> "Dart runtime socket creation, TLS handshake, HTTP framing'i tamamen user space'te yapar. Bu, standart system-wide proxy konfigürasyonlarının (Wi-Fi settings veya ADB) Flutter engine tarafından ignore edilebileceği anlamına gelir — uygulama explicit olarak bunlara uymadıkça."

**Sprint 14 açısından sonuç:** VPN transparent proxy doğru çalışıyor (TUN → kernel → TUN), Dart HttpClient OS TCP stack'ini kullandığı için VpnService transparent proxy'yi bypass etmiyor. Sprint 14'ün MIMARI'sı doğru.

### 4. SockSniffer / ClumsyTunnel VPN-to-SOCKS5 (referans implementasyon)

**Bulgu:** Birçok production uygulama `tun2socks` core (C/Go) + Java/Kotlin VpnService + Dart UI kullanıyor. Örnekler:
- **Fluxzy (haga-rak/fluxzy.core)**: v-s5unnel (C-based tun2socks) + Flutter UI. "Since the application consists of 90% native code (C tunnel combined with the platform's VPN service), Flutter is mainly used for the settings interface and the toggle button"
- **Kitsunebi (eycorsican/kitsunebi-android)**: Go-tun2socks + V2Ray + Java VpnService
- **mahsanet/proxy_core**: XRay core + Dart FFI bindings + Java VpnService
- **flutter_singbox_client**: Sing-box core + Java VpnService + Dart wrapper

**Hepsi aynı mimari:** Native core + Java/Kotlin VpnService + Dart UI/wrapper. Sprint 14'ün seçtiği yol.

## Sprint 14 Mimari Değerlendirmesi

Sprint 14'ün mimarisi endüstri standardıyla **uyumlu**:
- ✅ VpnService Kotlin (Android system API zorunluluğu)
- ✅ TCP/UDP proxy Java/Kotlin (raw Socket + Selector — huolizhuminh pattern)
- ✅ MethodChannel ile Dart UI'ya state push
- ✅ NatSessionManager ile session tracking
- ✅ PortHostService ile UID lookup

**Ama user-space TCP/IP stack "raw Socket" ile yapmak yerine `tun2socks` core (NDK C library) kullansaydı daha sağlam olur muydu?**

| Avantaj (tun2socks) | Dezavantaj (tun2socks) |
|---------------------|-------------------------|
| TCP state machine lwIP'te test edilmiş | NDK build complexity (CMake, .so per ABI) |
| UDP fragmentation built-in | Debug zor (C code + Java bridge) |
| ICMP, DNS, IPv6 built-in | 3rd-party dependency (LondaxX/tun2socks-android) |
| SOCKS5 upstream native desteği | Sprint 14'ün NDK yok, yeni setup gerekir |
| Sprint 11.0Z Netty başarısızlığı önlenir | 1-2 sprint ek setup (gradle NDK config) |

| Avantaj (raw Socket, Sprint 14) | Dezavantaj (raw Socket) |
|---------------------------------|-------------------------|
| Pure JVM, no NDK | TCP state machine custom (SYN/ACK/FIN, sliding window, retransmission) |
| Debug kolay (Kotlin source) | UDP fragmentation custom |
| Spec compliance (huolizhuminh birebir) | Sprint 11.0Z'de Netty başarısız, raw Socket da riskli |
| Hızlı implement (Kotlin copy-paste) | HTTP/1.1 chunked, TLS passthrough ayrı sprint gerek |

## Owner'a Öneri (Sprint 15+ planı)

**Sprint 14 (raw Socket + huolizhuminh) devam etmeli, çünkü:**
1. Mimari doğru
2. Owner test edebilir (cihaz + logcat)
3. Sprint 14.1 stopVpn fix başarılı (logcat140a doğruladı)
4. DNS+TCP handshake çalışıyor (logcat140a: 8 DNS kanal, 23 to_net / 16 from_net)

**HTTP data response kesilmesi (gerçek sorun) için Sprint 15 planı:**
- Spec'e birebir uyulmuş mu (architect delta analizi) — ÖNCELİK
- Eğer spec doğru ama HTTP response yine gelmiyorsa: TCP reverseExecutor → TUN write path debug
- Eğer spec'ten sapma varsa: delta'yı düzelt
- NDK tun2socks'a GEÇMEK İÇİN ERKEN — sprint 15/16 raw Socket iyileştirmeleri bitmeden

## Referans Linkler (kanıt)

- Android VpnService docs: https://developer.android.com/develop/connectivity/vpn
- proxy_core: https://github.com/mahsanet/proxy_core
- flutter_singbox_client: https://dev.to/amirzr/fluttersingboxclient-a-modern-flutter-vpn-proxy-plugin-built-on-sing-box-4omj
- vpn_plugin (Sing-box): https://libraries.io/pub/vpn_plugin
- flutter_v2ray_plus: https://pub.dev/documentation/flutter_v2ray_plus/latest/
- Flutter HttpClient proxy bypass: https://magiskmodule.gitlab.io/blog/proxying-flutter-traffic-on-android-with-claude/
- Fluxzy (v-s5unnel): https://www.reddit.com/r/FlutterDev/comments/1r72dwa/half_of_android_apps_ignore_proxy_settings_so_i/
- go-tun2socks-android: https://pkg.go.dev/github.com/eycorsican/go-tun2socks-android
- LondonX/tun2socks-android: https://github.com/LondonX/tun2socks-android
- huolizhuminh/NetWorkPacketCapture: https://github.com/huolizhuminh/NetWorkPacketCapture
- Java NIO tcp tunnel (gist): https://gist.github.com/Addvilz/bd36bb423f3861296e4ef1127f0119bd
