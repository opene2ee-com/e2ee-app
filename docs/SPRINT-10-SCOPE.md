# Sprint 10 Scope — UI-First (Revize 10.07.2026)

**Tarih:** 10 Temmuz 2026 (Europe/Istanbul, UTC+3) — **revize 18:51** (Owner direktifi: VPN kaldır, GSM operatörleri kaldır, WhatsApp deep link, yeni bilgilendirme metni, 3-stat aktif nöbet)
**Base:** `origin/main @ b406c7e` (Sprint 9.7.0 + workflow fix + arch rewrite)
**Pattern:** UI-first, exploratory — Owner direktifi: "önce arayüz, sonra business kuralları; arayüze bakarak bir şeyler netleşebilir"
**Sprint zinciri:**
- **Sprint 10.0 (BU SPRINT):** UI-only — gerçek ekranlar, mock data, navigation, state. Business logic YOK.
- **Sprint 10.1 (sonraki):** Go NDK + FFI binding, gerçek VPN service, api-test POST /telemetry.
- **Sprint 10.2+:** P2P eşleşme, Active Pool, gerçek görevler, Skorlar tab'ı.

---

## 🔄 ÖNEMLİ REVİZYON (10.07.2026 18:51)

Owner tasarımı büyük ölçüde değiştirdi. Yeni tasarım Sprint 10.0'ın tüm kapsamını etkiler:

| Konu | Eski tasarım (18:14) | Yeni tasarım (18:51) |
|---|---|---|
| **İlk ekran başlığı** | "Consent Screen — Açık Onam" | **"Bilgilendirme"** |
| **İlk ekran metni** | JSON spec + veri minimizasyonu teknik açıklaması | **"OpenE2EE için gönüllü olduğunuz için teşekkürler. Taahhütümüzün arkasındayız, kesinlikle telefon numaranız, cihaz bilgileriniz, ip adresiniz telefonunuzdan dışarıya çıkmamaktadır."** |
| **VPN ifadesi** | "VPN AKTİF", "VPN Profili Aktif Et" badge | **TÜM EKRANLARDAN KALDIRILDI** (App Store politika uyumu) |
| **Ana menü görevleri** | Turkcell RCS, WhatsApp, Vodafone VoLTE, Türk Telekom (4 GSM operatörü) | **RCS Mesajları** + **WhatsApp** (sadece 2 görev, GSM operatörü YOK) |
| **WhatsApp task detail** | Timer + "Özet Gönder" | **Hazır mesaj kartı** ("Bu mesaj şifreleme bütünlüğü için test amacıyla gönderilmiştir") + **"Gönder"** butonu → WhatsApp deep link |
| **Aktif Nöbet** | Toggle + 1 stat (alıcı sayısı) | **3 stat:** İzlenen Paket (247), Bağlı Gönüllü (3), Test Edilenler (RCS ✓ + WhatsApp ✓) |
| **Aktif Nöbet konumu** | FAB + bottom nav | Bottom nav (3 tab) — Skorlar placeholder |

> Yeni tasarım görseli: `C:\Users\User\.mavis\sessions\mvs_0bf224775d8644fbb3c76f9a8b0283c0\workspace\sprint10-wireframes.html` ve `image_fd1f80f9.png` (mockup).

---

## Sprint 10.0 Kapsam (UI-only, revize)

### 1. pubspec.yaml — gerçek dependencies

```yaml
dependencies:
  flutter:
    sdk: flutter
  cupertino_icons: ^1.0.8
  flutter_riverpod: ^2.5.1
  go_router: ^14.0.0
  fl_chart: ^0.68.0
  url_launcher: ^6.2.0   # WhatsApp deep link için (Sprint 10.0 — yeni)
```

### 2. mobile/lib/main.dart — app shell

- `ProviderScope` wrap (audit S7 + S22)
- `MaterialApp.router` + go_router shell
- Theme (Material 3 + OpenE2EE brand colors)
- L10n placeholder (Türkçe varsayılan)

### 3. mobile/lib/screens/ — 4 ekran (gerçek, mock data ile)

