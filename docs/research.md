# Android VPN ile TCP Paket İzleme — Araştırma Raporu

**Tarih:** 2026-08-02
**Bağlam:** `e2ee-ap-v2` Sprint 24+ — VpnService tabanlı TCP capture. Wirebare (Kokomi7QAQ/wirebare-android + wirebare-kernel) kullanılıyor. Owner 12+ sprint debug sonunda "VPN açılınca internet kesiliyor" diyor; Sprint 24+ handoff'unda ise "default mode çalışıyor" yazıyor. Bu çelişki çözülmeden kod değişikliği yapılmamalı.

**Bu rapor:** Kod yazma kararı öncesi, kanonik bilgi ile Sprint 24+ yaklaşımının dürüst bir karşılaştırması. Araştırma bulguları + sprint mevcut durum değerlendirmesi + net bir yön önerisi.

---

## 1. "VPN Açılınca İnternet Kesiliyor" Semptomu — Olasılık Sıralı Kök Nedenler

Owner'ın yaşadığı semptom ("VPN açılınca internet kesiliyor") tek bir nedenle sınırlı değil. Aşağıdaki tablo, 2024-2026 arası dönemde Android VPN uygulamalarında raporlanan en sık nedenleri, bizim Sprint 24+ config'imizle çakışma riskine göre sıralıyor.

| # | Kök neden | Semptom | Sprint 24+ ile çakışma | Kaynak doğrulama |
|---|---|---|---|---|
| 1 | **Private DNS (DoT) override** — Android 9+ Private DNS, VPN'in `addDnsServer`'ını override eder; Android 10+'da lockdown modunda bile | VPN açıkken DNS çözümlenmiyor → tüm bağlantılar sessizce düşüyor | **YÜKSEK** — Sprint 24+ `addDnsServers(...)` çağırmıyor, OS carrier DNS'e güveniyor | rethink-app GitHub issue #25 [1], Tor VPN forum [2], Express VPN Reddit [3] |
| 2 | **Per-app VPN `isDefaultNetwork=false`** — `addAllowedApplication` kullanıldığında VPN tüm uygulamaların varsayılan ağı olmuyor; uygulama kendi tercih ettiği (WiFi/mobile) ağdan gidebiliyor | Uygulama kendi routing kararını veriyor, TUN'dan geçmiyor | **YÜKSEK** — Sprint 24+ sadece `addAllowedApplications(packageName)` kullanıyor, `setUnderlyingNetworks` çağırmıyor | AOSP ConnectivityService kaynağı [4], VpnService API docs [5] |
| 3 | **İkinci TCP bağlantı race condition** — `ServerSocketChannel` + blocking accept pattern'inde, ilk session kapatıldıktan sonra channel state'i kirli kalabiliyor; ikinci SYN geldiğinde accept ya geç ya da timeout | İlk bağlantı çalışıyor, ikincisi timeout | **ORTA-YÜKSEK** — Sprint 24+ handoff zaten "ikinci bağlantı timeout"u açık sorun olarak listeliyor | Java NIO Selector örüntüleri [6] |
| 4 | **Android 16 VPN bug'ı** — "VPN connections are broken on Android 16" — always-on VPN'lerde Google'ın da onayladığı, güncel bir OS bug'ı | VPN açıldıktan veya güncellendikten sonra tüm internet kesiliyor; cihaz yeniden başlatılana kadar düzelmiyor | **Cihaz sürümüne bağlı** — eğer OnePlus OxygenOS 16+ ise risk var | howtogeek.com [7] |
| 5 | **"Block connections without VPN" lockdown** — sistem ayarı, VPN tunnel kurulmadan tüm trafiği blokluyor; tunnel kurulumu sırasında deadlock oluşabiliyor | VPN tüneli kurulamıyor → "VPN bağlı ama internet yok" | **ORTA** — Sprint 24+ manifest'inde `SERVICE_META_DATA_SUPPORTS_ALWAYS_ON` set edilmemiş olabilir | Google Pixel VPN deadlock raporu [8] |
| 6 | **MTU yanlış ayarı** — Wirebare default 1400 byte; bazı captive portal / carrier'lar 576'da sorun yaşatır | Büyük HTTP response'ları parçalanıyor, timeout | **DÜŞÜK** — Sprint 24+ Wirebare default'unu kullanıyor | Stack Overflow [9] |
| 7 | **Link-local subnet exclusion eksikliği** — `addRoute("0.0.0.0", 0)` 10.x/172.16.x/192.168.x'i de yakalıyor; captive portal ve DHCP bu yüzden bozulabiliyor | Captive portal authentication başarısız → internet "yok gibi" görünüyor | **DÜŞÜK** — Android 13+ `excludeRoute` API'si var, kullanılmamış | Stack Overflow excludeRoute [10] |

