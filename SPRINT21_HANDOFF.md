# Sprint 21 — Handoff Dokümanı

**Tarih:** 01 Ağustos 2026  
**Durum:** ✅ TAMAMLANDI — Cihaz testi bekleniyor  
**Branch:** `e2ee-ap-v2` (worktree: `C:\repos\e2ee-app-pr-s21item1`)

---

## 1. Ne Oldu?

Sprint 21, 7 ardışık başarısız VPN sprintinden (Sprint 14–20) sonra **tamamen sıfırdan başladı**. Temel karar: kendi yazdığımız VPN servislerini bir kenara bırakıp, wirebare'ın kendi `SimpleWireBareProxyService`'ini kullanmak. wirebare kendi APK'sında çalıştığı için, bizim hata da muhtemelen kendi VPN servis implementasyonumuzdaydı.

---

## 2. APK

| Tip | Dosya | SHA-256 |
|-----|-------|---------|
| Debug | `build/app/outputs/apk/debug/app-debug.apk` | `005AE315253D311D52212CFF62E107A38FFCA651310B209C8F3C00F61691CD8F` |
| Release | `build/app/outputs/apk/release/app-release.apk` | `568E0F48AFE4AAEFC73A322FB866325F3C1A7D0CA98D5C8958D0A96A675BDED2` |

Boyut: Debug ~153 MB, Release ~46 MB (R8 shrink + minify açık).

---

## 3. Mimari

```
┌─────────────────────────────────────────────┐
│  lib/main.dart (Flutter)                   │
│  └─ MaterialApp → MyApp → _buildBody()     │
│       → "VPN AÇ" / "VPN KAPAT" butonu     │
│       → MethodChannel: startVpn / stopVpn │
└──────────────────┬────────────────────────┘
                   │ platform: "com.opene2ee.e2ee_ap_v2/vpn"
┌──────────────────▼────────────────────────┐
│  MainActivity.kt (Kotlin)                 │
│  └─ MethodChannelHandler                  │
│       → simpleWireBareProxyService.start()│
│       → simpleWireBareProxyService.stop()  │
└──────────────────┬────────────────────────┘
                   │
┌──────────────────▼────────────────────────┐
│  SimpleWireBareProxyService               │
│  (wirebare-kernel/service/)               │
│  ┌─ TunBuffer / PacketDispatcher          │
│  ├─ WireGuard connection (UDP)           │
│  └─ DNS interception + forward          │
└───────────────────────────────────────────┘
```

**Önemli:** VPN mantığı tamamen wirebare-kernel'de. Biz sadece Flutter UI + MethodChannel köprüsü yazdık.

---

## 4. Kritik Düzeltme (Gate 7 — Mavis buldu)

Sprint 21 Coder sed-rename'i (`top.sankokomi.wirebare` → `com.opene2ee.e2ee_ap_v2.vpn`) package declaration'larda `.kernel.` segmentini kaçırdı. Sonuç:

- **DEX'te class FQN:** `vpn.kernel.service.SimpleWireBareProxyService`
- **Manifest'te service FQN:** `vpn.service.SimpleWireBareProxyService`

Bu ClassNotFoundException'a sebep olurdu. Mavis manuel olarak tüm 87 .kt dosyasında `vpn.kernel.` → `vpn.` düzeltmesini yaptı. Build tekrar başarılı.

---

## 5. Dosya Yapısı (özet)

```
e2ee-ap-v2/
├── lib/main.dart                          # Flutter minimal UI (on/off button)
├── android/app/src/main/
│   ├── kotlin/com/opene2ee/e2ee_ap_v2/
│   │   ├── MainActivity.kt                # MethodChannel bridge
│   │   └── vpn/                           # wirebare-kernel (87 dosya, tam kopya)
│   │       ├── service/SimpleWireBareProxyService.kt
│   │       ├── TunBuffer.kt
│   │       ├── WireBareConfig.kt
│   │       └── ... (83 dosya daha)
│   └── AndroidManifest.xml                # BIND_VPN_SERVICE + FOREGROUND_SERVICE_DATA_SYNC
├── android/app/build.gradle.kts           # BouncyCastle, Brotli, OkHttp, kotlinx
├── android/gradle/libs.versions.toml      # wirebare'dan alınan 24 version, 27 lib
└── android/app/proguard-rules.pro         # R8 keep rules
```

---

## 6. Sonraki Adımlar (Ali'nin Yapacakları)

### A) main branch'e merge

```powershell
cd C:\repos\e2ee-app-integration
git fetch --all
git merge e2ee-ap-v2
git push origin main
```

> ⚠️ `alibildir-sesasis` push'u 403 döner. Ali'nin kendi SSH key'i ile push etmesi gerekiyor.

### B) Cihaz testi

```powershell
# Debug APK'yı kur (cihazı USB ile bağla)
adb install -r C:\repos\e2ee-app-pr-s21item1\build\app\outputs\apk\debug\app-debug.apk

# Uygulamayı aç, "VPN AÇ" butonuna bas
# İzinleri onayla

# Logcat'te ClassNotFoundException veya DNS timeout kontrol et
adb logcat -d | Select-String -Pattern "SimpleWireBare|PacketDispatcher|ClassNotFoundException|DNS|wirebare"
```

**Beklenen sonuç (başarılı test):**
- `ClassNotFoundException` YOK
- `PacketDispatcher` veya `WireBare` logları GÖRÜNÜYOR
- VPN bağlantısı aktif

**Beklenmeyen sonuç (hâlâ hata):**
- `ClassNotFoundException` VAR → Gate 7 düzeltmesi manifest ve DEX'te uyuşmuyor
- DNS timeout VAR → wirebare runtime'ında sorun; logcat'i paylaş

---

## 7. Sprint 22 İçin Notlar

Test başarılı olursa, Sprint 22 Flutter UI genişletmesi olacak:
- DNS check (cloudflare.com veya Google'a ping)
- Bağlantı durumu göstergesi (connected/disconnected)
- Basit log viewer (son 50 satır)

Test hâlâ başarısız olursa, bir sonraki tanı enstrümanı wirebare'ın kendi `SimpleWireBareProxyService` test APK'sını karşılaştırmak olacak.

---

## 8. Öğrenilen Dersler (Memory'ye Eklenecek)

- **wirebare'ın kendi VPN servisi referans alınmalı** — kendi yazdığımız OpenE2eeVpnService Sprint 14–20 arasında 7 kez başarısız oldu
- **Sed rename sonrası package declaration kontrolü şart** — `.kernel.` segmenti kaçırıldı, Gate 7'de fark edildi
- **Clean reset her zaman daha hızlı** — Sprint 20 Scenari A+ 21 dosya bıraktı, Sprint 21 sıfırdan 2 günde tamamlandı

---

**Sorun varsa:** `adb logcat -d` çıktısını paylaş — Mavis burada.