#### 3.1 `bilgilendirme_screen.dart` — Bilgilendirme (yeni ad, eski consent)
- Tam sayfa, koyu yeşil hero
- Başlık: "Bilgilendirme"
- Body: tam metin (yukarıdaki "OpenE2EE için gönüllü olduğunuz için teşekkürler..." paragraf)
- "Anladım, Devam Et" butonu
- Kabul edilince → home_screen'e navigate

#### 3.2 `home_screen.dart` — Ana Menü / Görevler (GSM operatörü YOK)
- AppBar: "OpenE2EE" + ayarlar ikonu
- 2 task kartı (sadece 2, yeni):
  - **RCS Mesajları** — turuncu icon, "RCS üzerinden gönderilen mesajların şifreleme bütünlüğünü test et"
  - **WhatsApp** — yeşil WhatsApp icon, "WhatsApp üzerinden hazır mesaj gönder, şifreleme bütünlüğünü kanıtla"
- Her kart: "Başla" butonu
- BottomNavigationBar: 3 tab (Görevler, Aktif Nöbet, Skorlar)
- FAB: "Alıcı Ol (Nöbet)" → active_pool_screen'e

#### 3.3 `whatsapp_task_detail_screen.dart` — WhatsApp Şifreleme Testi (yeni)
- AppBar: geri butonu + "WhatsApp" başlık
- Card: hazır mesaj (`chat-bubble` stili, yeşil WhatsApp bubble)
  - Mesaj metni: **"Bu mesaj şifreleme bütünlüğü için test amacıyla gönderilmiştir."**
  - "Gönder'e bastığında WhatsApp açılacak ve mesaj hazır olacak" hint
- "Gönder" butonu (yeşil WhatsApp rengi) — `url_launcher` ile `whatsapp://send?text=ENCODED_MESSAGE` deep link açar
- "İptal" butonu (outline)

> Not: VPN ifadesi YOK. Badge YOK. Status pill YOK. Tek başlık + mesaj + gönder butonu.

#### 3.4 `active_pool_screen.dart` — Aktif Nöbet Modu (3 stat, yeni)
- Hero: turuncu "Aktif Nöbet Modu"
- Toggle card: "Alıcı Ol" + "Havuzda 15 dk bekle"
- 3 stat card (grid 2+1):
  - **İzlenen Paket** — 247 (mock)
  - **Bağlı Gönüllü** — 3 (mock)
  - **Test Edilenler** — RCS ✓ + WhatsApp ✓ (2 pill)
- Bottom nav: Aktif Nöbet tab active

### 4. mobile/lib/state/ — Riverpod providers (mock data ile)

- `is_accepted_provider.dart` — `isAcceptedProvider` (StateProvider<bool>, bilgilendirme kabul durumu)
- `tasks_provider.dart` — `tasksListProvider` (2 mock görev: RCS + WhatsApp)
- `pool_provider.dart` — `poolStatusProvider` (StateNotifierProvider, paketSayisi + gonulluSayisi + testEdilenler)
- `whatsapp_deeplink_provider.dart` — helper (url_launcher çağrısı)

### 5. mobile/lib/widgets/

- `task_card.dart` — görev kartı (RCS için turuncu icon, WhatsApp için yeşil icon)
- `stat_pill.dart` — durum göstergesi
- `chat_bubble.dart` — WhatsApp mesaj preview bubble

### 6. mobile/lib/theme/

- `app_theme.dart` — Material 3 + brand colors (primary `#2f6f5e`, accent `#c97b3f`, WhatsApp green `#25d366`)

---

## Audit gap'leri (Sprint 10.0 eklenecek)

- **S21:** `pubspec.yaml` `flutter_riverpod:` + `go_router:` + `fl_chart:` + `url_launcher:` all present (PyYAML parse)
- **S22:** `mobile/lib/main.dart` contains `ProviderScope(` literal
- **S23:** `mobile/lib/screens/` en az 3 .dart dosyası
- **S24:** `mobile/lib/state/` en az 2 .dart dosyası
- **S25 (YENİ):** `mobile/lib/` altında `lib/main.dart` + `lib/screens/*.dart` içinde "VPN" string'i YOK (case-insensitive). Sprint 10.0 kapsamı: kod ve string'lerden VPN kelimesi tamamen kaldırıldı.
- **S26 (YENİ):** `mobile/lib/screens/whatsapp_task_detail_screen.dart` içinde `whatsapp://send?text=` deep link URI scheme'i var.