**Sprint 24+ config'ine göre en olası suçlu:** Sıra 1 (Private DNS override) + Sıra 2 (per-app VPN isDefaultNetwork=false). Sprint 24+ handoff'unda "per-app VPN fix for isDefaultNetwork=false" yazıyor, ama o not OnePlus+Android 14 kombinasyonunda çalıştığı gözlemlenmiş bir fix — diğer cihaz/OS kombinasyonlarında (özellikle Android 15/16) aynı fix'in çalışacağı garanti değil.

---

## 2. Sprint 24+ Mevcut Konfigürasyon — Dürüst Değerlendirme

Sprint 24+ base state (memory notundan, SHA `F2C41960...`):

```kotlin
WireBare.startProxy {
    addRoutes("0.0.0.0" to 0)
    addAllowedApplications(packageName)        // per-app VPN
    // NO addDnsServers(...)                   // ⚠️ OS carrier DNS'e güveniyor
    clearHttpInterceptor()
    clearAsyncHttpInterceptor()
}
```

Bu konfigürasyonu üç açıdan değerlendirelim: doğru kararlar, kırılgan kararlar, eksik olanlar.

### 2.1 Sağlam olan kararlar

- **Custom `SimpleTcpForwarder` per-session** — TCP'yi stateless pass-through yapmak yerine, her SYN için ayrı bir forwarder coroutine + `vpnService.protect()` ile out-of-tunnel socket açıyor. Bu, "TUN read → TUN write" yaparak SYN_SENT'te deadlock'a düşen klasik hatadan kaçınıyor [11].
- **Per-app VPN** — kendi uygulamamız dışındaki tüm uygulamalar (Brave, Termux, vb.) normal network'te kalıyor; debug sırasında yan etki riski düşük.
- **MITM yok** — `clearHttpInterceptor()` ile wirebare HTTPS inspection zincirini söküyoruz. App Store policy ile uyumlu, kullanıcıya CA cert yükleme derdi yok.
- **Int (unsigned 16-bit) port handling** — Sprint 24+'daki `tcpHeader.sourcePort.port.toShort()` Short signed-overflow bug'ı çözülmüş. 32768+ portlar artık map key olarak negatif olmuyor.
- **`protect()` retry 10×20ms** — fresh socket'ta `protect()` false dönebiliyor; retry pattern'i race koşulunu tolere ediyor.
- **3-flag toggle altyapısı (`PacketAnalysis` singleton)** — diagnostic için solid; bypassTcp / bypassUdp / enabled bağımsız kontrol edilebiliyor.

### 2.2 Kırılgan olan kararlar

- **NO `addDnsServers(...)`** — Sprint 24+ memory notunda "UdpRealTunnel broken, OS carrier DNS kullanılıyor" deniyor. Ama bu, DNS çözümlemenin bütünüyle OS carrier DNS davranışına bağlı olduğu anlamına geliyor. Eğer cihazda Private DNS açıksa, VPN tünelinden geçen trafik için bile DoT üzerinden Google DNS kullanılıyor — ve bu DNS, kullanıcının gerçek network konumunu (OnePlus + TT 4G) ile uyumsuz olabiliyor. Sprint 24+ bu riski test etmemiş.
- **Custom forwarder per-session race** — Handoff'taki "ikinci bağlantı timeout" sorunu çözülmemiş; bu production state'te gerçek bir bug. Kullanıcı düğmeye 2-3 kez basınca ikinci istek takılıyor. Büyük olasılıkla `pendingSessions.remove()` ile `serverChannel.accept()` arasındaki ordering + selector'ın dirty state'i.
- **NO `setUnderlyingNetworks(...)`** — API 31+ ile gelen bu metod, VPN'in "underlying network"ünü açıkça bildirerek sistem tarafından default network olarak tanınmasını sağlıyor. Sprint 24+ bunu çağırmıyor, bu yüzden OnePlus + Android 14 + TT 4G kombinasyonunda bile reliability garanti değil [4].
- **UDP yok** — UdpRealTunnel broken olduğu için non-DNS UDP trafiği (QUIC, STUN, custom UDP) basitçe düşüyor. QUIC'in Cloudflare 2024 verilerine göre global trafiğin %20.5'ini oluşturduğu düşünülürse, modern sitelerin önemli kısmı QUIC kullanıyor ve bu trafik Sprint 24+'da kayboluyor [12].

