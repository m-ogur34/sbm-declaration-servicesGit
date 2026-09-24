# PEN Test Rehberi — sbm-declaration-services

Yangın Sigorta Vergisi (YSV) beyannamelerini Excel'den alıp ESB üzerinden SBM'ye gönderen REST
servisi. Bu rehber PEN ekibinin testi kısa adımlarla yürütmesi içindir.

## 1. Paket

| Dosya | İçerik |
|---|---|
| `bruno-pentest-2014-02-basit.json` | **Basit senaryo** — 5 istek: yükle → gönder → sorgula → güncelle → tekrar sorgula |
| `bruno-pentest-2014-02.json` | Kapsamlı koleksiyon — 7 klasör, 38 istek (girdi doğrulama, başlık, bilgi ifşası) |
| `pentest-2014-02-yukleme.xlsx` | Geçerli test verisi: 12 satır / 6 beyanname (`PENTEST1402-01..06`), dönem **2014/02** |
| `pentest-2014-02-hatali-satirlar.xlsx` | Her satırda bir hata türü (boş alan, `&`/`=`, formül, `<script>`, 37 karakter, tekrar…) |
| `pentest-2014-iki-donem.xlsx` | İki dönem içeren dosya — tümü reddedilmeli |

## 2. Ortam

| Ortam | Adres |
|---|---|
| SC-UAT (önerilen) | `https://int-sc-uat-elementer.allianz.com.tr/sbm-declaration-services` |
| SC-TEST | `https://int-sc-test-elementer.allianz.com.tr/sbm-declaration-services` |

API kökü: `/api/v1/declarations`. **PROD'da test yapılmaz** (koleksiyonda PROD ortamı yoktur).

## 3. Başlamadan önce

- ⚠️ Gönder / güncelle / iptal uçları **gerçekten SBM TEST'e veri gönderir.** Yalnız bu paketteki
  `PENTEST1402-…` verisini kullanın. Fuzz / tekrar denemelerini tekli uçlarla ya da
  `ysvDosyaNoList` ile dar bir kümede yapın.
- Yük / DoS testi yapılacaksa önce geliştiriciyle koordine edin (her istek SBM'ye ve token
  servisine gider).
- Kimlik doğrulama **uygulamada yoktur**; gateway'e bırakılmıştır. `X-User-Name` ve
  `X-Requester-Id-Type/No` başlıklarının gateway tarafından konması beklenir (bkz. §5).

## 4. Adımlar

### 4.1 Basit senaryo (`bruno-pentest-2014-02-basit.json`)

Bruno'ya aktar, ortam `sc-uat`, 1. istekte **Body → file** ile `pentest-2014-02-yukleme.xlsx`'i seç
ve istekleri sırayla çalıştır:

| # | İstek | Beklenen |
|---|---|---|
| 1 | Excel'i yükle | 200, `inserted: 12`, `failed: 0` |
| 2 | Verileri SBM'ye gönder (2014/02) | 200, `successCount: 6`, `failCount: 0` |
| 3 | Verileri SBM'den sorgula | 200, `successCount: 6` |
| 4 | `PENTEST1402-01`'i güncelle (MENKUL alınan prim 660.000) | 200, `data: true` |
| 5 | `PENTEST1402-01`'i tekrar sorgula | 200, MENKUL `alinanPrimTutari` 660000 |

Tekrar çalıştırılabilir: 1. adım `inserted: 0`, 2. adım `totalGroups: 0` döner (zaten gönderilmiş).

### 4.2 Kapsamlı koleksiyon (`bruno-pentest-2014-02.json`)

1. Bruno → **Import Collection → Bruno Collection** → `bruno-pentest-2014-02.json`; ortam `sc-uat`.
2. Excel gönderen isteklerde **Body → file** alanından ilgili dosyayı seçin.
3. **Klasör 0 — Hazırlık:** sağlık kontrolü, test verisini kontrol et, yükle, gönder.
4. **Klasör 1 — Normal akış:** doğru cevapların biçimini görün (referans).
5. **Klasör 2 — Dosya yükleme:** hatalı satırlar, iki dönem, uzantı, sahte `.xlsx`, dosyasız istek.
6. **Klasör 3 — API girdileri:** boş filtre, eksik dönem, ay 13, bozuk JSON, URL karakterli dosya
   no, SQL enjeksiyonu (`sort`), yanlış metot / içerik tipi.
7. **Klasör 4 — Başlık ve kimlik:** geçersiz kimlik tipi/numarası, uzun kullanıcı adı, başlık
   sahteciliği (gateway davranışı).
8. **Klasör 5 — İş kuralları:** mükerrer gönderim (409), olmayan kayıt (404).
9. **Klasör 6 — Bilgi ifşası:** kapalı actuator uçları, Swagger.

## 5. Beklenen genel davranış

- Tüm cevaplar SBM zarfındadır: başarı `{result:true, status, data}`, hata
  `{result:false, status, error:{timestamp, reasons:[{field, code, message}]}}`.
- Hiçbir girdi **500** üretmemeli; cevapta stack trace, sınıf adı, iç adres, SQL ya da kütüphane
  mesajı olmamalı. Beklenmeyen hata: `500 ALZ-INTERNAL "Beklenmeyen bir hata oluştu."`.
- Hata kodları: `400 ALZ-VALIDATION` (alan/başlık), `400/405/413/415 ALZ-REQUEST`,
  `404 ALZ-NOT-FOUND`, `409 ALZ-STATUS-CONFLICT`, `422` SBM kodu, `502 CORE-00000`,
  `503 SEC-00001`.
- Excel: satır hataları dosyanın geri kalanını engellemez (`errors[]`); dosya diske yazılmaz,
  adı yalnız DB'de bir kolona kaydedilir. Formül hücreleri çalıştırılmaz.
- Loglarda token, `Authorization` ve TCKN/YKN maskelidir.

## 6. Bilinen ve kabul edilmiş durumlar

Raporda "bilinen" olarak işaretlenebilir; kararları geliştirici ekiptedir:

| Durum | Açıklama |
|---|---|
| Uygulamada kimlik doğrulama / rate limit yok | Gateway katmanında olması bekleniyor. Uç doğrudan erişilebiliyorsa **bulgu** |
| `X-User-Name`, `X-Requester-Id-*` istemciden geliyor | Gateway başlıkları silip kendisi koymalı. Koymuyorsa **bulgu** |
| `/actuator/prometheus` ve `/actuator/health` açık | İzleme için. Dışarıdan erişim ingress'te kısıtlanmalı |
| Swagger test ortamlarında açık | PROD'da kapalı (404) |
| Güvenlik başlıkları (`X-Content-Type-Options`, `X-Frame-Options`) | Gateway'e bırakıldı |
| Dosya boyutu sınırı | k8s'te Spring varsayılanı (1MB) |

## 7. Test sonrası

Test verisi (`PENTEST1402-…`) DB'den geliştirici tarafından silinir
(`Lokal Test/temizle-test-verisi.sql`). SBM TEST'teki kayıtlar silinemez; geçmiş bir dönemde
(2014/02) oldukları için gerçek aylara etki etmez.