Self-test: her biri için en az 2 case (PASS + FAIL). Toplam: 39 → 47 → 51 case (S25 + S26 eklendi).

---

## Out of Scope (Sprint 10.0)

- ❌ Go NDK + FFI (Sprint 10.1)
- ❌ `api-test.opene2ee.com` POST /telemetry (Sprint 10.1)
- ❌ Real VPN service binding (Sprint 10.1) — **Sprint 10.0 boyunca VPN kelimesi bile YOK**
- ❌ iOS build (Sprint 10.2+)
- ❌ Web Dashboard (Sprint 10.3+)
- ❌ P2P matching (Sprint 10.2)
- ❌ Real telemetry (Sprint 10.1)
- ❌ Real görevler (Sprint 10.2)
- ❌ Skorlar tab'ı implementation (placeholder OK, Sprint 10.3+)

---

## Verification (Kabul Barı)

- ✅ `flutter build apk --debug` SUCCESS
- ✅ Tablet'te uygulama açılıyor, crash yok
- ✅ Bilgilendirme → "Anladım, Devam Et" → Home navigation
- ✅ Home'da 2 task kartı (RCS + WhatsApp) görünüyor, **GSM operatörü YOK**, **VPN ifadesi YOK**
- ✅ WhatsApp kartına tıkla → task detail → chat bubble + Gönder butonu
- ✅ Gönder butonu → `url_launcher` ile `whatsapp://send?text=...` deep link tetikler
- ✅ Bottom nav → Aktif Nöbet → 3 stat görünüyor (İzlenen Paket + Bağlı Gönüllü + Test Edilenler)
- ✅ Audit: S21-S26 PASS, self-test 51/51
- ✅ `grep -ri "vpn" mobile/lib/` boş döner (S25)

---

## ETA

- 1 task (1 Coder session) + 1 gate (1 Verifier session) = 2 cycles
- Tahmini süre: 2-3 saat
- Owner tablet test + UI feedback → Sprint 10.1 planı

---

## Cross-Sprint Dependency Map

```
Sprint 9.7.0 (DONE):
  - Fresh Flutter skeleton ✓
  - MainActivity + VpnService + Manifest + nsc ✓
  - Native build gate ✓
  - Audit 39 invariant ✓

Sprint 9.7.0 followups (DONE, push bekliyor):
  - Item 6: android-debug workflow fix ✓
  - Item 7: android-release workflow fix ✓
  - Item 8: nsc pin-set fix ✓
  - arch rewrite: ARCHITECTURE_DECISIONS.md new §5 ✓

Sprint 10.0 (BU — REVİZE):
  - pubspec.yaml: flutter_riverpod + go_router + fl_chart + url_launcher
  - 4 ekran: Bilgilendirme, Home (RCS+WhatsApp), WhatsApp Detail, Aktif Nöbet
  - State (Riverpod) + theme
  - url_launcher ile WhatsApp deep link
  - Audit S21-S26 (S25 no-VPN, S26 whatsapp://send)
  - Build + tablet test

Sprint 10.1 (sonraki):
  - Go + gopacket → Android NDK cross-compile
  - Flutter dart:ffi binding
  - OpenE2eeVpnService.kt FFI entegrasyonu (ama UI'da VPN ifadesi yerine sadece "Bağlantı" veya "İzleme" kullanılır)
  - api-test.opene2ee.com POST /telemetry
  - Real start/stop + telemetry gönderimi
```

---

**File path:** `C:\repos\e2ee-app-integration\docs\SPRINT-10-SCOPE.md`
**Pattern:** UI-first exploratory (Sprint 10.0, revize 10.07.2026 18:51)
**Push kararı:** §8 user/Owner onayı sonrası (`docs/SPRINT-10-CLOSURE.md` ile birlikte)