### 2.3 Eksik olan kararlar

- **TLS ClientHello SNI parsing** — wire-side capture, MITM olmadan. Sprint 24+ backlog'ta var ama hâlâ uygulanmamış. Sprint 24+ interceptor'ları şu an sadece IP+TCP header rewrite yapıyor, payload parse etmiyor. Bu, capture'ın "ne yakaladığını" tam görememesi demek.
- **Shannon entropy on ciphertext** — entropy ≥ 7.5 = "şifreli muhtemelen" sinyali; payload byte'larından App Store policy uyumlu metadata. Backlog'ta var, uygulanmamış.
- **Self-test fallback** — peer gönüllü olmadan test edebilmek için kendi uygulamamıza/sunucumuza mesaj. Şu an Sprint 24+ yalnızca backend HTTPS POST'ları test ediyor; HTTP/3 veya farklı domain'lerde bug varsa kör kalıyor.
- **Per-domain routing** — örneğin sadece `*.example.com` trafiğini yakala, gerisini normal routing'e bırak. Bu, "diğer uygulamalar etkilenmesin" garantisini daha da güçlendirir. Sprint 24+'da `addAllowedApplication` var ama domain bazlı değil.

---

## 3. Kanonik Mimari Pattern'leri — Wirebare'in Alternatifleri

Sprint 24+, wirebare'in sadece `VpnService.Builder` katmanını kullanıyor, ama TcpProxyServer/UdpRealTunnel zincirini bypass ediyor. Doğru yön gibi görünüyor, ama bu "wirebare'i bypass et ama hâlâ kullan" ara formu aslında 3 production kalıbından biri. Üçünü de netleştirelim.

### 3.1 Pattern A: VpnService.Builder + tam özel forwarder (Sprint 24+ şu an burada)

```
[VpnService.Builder] → TUN fd → [Custom IP parser] → 
  [Custom TCP session table] → [Per-session ServerSocketChannel] → 
  [protect()'lı out-of-tunnel socket] → gerçek sunucu
```

**Avantaj:** Wirebare'in MITM zincirine bağımlılık yok. Sadece metadata toplanıyor (SNI, entropy) — App Store uyumlu. Custom kod, custom kontrol.

**Dezavantaj:** Her şeyi kendin yazıyorsun — IP parser, TCP state machine, retransmission, congestion window, vs. Wirebare bile bunları yazarken bug yapıyorsa (Sprint 22.12 regression kanıtı), custom yazarken daha çok bug yapılır.

**Sprint 24+ bu pattern'de ama wirebare'i de yanında tutuyor** — `WireBare.startProxy` ile başlatıp kendi interceptor'larını bağlıyor. Bu, "yarı wirebare, yarı custom" ara formu; avantajı wirebare'in tunnel/MTU/IP parser katmanı, dezavantajı wirebare'in buggy TcpProxyServer zincirinin hâlâ orada olması (memory'de Sprint 22.12 regression notu var).

### 3.2 Pattern B: Saf tüketici kütüphane (tun2socks, smart-socket, AndroidAsync)

```
[VpnService.Builder] → TUN fd → [tun2socks C library veya JNI] → 
  [SOCKS5/HTTP proxy handler] → out-of-tunnel socket
```

**Örnekler:** LondonX/tun2socks-android (Java API + .aar), ksharpdabu/go-tun2socks (Go, lwIP stack), koush/AndroidAsync (Java NIO) [13][14][15].

**Avantaj:** TUN'dan gelen byte'ları güvenilir şekilde TCP/UDP stream'lerine dönüştürmek için yıllarca test edilmiş kütüphaneler. Biz sadece "her connection için ne yapacağız" kararını veriyoruz.

**Dezavantaj:** MITM zinciri yok (bu bizim için artı). SOCKS5/HTTP proxy varsayıyor; bizim senaryomuzda forwarder kendimiz yazacağız — yani tun2socks sadece IP+TCP+UDP parser katmanını verir, gerisini biz yaparız. Bu da Pattern A'nın üstüne bir kütüphane koyar.

