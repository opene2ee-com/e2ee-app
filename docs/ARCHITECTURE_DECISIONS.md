# OpenE2EE Projesi - Mimari Kararlar ve Teknoloji Yığını (Tech Stack)

Tarih: 1 Temmuz 2026 (orijinal) — **10 Temmuz 2026 (güncellendi: §2 + §4 + §5, Go mobilde çalışır, ham veri cihazda kalır, sadece özet `api-test.opene2ee.com`'a gider)**

Fizibilite raporu ve MVP (Minimum Viable Product) hedefleri doğrultusunda alınan nihai mimari kararlar aşağıda listelenmiştir. **Sprint 9.8+ için bağlayıcıdır; önceki sürümle çelişen bölümler aşağıda "GÜNCELLENDİ" notuyla işaretlenmiştir.**

---

## 1. Frontend (Mobil ve Web)

Tüm kullanıcı arayüzleri (hem veri toplayan mobil uygulama hem de B2B SaaS platformu/dashboard) için tek bir kod tabanından ilerlenecektir:

* **Framework:** **Flutter**
* **Neden:** Flutter, hem iOS hem Android için güçlü Native performans sunarken, aynı zamanda Web derlemesi (Flutter Web) ile dashboard ihtiyacını da aynı ekip ve aynı kod tabanı ile çözmeyi sağlar.
* **Grafik ve Görselleştirme:** Şeffaflık matrisi ve entropi/güvenlik skorlarını görselleştirmek için Flutter ekosisteminin en popüler kütüphanesi olan **`fl_chart`** kullanılacaktır.
* **State / Routing (güncellendi 10.07.2026):** Mobilde `flutter_riverpod` + `go_router` (Sprint 9.6.8 fix, audit S7). Web dashboard tarafında aynı paket seti.

---

## 2. Backend (Aggregator + Dashboard) — GÜNCELLENDİ 10.07.2026

**Önceki tasarım:** Backend = analiz + skorlama motoru. `gopacket` ile paket ayrıştırma, entropy hesaplama, IP/TCP/UDP metadata çıkarma **backend'te** yapılıyordu. Bu tasarım §5 (Veri Minimizasyonu) ile çelişiyordu — ham veri cihaz dışına çıkıyordu.

**Yeni tasarım:** Backend artık **yalnızca aggregator + dashboard**. Paket ayrıştırma, entropy hesaplama, IP maskeleme **mobilde** yapılır (Go native + Flutter binding, detay için §5.6). Backend sadece:

* **Ana Dil:** **Go (Golang)**
* **Sorumluluk:** `api-test.opene2ee.com` üzerinde `POST /telemetry` endpoint'i — mobilin gönderdiği anonimleştirilmiş JSON özetlerini alır, TimescaleDB'ye yazar, dashboard API'sine sunar.
* **Paket analizi YOK.** `gopacket` artık mobil native library olarak derlenir (§5.6); backend Go saf HTTP/REST + storage işlemleri yapar.
* **Kimlik doğrulama:** API key (Authorization: Bearer header) + rate limit (60 req/min per device).

> Geçiş planı: Sprint 9.8.0 — eski gopacket kodu mobil native katmana taşınır, backend yeni aggregator rolüne geçer. Mevcut backend API contract'ı (POST /telemetry) korunur; sadece payload formatı cihaz-üretimi özet olur (eskiden backend üretiyordu).

---

## 3. Veritabanı ve Önbellek (Veri Depolama)

Güvenlik skorları ve zaman serisi (time-series) analizlerini tutacak devasa veri altyapısı:

* **Ana Veritabanı:** **PostgreSQL**
* **Zaman Serisi Uzantısı:** Mevcut production postgre kurulumu içerisinde **TimescaleDB** eklentisi veritabanı bazında aktif edilecektir (Anlık saniyelik trafik loglarını performanslı sorgulamak için).
* **Geliştirme Ortamı (Dev):** `bildirops/postgredev` docker/altyapı kurulumu geliştirme süreci için kullanılacaktır.
* **Önbellek (Cache):** **Redis** (Sık sorgulanan IP adresi imzaları, anlık oturum verileri ve geçici analiz sonuçlarını hızlı sunmak için).
* **Ne depolar:** Sadece anonimleştirilmiş özet kayıtları. Ham payload, gerçek IP, payload içeriği **hiçbir zaman** veritabanına yazılmaz (KÖTÜ TASARIM = release blocker).

---

## 4. MVP (Minimum Viable Product) Kapsamı — GÜNCELLENDİ 10.07.2026

1. **Flutter mobil uygulama:** VPN Service (Android) / NetworkExtension (iOS) ile trafiği sampling yöntemiyle kopyalar, **mobil native Go + gopacket** ile cihazda IP/TCP/UDP/TLS metadata çıkarır ve maskeler, **sadece özet JSON**'u `https://api-test.opene2ee.com/telemetry`'a POST eder. Ham payload hiçbir zaman cihaz dışına çıkmaz.
2. **Go backend (api-test.opene2ee.com):** Cihazlardan gelen anonim özet JSON'ları alır, TimescaleDB'ye yazar, dashboard API'sine sunar. Paket ayrıştırma **yapmaz** (mobilde yapıldı).
3. **Aynı Flutter kod tabanı ile derlenmiş Web Dashboard:** Veritabanındaki skorları `fl_chart` ile görselleştirir.
4. **Hibrit Yük Dağılımı (Pil ve Gizlilik Dengesi):** §5.2 ile uyumlu — tüm hassas işlem cihazda, sadece anonim özet gider. Sampling yöntemiyle (yeni bir oturumun sadece ilk birkaç paketi) pil optimizasyonu sağlanır.

> Eski §4 (1 Temmuz 2026 versiyonu) "Go ve gopacket ile yazılmış, cihazlardan gelen anonim telemetri ... hesaplayan backend" diyordu — **iptal edildi**. Yeni tasarımda hesaplama cihazda, backend sadece toplama.

---

## 5. Regülasyon Uyumu ve Mağaza Politikaları (App Store & Google Play) — YENİDEN YAZILDI 10.07.2026

"Local VPN" arayüzü kullanılarak ağ trafiğinin cihaz içinde izlenmesi, uygulama mağazalarında çok sıkı denetimlere tabidir. Uygulamanın mağazalardan onay alabilmesi için mimaride aşağıdaki prensipler uygulanacaktır:

### 5.1 Açık Kaynak Şeffaflığı

Proje tamamen **Açık Kaynak Kodlu (Open Source)** olacak ve **MIT Lisansı** ile GitHub'da yayınlanacaktır. İnceleme (App Review) ekiplerine kaynak kodun açık olduğu gösterilerek uygulamanın gizli bir ajandası olmadığı (arka kapı veya casus yazılım olmadığı) kanıtlanacaktır.

### 5.2 Veri Minimizasyonu (Cihazda İşlem) — güçlendirildi 10.07.2026

Flutter uygulaması, ağ paketlerinin ham içeriğini (payload), hedeflenen IP adresini veya TLS şifreleme anahtarlarını **kesinlikle cihaz dışına göndermeyecektir**. Tüm paket ayrıştırma, entropy hesaplaması, IP/TCP/UDP maskeleme **cihazda** (mobil native katman, §5.6) yapılır. Backend'e sadece şu formatta tamamen anonim JSON telemetri gider:

```json
{
  "sessionId": "uuid-v4",
  "sampledAt": "2026-07-10T17:30:00Z",
  "packets": [
    { "srcIpMasked": "10.42.0.0", "dstIpMasked": "1.1.1.0", "srcPort": 443, "dstPort": 51234, "protocol": "TCP", "tcpFlags": 24, "tlsClientHelloFingerprint": "a1b2" }
  ],
  "samplingCap": 10
}
```

Bu format `OpenE2eeVpnService.kt` (Sprint 9.7.0) ile aynı çıktıyı verir; geriye dönük uyumluluk korunur.

### 5.3 Pazarlama Konumlandırması

Uygulama mağazalara bir VPN aracı olarak değil, uçtan uca şifreleme (E2EE) durumunu ve ağ trafiğini test eden bir **"Ağ Güvenliği ve Şeffaflık Aracı (Network Security Tool)"** olarak sunulacaktır (Google Play VpnService istisna kategorisi).

### 5.4 Açık Onam (Consent UI)

Uygulamanın ilk açılışında, trafiğin **sadece cihaz içinde işlendiği** ve sunucuya **şifresiz ham veri aktarılmadığı** (sadece anonimleştirilmiş özet metadata gönderildiği) belirten tam sayfa şeffaf bir aydınlatma/onam ekranı yer alacaktır. iOS tarafında ise sadece resmi `NetworkExtension` API'si kullanılacaktır.

Consent metni §5.2'deki JSON formatını örnek olarak gösterir, kullanıcıya "bu veri nereye gider, ne kadar, hangi formatta" açıkça belirtilir.

### 5.5 Görev Tabanlı (Task-Based) Çalışma Modeli

Uygulama arka planda 7/24 çalışan bir izleme aracı olmak yerine, tamamen **kullanıcı tetiklemesiyle (on-demand)** çalışan bir test aracıdır. Kullanıcı uygulamayı açtığında karşısına *"Turkcell üzerinden RCS testi yap (Görev)"* veya *"WhatsApp şifreleme bütünlüğünü doğrula"* gibi görevler çıkar. VPN profili sadece bu görev süresince (örneğin 2 dakika) aktif olur, test bitince kapatılır. Bu "pil tüketimi" sorununu kökten çözer ve mağaza onayında "arka planda izinsiz izleme" şüphelerini ortadan kaldırır.

### 5.6 Paket Analizi Cihazda — YENİ 10.07.2026

Paket ayrıştırma + maskeleme + entropy hesaplama **mobil native katmanda** yapılır. Üç uygulama yolu değerlendirilmiştir:

| Yol | Yaklaşım | Artı | Eksi |
|---|---|---|---|
| **A (önerilen)** | Go kodunu Android NDK + iOS CocoaPods'a cross-compile, Flutter `dart:ffi` ile çağır | gopacket aynen kullanılır, hızlı | Karmaşık build setup, mimari risk |
| **B** | Go kodunu Dart'a port et, `dart:io` + custom IP/TCP/UDP parser | Saf Dart, FFI yok, basit build | gopacket yeniden yazılır (1-2 hafta iş) |
| **C** | Native Kotlin (Android) / Swift (iOS) + Go shared library JNI üzerinden | iOS Swift'e paralel pattern | Hem Kotlin hem Go bilgisi, 2 katmanlı FFI |

**Sprint 9.8.0 kararı: A (Go NDK + FFI)** — mevcut Go + gopacket kodu aynen kullanılır, mobil platforma taşınır. Çıktı JSON formatı `OpenE2eeVpnService.kt` ile birebir aynıdır (Flutter tarafında `lib/services/vpn_service.dart` bu JSON'u okur, `MethodChannel` üzerinden Kotlin tarafına geçirir, Kotlin Go FFI'yı çağırır).

Ham payload **hiçbir koşulda** Go FFI çıktısında yer almaz. `OpenE2eeVpnService.kt` zaten bu invariant'a uygun (PR-28 §B.1, PR-22a); yeni Go katmanı da aynı invariant'ı korur.

### 5.7 api-test.opene2ee.com Telemetry Endpoint — YENİ 10.07.2026

* **Endpoint:** `POST https://api-test.opene2ee.com/telemetry`
* **Auth:** `Authorization: Bearer <device_api_key>` (cihaz başına tek key, secret-store'da)
* **Payload:** §5.2'deki JSON (sadece özet metadata, ham veri YOK)
* **Rate limit:** 60 req/min per device, 1000 req/hour per device
* **Response:** `202 Accepted` + `sessionId` echo
* **Hata durumları:** `400` (malformed JSON), `401` (auth fail), `429` (rate limit), `5xx` (server error, retry with backoff)
* **Veri saklama:** Backend sadece gelen özeti TimescaleDB'ye yazar (5 yıl retention, GDPR/KVKK uyumlu). Ham payload, gerçek IP, payload içeriği **hiçbir zaman** yazılmaz.
* **Mevcut API contract'ı korunur:** Eski backend zaten `POST /telemetry` kabul ediyordu; sadece payload formatı artık cihaz-üretimi özet (eskiden backend üretiyordu).

---

## 6. Operasyonel Model: Gönüllülük ve Görev Odaklı Test (Gamification)

Uygulama arka planda 7/24 çalışan bir izleme aracı olmak yerine, tamamen **kullanıcı tetiklemesiyle (on-demand)** çalışan bir test aracına dönüştürülecektir. Bu modelin mimariye katkıları şunlardır:

* **Görev Tabanlı (Task-Based) Yaklaşım:** Kullanıcı uygulamayı açtığında karşısına *"Turkcell üzerinden RCS testi yap (Görev)"* veya *"WhatsApp şifreleme bütünlüğünü doğrula"* gibi görevler çıkacaktır. VPN profili sadece bu görev süresince (örneğin 2 dakika) aktif edilecek, test bitince kapatılacaktır. Bu durum "pil tüketimi" sorununu kökten çözer.
* **Kontrollü Alıcı (Receiver) Opsiyonları ve MVP Kararı:** MVP aşamasında henüz kurumsal WhatsApp Business API veya RCS sunucu altyapısı (ve bütçesi) bulunmadığı için testler tamamen **P2P (Gönüllü Eşleşmesi)** üzerinden yürüyecektir:
  1. **P2P Gönüllü Eşleşmesi (MVP Ana Yöntemi):** Uygulamanın ilk sürümünde (MVP) testler bizzat kurucular ve erken aşama gönüllüler arasında yapılacaktır. Kullanıcılar (örneğin siz ve ortağınız) uygulamadan "Alıcı Ol (Nöbet)" moduna geçecek ve test mesajlarını birbirinize atarak E2EE doğrulaması yapacaksınız.
  2. **Merkezi Echo-Bot ve Bulut Sanal Numaralar (Faz 2):** Proje büyüdüğünde, son kullanıcılar için manuel süreci ortadan kaldırmak (frictionless UX) adına resmi OpenE2EE bot numaraları ve API entegrasyonları devreye alınacaktır.
  Bu çoklu opsiyon mimarisi sayesinde hem gönderen cihazda hem de alıcıda ağ paketleri eşzamanlı ölçülerek E2EE %100 ispatlanabilecektir.
* **Mağaza Onayında Avantaj:** Kullanıcının testi kendi rızasıyla başlatıp bitirmesi, Apple ve Google'ın "arka planda izinsiz izleme" şüphelerini tamamen ortadan kaldırır.
* **Gönüllü Alıcı Tetikleme (Active Pool Modeli):** iOS işletim sisteminin arkaplanda bildirim okuma (Notification Listener) ve izinsiz VPN başlatma kısıtlamaları nedeniyle P2P testlerde "Aktif Nöbet" modeli kullanılacaktır. Gönüllü kullanıcı uygulamada "Alıcı Ol (15 dk)" butonuna basarak kendi VPN'ini aktif edecek ve Backend'deki hazır alıcılar havuzuna girecektir. Test yapmak isteyen diğer kullanıcılar, havuzdaki bu aktif (nöbetteki) gönüllülerle eşleştirilecektir.

---

## Geçiş Özeti (1 Temmuz 2026 → 10 Temmuz 2026)

| Bölüm | Eski | Yeni | Sprint 9.8.0 aksiyonu |
|---|---|---|---|
| §2 | Backend = analiz motoru (gopacket backend'te) | Backend = sadece aggregator | Go kodunu mobil NDK'ya taşı, backend'i sadece POST /telemetry'a indir |
| §4.2 | Go + gopacket backend'te Şeffaflık Matrisi hesaplar | Go + gopacket mobilde özet çıkarır, backend toplar | Mobil native binding, FFI entegrasyonu |
| §5.2 | "Cihazda hesap, backend'e anonim telemetri" | "Cihazda HER ŞEY (parse + mask + entropy), backend'e sadece özet JSON" | Consent UI'ı güncelle, JSON formatını spec'le |
| §5.6 | (yoktu) | Paket analizi cihazda — Go NDK + FFI yolu | Go cross-compile setup, Android NDK build |
| §5.7 | (yoktu) | api-test.opene2ee.com endpoint spec | Backend contract güncelle, auth + rate limit ekle |
| §6 | (değişmedi) | (değişmedi) | — |

---

**Geçerli sürüm:** 10.07.2026 — Sprint 9.8.0+ için bağlayıcı.
**Önceki sürüm:** 1.07.2026 — yalnızca referans amaçlı (Sprint 3-8 chain).
**Push kararı:** Owner onayı sonrası `git push origin main`.