### 3.3 Pattern C: NetGuard / shadowsocks-android tarzı bütün uygulama

NetGuard GPL lisanslı, shadowsocks-android GPLv3, clash-for-android GPL-3.0. Hepsi VpnService + custom parser + uygulama-spesifik dispatch yazıyor [16][17].

**Avantaj:** Çalışan, production'da milyonlarca kullanıcı tarafından test edilmiş. Wirebare'in kendisi de bu kategoride (Kokomi7QAQ/wirebare-android, MIT, 73 stars, 12 stars wirebare-kernel) [18][19].

**Dezavantaj:** Wirebare dışındakilerin hepsi **MITM yapmak için tasarlanmış** (Packet Capture / HTTPS inspection için). Bizim senaryomuzda bu özellik gereksiz ve App Store policy'ye aykırı. Wirebare'in bile sadece HTTPS modunu kullanmak için CA cert kurulumu gerekiyor; biz bunu devre dışı bırakıp sadece plain forwarder'ı kullanıyoruz.

**Wirebare'i bırakıp NetGuard/shadowsocks/clash'e geçmek:** Çok büyük refactor. Her birinin farklı API surface'i var; Sprint 24+'ın `PacketAnalysis` toggle'ları, `SimpleTcpForwarder`, `SimpleTcpInterceptor` mantığı baştan yazılır. **Önermiyorum.**

### 3.4 Hangisi Sprint 24+ için doğru?

**Pattern A (şu anki yön) doğru, ama wirebare dependency tamamen atılmalı.** Sprint 24+'ın `WireBare.startProxy` çağrısı, `addAllowedApplications` API'sini ve `addRoutes` API'sini kullanıyor — bunların hepsi doğrudan `VpnService.Builder`'da var. Wirebare'in burada yaptığı tek ek: `addHttpInterceptor`/`addAsyncHttpInterceptor` semantiğini soyutlamak (biz bunları clear ediyoruz zaten), ve `mockPacketLossProbability` gibi test yardımcıları. Bunlar için wirebare'i bağımlılık olarak tutmak overkill.

**Net öneri:** Pattern A'da kal, ama wirebare dependency'yi Sprint 25'te çıkar. Yerine sadece `VpnService.Builder` + kendi forwarder/interceptor'ların. Bu, wirebare TcpProxyServer/UdpRealTunnel zincirinin "etrafta bir yerde dormant" olması sorununu da bitirir (Sprint 24+ memory notu, "wirebare TcpProxyServer broken olduğu için SimpleTcpInterceptor custom yazdık, ama TcpRealTunnel hâlâ orada" diyor).

---

## 4. "İkinci Bağlantı Timeout" — Race Condition Analizi

Sprint 24+ handoff'unda bu açık sorun olarak duruyor: ilk bağlantı çalışıyor, ikincisi timeout. Bu, owner'ın bir sonraki sprint'te uğraşmayı planladığı tek şey ama "Sprint 24+ default mode çalışıyor" iddiasıyla çelişiyor (eğer ikinci bağlantı timeout oluyorsa, mode "çalışmıyor").

Java NIO Selector pattern'inde bilinen tuzaklar [6]:

1. **`selectedKeys` set otomatik temizlenmez** — her `selector.select()` çağrısından sonra `selectedKeys()` içindeki key'leri manuel `iter.remove()` yapmazsan, aynı event sonsuz kez işlenir. Bu, "ikinci SYN geldi, accept çağrıldı, ama aynı key hâlâ set'te → next iteration'da yine accept çağrıldı → aynı session iki kez register edildi" gibi davranışlara yol açar.
2. **`SelectionKey.interestOps()` güncellenince attachment kaybolur** — `key.attach(...)` ile bağladığın data, `interestOps()` çağrısıyla sıfırlanmaz ama **referans**'ı kaybedebilir. Per-session `SocketChannel` reference'ı `pendingSessions` map'inde tutuluyorsa ve map'in kendisi garbage collect oluyorsa, accept loop eşleşemez.
3. **TIME_WAIT + accept()** — TCP server'da, önceki connection'ın 4-tuple'ı TIME_WAIT'teyken yeni SYN gelirse accept edilebilir (kernel otomatik yapar) ama **aynı ephemeral port'a** bağlanıyorsa, yeni `ServerSocketChannel` eski session'ın state'ini görebilir.

Sprint 24+'daki `SimpleTcpForwarder` blocking + single ServerSocketChannel pattern'i kullanıyor olabilir (memory notu "Sequential blocking" diyor). Bu pattern'de 3. madde (TIME_WAIT) ana suçlu olabilir. Çözüm: **NIO Selector pattern'ine geçiş** veya **per-session fresh ServerSocketChannel** (random ephemeral port).

**Sprint 24+ önerisi (race fix için):**
- Blocking `ServerSocketChannel.accept()` yerine `Selector` ile non-blocking + `select(timeout)` 
- Veya her session için yeni bir `ServerSocketChannel` (port reuse için `SO_REUSEADDR` set et)
- Her iki durumda da: `pendingSessions` ConcurrentHashMap + close cleanup sırası net olmalı (register'dan önce kontrol, accept'ten sonra remove)
- `protect()` race için retry pattern'i zaten var, ama **out-of-tunnel socket açıldıktan sonra** retry değil, **önce** retry olmalı

Sprint 24+ handoff zaten bu 4 senaryoyu (A/B/C/D) listelemiş. Sprint 25 için yapılacak iş: hangi senaryo olduğunu teyit etmek için `adb logcat -d | Select-String -Pattern "SimpleTcpFwd|SimpleTcpInt"` çalıştırmak, sonra scenario-specific fix.

---

## 5. DNS — "VPN Açılınca İnternet Kesiliyor" En Sessiz Suçlusu

Bu muhtemelen owner'ın yaşadığı semptomun **birincil sebebi** ve Sprint 24+ bunu hiç test etmemiş.

**Sprint 24+ durumu:**
- `addDnsServers(...)` çağrılmıyor (memory notu: "UdpRealTunnel broken, OS carrier DNS")
- OS carrier DNS → cihazın fiziksel network interface'inden (WiFi veya 4G) DNS sorgusu
- Eğer cihazda **Android 9+ Private DNS açıksa** (varsayılan "Automatic" = çoğu cihazda "dns.google" veya ISP'nin DoT'u):
  - **Private DNS, VPN'in addDnsServer'ını override eder** (Android 10+ davranışı) [1][2][3]
  - Hatta **lockdown modunda bile** Private DNS tünel dışına çıkabiliyor [2]
  - DNS sorgusu VPN tünelinden değil, fiziksel interface'den gider

**Sprint 24+ bu davranışı test etmemiş çünkü:**
1. `addDnsServers` çağırmıyor → test edilecek bir şey yok
2. Sprint 24+ memory notu "OS carrier DNS" diyor ama bu, **OS'un DNS davranışının Private DNS tarafından override edileceğini** hesaba katmıyor
3. Eğer user OnePlus'ta Private DNS "dns.google" set ettiyse, DNS çözümleme Google'ın DoT endpoint'ine gidiyor → o endpoint'e ulaşılamazsa (TT 4G'de Google DNS engelleniyor olabilir) → DNS çözümlenmiyor → tüm HTTPS bağlantıları sessizce düşüyor

**Çözüm (Sprint 25 önerisi):**
1. Önce teşhis: `adb shell settings get global private_dns_mode` — "off", "hostname", "opportunistic" hangisi?
2. Eğer "hostname" veya "opportunistic" ise, test sırasında **"off"** yap (Sprint 24+'da bunu yapmamış olabilir)
3. Custom DNS forwarder yaz (Sprint 24+'da yok): DNS UDP paketlerini TUN'da gör, kendi resolver'ına gönder (8.8.8.8 / 1.1.1.1) — bu, **UdpRealTunnel'dan bağımsız** olur çünkü kendi resolver'ın, kendi out-of-tunnel UDP socket'ını kullanır
4. Veya `addDnsServer("8.8.8.8")` + `addDnsServer("1.1.1.1")` + kullanıcıya "Private DNS'i kapat" talimatı

**Not:** Memory'de "1.1.1.1 DNS drop tezi çürütüldü" var — yani TT 4G'de 1.1.1.1'in drop olmadığı doğrulandı. O zaman DNS'i `["8.8.8.8", "1.1.1.1"]` yapıp Private DNS'i kapatmak, user'ın yaşadığı "internet kesiliyor" sorununu çözebilir.

---

## 6. Per-app VPN ve `setUnderlyingNetworks` — Default Network Sorunu

Sprint 24+ handoff: "Per-app VPN fix for isDefaultNetwork=false". Bu OnePlus + Android 14 + TT 4G üzerinde test edilmiş ve çalıştığı gözlemlenmiş.

Ama bu fix **Android 15 ve Android 16'da aynı şekilde çalışacağının garantisi yok.** AOSP kaynak kodu incelendiğinde [4]:

```java
// VpnService.Builder.setUnderlyingNetworks (API 31+)
// null: default network kullan (yani OS ne derse onu)
// non-empty array: bu network'leri underlying olarak kullan
// empty array: underlying network yok
```

Sprint 24+ `setUnderlyingNetworks` çağırmıyor → null default → "system default network" → bu, **WiFi tercih edilen** varsayılan. OnePlus + Android 14'te per-app VPN yeterli olmuş olabilir, ama Android 15+ veya farklı OEM'lerde güvenilir değil.

**Sprint 25 için net öneri:**
```kotlin
// VpnService.Builder'ı kurduktan sonra, establish() öncesi:
builder.setUnderlyingNetworks(null) // null = system default
// veya explicit olarak:
// val activeNetwork = connectivityManager.activeNetwork
// if (activeNetwork != null) builder.setUnderlyingNetworks(arrayOf(activeNetwork))
```

Bu, "Block connections without VPN" lockdown modunda bile VPN'i default network yapıyor. Sprint 24+ handoff'unda bu yapılmamış.

**Ek olarak — `bindProcessToNetwork()`** [20]:
```kotlin
val builder = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
connectivityManager.requestNetwork(builder.build(), object : NetworkCallback() {
    override fun onAvailable(network: Network) {
        connectivityManager.bindProcessToNetwork(network)
    }
})
```
Bu, kendi process'imizi (VPN service) VPN network'üne bind ediyor. `protect()` çağırmaya gerek kalmıyor (bizim process zaten VPN'de). **Bu Sprint 24+'da yok** ve düşünülmeli.

---

## 7. Wirebare'in Dayattığı Kısıtlamalar — Net Tablo

Sprint 24+ wirebare dependency'sini sürdürüyor. Bunun bedeli:

| Kısıtlama | Wirebare'in dayattığı | Sprint 24+'daki workaround | Workaround yeterli mi? |
|---|---|---|---|
| MITM için CA cert | HTTPS inspection için wirebare CA cert gerekli (system trust store'a kur) | `clearHttpInterceptor()` + `clearAsyncHttpInterceptor()` | ✓ Yeterli, çünkü MITM yapmak istemiyoruz |
| TcpProxyServer broken | Sprint 22.12'den beri bilinen bug | Custom `SimpleTcpForwarder` yazıldı, wirebare TcpProxyServer bypass ediliyor | ⚠️ Yeterli ama dormant kod var |
| UdpRealTunnel broken | Sprint 22.10+'dan beri bilinen bug | UdpRealTunnel kullanılmıyor, OS carrier DNS'e güveniliyor | ✗ **YETERSİZ** — DNS UDP paketleri TUN'da görünmüyor |
| `tcpProxyServerCount` | wirebare 1+ TCP proxy server'ı kendi yönetir | `tcpProxyServerCount = 1` set edildi ama bypass ediliyor | ⚠️ Yeterli ama anlamsız |
| MITM zincirinin kod yüzeyi | HTTPS inspection için gerekli kod hâlâ dependency'de | Clear ediliyor ama sınıf yüklüyor | ⚠️ Bakım yükü |
| `mockPacketLossProbability` | test için güzel özellik | Sprint 24+ bunu kullanmıyor | ✗ Gerekmiyor ama dependency'de var |

**Net:** Wirebare'in Sprint 24+'a gerçek katkısı, `VpnService.Builder` üzerinde DSL sağlamak (fluent `addRoutes`, `addDnsServers`, `addAllowedApplications`, `addHttpInterceptor`). Bunların hepsi native `VpnService.Builder`'da var. Wirebare dependency'si Sprint 25'te çıkarılabilir — Sprint 24+ base state'i sadece DSL kullanıyor, runtime davranışı custom.

---

## 8. Sprint 25 İçin Net Yol Haritası

Araştırma bulgularının ışığında, kod değişikliği için Sprint 25'e girmeden önce önerilen sıra:

### Aşama 1 — Semptom teşhisi (önce veri topla, sonra yaz)

Sırasıyla şu testleri yap, hepsinin loglarını al:

1. `adb shell settings get global private_dns_mode` — Private DNS durumunu öğren
2. Private DNS'i "off" yap, VPN'i aç, "Şifreleme Doğrulamayı Başlat"a bas — internet çalışıyor mu?
3. Private DNS'i "off" bırak, VPN'i aç, logcat'te DNS çözümlemesi başarılı mı kontrol et
4. `adb logcat -d | grep -i "vpn\|dns\|connectivity"` — Private DNS'in devre dışı bırakılması semptomu çözüyor mu?
5. Aynı testi 4G + WiFi'de tekrarla
6. `adb logcat -d | grep "SimpleTcpFwd\|SimpleTcpInt"` — ikinci bağlantı timeout'u hangi senaryo (A/B/C/D, handoff'tan)

Bu test sonuçlarına göre Aşama 2'nin yönü netleşir.

### Aşama 2 — Sprint 25'te yapılacak iş (olası iki yol)

**Yol X — eğer Aşama 1'de Private DNS suçlu çıkarsa (en olası):**
- `addDnsServers("8.8.8.8", "1.1.1.1")` ekle (memory notu "1.1.1.1 çalışıyor" diyor)
- Test sırasında Private DNS'in "off" olduğundan emin ol
- Production'da kullanıcıya "Ayarlar → Ağ → Özel DNS → Kapalı yap" talimatı göster
- Custom UDP DNS forwarder yaz (UdpRealTunnel'dan bağımsız, kendi out-of-tunnel UDP socket + protect)
- DNS UDP çözümlemesi başarılı olunca `isDefaultNetwork=false` sorunu da büyük olasılıkla çözülür

**Yol Y — eğer Aşama 1'de per-app VPN isDefaultNetwork=false suçlu çıkarsa:**
- `setUnderlyingNetworks(null)` veya explicit activeNetwork array ekle
- `bindProcessToNetwork()` ile VPN service'i VPN network'üne bind et
- Per-app VPN'i kaldırıp full VPN dene (`addAllowedApplications` YOK) — diğer uygulamaların internet'i kesilir ama bu zaten test senaryosu
- Eğer full VPN çalışıyorsa, per-app VPN'i geri getir, sorun izole

**Yol Z — eğer yukarıdakilerin ikisi de çalışmazsa ve ikinci bağlantı race'i de doğrulanırsa:**
- Wirebare dependency'yi tamamen çıkar (Pattern A saf)
- `SimpleTcpForwarder`'ı NIO Selector pattern'ine refactor et
- Tüm testleri sıfırdan koş

### Aşama 3 — Production hardening

Yol X veya Y çalıştıktan sonra, üretim sağlamlığı için:

- `setUnderlyingNetworks` her durumda çağrılmalı
- `bindProcessToNetwork()` retry pattern'i ile birlikte (3 deneme × 3s, SO cevapsız kalabilir)
- `excludesPrivateDns` test ekle — Private DNS açıkken VPN'in hala çalıştığını doğrula
- `secondConnectionTimeout` test ekle — 3 bağlantı arka arkaya, hepsi başarılı olmalı

---

## 9. "Ama Sprint 24+ Default Mode Çalışıyor" İddiası Hakkında

Memory notundaki "Sprint 24+ base state — owner confirmed working" entry'si ile user'ın "VPN açılınca internet kesiliyor" şikayeti arasında ciddi bir tutarsızlık var. Bu üç şekilde açıklanabilir:

1. **Memory notu eski** — Sprint 24+'ın daha eski bir versiyonunda "çalışıyor" gözlemlenmiş, sonra Android veya OnePlus güncellemesi bozmuş olabilir.
2. **Test koşulları farklı** — Sprint 24+ test edildiğinde Private DNS "off" idi; şimdi "on" (kullanıcı ayar değiştirmiş, OnePlus firmware update, vb.).
3. **"Çalışıyor" gözlemi sınırlıydı** — owner ilk bağlantıyı test etmiş, "çalışıyor" notu düşmüş, ama ikinci bağlantı + uzun süreli stabilite test edilmemiş.

Hangi açıklama doğru olursa olsun, Sprint 24+ kod state'i production-ready değil — Aşama 1'deki teşhis olmadan hangi yolun (X/Y/Z) izleneceği söylenemez.

---

## 10. Sonuç

**Tek cümle:** Sprint 24+ mimari olarak doğru yönde (custom TCP forwarder + per-app VPN + MITM yok), ama "VPN açılınca internet kesiliyor" semptomunun birincil sebebi büyük olasılıkla `addDnsServers` çağırmamak (Private DNS override'a kurban gitmiş) + `setUnderlyingNetworks` çağırmamak (per-app VPN isDefaultNetwork=false kalıyor). İkinci bağlantı timeout'u ise accept loop race condition. **Aşama 1 teşhis olmadan kod değişikliği yapmak, 12+ sprintlik debug döngüsünü uzatır.**

**Net aksiyon:** Sprint 25'e başlamadan önce 6 teşhis adımını (Aşama 1) çalıştır. Çıkan sonuca göre Yol X (DNS) veya Yol Y (default network) veya Yol Z (wirebare'den çıkış) seç. Üç yol da kanonik pattern'leri takip ediyor, hangisinin doğru olduğu cihaz + OS + ayar kombinasyonuna bağlı.

**Kırmızı bayrak:** Sprint 24+ base state'i 12+ sprint sonra hâlâ "şu çalışıyor / bu kırılgan / şu sorgulanabilir" durumunda. Bu, doğru bir mimari kararın doğru bir koşulda test edilmediğinin göstergesi. **Önce koşulu netleştir, sonra kodu yaz.**

---

## Kaynakça

[1] celzero/rethink-app GitHub Issue #25 — "Bypass Private DNS", https://github.com/celzero/rethink-app/issues/25

[2] Tor Project Forum — "Tor VPN doesn't work with Private DNS enabled", https://forum.torproject.org/t/tor-vpn-doesnt-work-with-private-dns-enabled/21198

[3] Reddit r/Express_VPN — "Android Private DNS - Does It Clash With EVPN", https://www.reddit.com/r/Express_VPN/comments/15f95bt/android_private_dns_does_it_clash_with_evpn/

[4] AOSP frameworks/base — VpnService.java, https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/VpnService.java

[5] Android Developers — VpnService.Builder API Reference, https://developer.android.com/reference/android/net/VpnService.Builder

[6] GitHub Gist (Addvilz) — "Java NIO tcp tunnel", https://gist.github.com/Addvilz/bd36bb423f3861296e4ef1127f0119bd

[7] How-To Geek — "VPN connections are broken on Android 16, and there's still no fix", https://www.howtogeek.com/vpn-connections-are-broken-on-android-16-and-theres-still-no-fix/

[8] Google Pixel Phone Support — "Built-in Google VPN deadlock when 'Block connections without VPN' is active", https://support.google.com/pixelphone/thread/391108171/built-in-google-vpn-deadlock-when-block-connections-without-vpn-is-active

[9] Stack Overflow — "Android VpnService Configuration", https://stackoverflow.com/questions/29810727/android-vpnservice-configuration

[10] Stack Overflow — "Android VpnService route exclusion on API prior to 33", https://stackoverflow.com/questions/76582003/android-vpnservice-route-exclusion-on-api-prior-to-33

[11] Android Developers — VPN, https://developer.android.com/develop/connectivity/vpn

[12] Cloudflare 2024 Year in Review (CSDN mirror), https://blog.csdn.net/2401_89757965/article/details/145497829

[13] LondonX/tun2socks-android — GitHub, https://github.com/LondonX/tun2socks-android

[14] ksharpdabu/go-tun2socks — GitHub, https://github.com/ksharpdabu/go-tun2socks

[15] koush/AndroidAsync — GitHub, https://github.com/koush/AndroidAsync

[16] johnjohnsp1/NetGuard — GitHub, https://github.com/johnjohnsp1/NetGuard

[17] (NetGuard related) NetCapture/Firewall — GitHub, https://github.com/NetCapture/Firewall

[18] Kokomi7QAQ/wirebare-android — GitHub, https://github.com/Kokomi7QAQ/wirebare-android

[19] Kokomi7QAQ/wirebare-kernel — GitHub, https://github.com/Kokomi7QAQ/wirebare-kernel

[20] Stack Overflow — "Why Cannot assign Google DNS adress to Android VPN service, only local DNS server", https://stackoverflow.com/questions/74748822/why-cannot-assign-google-dns-adress-to-android-vpn-service-only-local-dns-server
