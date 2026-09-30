# PEN Test Rehberi — sbm-declaration-services

Yangın Sigorta Vergisi (YSV) beyannamelerini Excel'den alıp ESB üzerinden SBM'ye gönderen REST
servisi. Bu rehber PEN ekibinin testi kısa adımlarla yürütmesi içindir.

## 1. Paket

| Dosya | İçerik |
|---|---|
| `bruno-pentest-2010.json` | **Tekrar testi (öncelikli)** — 7 klasör, 39 istek: normal akış (2010/01–02), API anahtarı (2.2), girdi yansıması (2.1), sunucu hata sayfası (2.4), Swagger/actuator (2.3), iş kuralları |
| `pentest-2010-01-yukleme.xlsx` / `pentest-2010-02-yukleme.xlsx` | Tekrar testi verisi: her biri 12 satır / 6 beyanname (`PENTEST1001-..`, `PENTEST1002-..`) |
| `pentest-2010-01-guncelleme.xlsx` | 2010/01 güncellemesi: `-01`, `-03` tutarları değişmiş, `-07` yeni |
| `bruno-pentest-2014-02-basit.json` | **Basit senaryo** — 5 istek: yükle → gönder → sorgula → güncelle → tekrar sorgula |
| `bruno-pentest-2014-02.json` | Kapsamlı koleksiyon — 7 klasör, 38 istek (girdi doğrulama, başlık, bilgi ifşası) |
| `pentest-2014-02-yukleme.xlsx` | Geçerli test verisi: 12 satır / 6 beyanname (`PENTEST1402-01..06`), dönem **2014/02** |
| `pentest-2014-02-hatali-satirlar.xlsx` | Her satırda bir hata türü (boş alan, `&`/`=`, formül, `<script>`, 37 karakter, tekrar…) |
| `pentest-2014-iki-donem.xlsx` | İki dönem içeren dosya — tümü reddedilmeli |
| `pentest-2012-01-yukleme.xlsx` / `pentest-2012-02-yukleme.xlsx` | Ek geçerli veri: her biri 12 satır / 6 beyanname (`PENTEST1201-..`, `PENTEST1202-..`), dönem 2012/01 ve 2012/02 — ayrı ayrı yüklenir, gönderimde `{"year":2012,"month":1}` / `2` |
| `pentest-2011-01..04-yukleme.xlsx` | Tekrar test verisi: her biri 12 satır / 6 beyanname (`PENTEST1101-..` … `PENTEST1104-..`), dönem 2011/01–2011/04 — ayrı ayrı yüklenir |
| `pentest-2011-05-girdi-yansimasi.xlsx` | Hücrelerde `<script>`, `<img onerror>`, `<svg onload>`, formül, `../` yolu. Beklenen: 2 geçerli satır (`PENTEST1105-01`) + 8 hata; hata mesajlarında gönderilen değer **yer almaz** |

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
- Tüm `/api/v1` uçları **`X-ApiKey`** başlığı ister. UAT anahtarı PEN ekibine ayrı kanaldan
  iletilir; koleksiyonlarda `API_KEY_BURAYA` yazan değer, import'tan **önce** JSON dosyasında
  metin editörüyle (Tümünü değiştir) anahtarla değiştirilir.

## 4. Adımlar

### 4.1 Basit senaryo (`bruno-pentest-2014-02-basit.json`)

Koleksiyon hazırdır: adres (SC-UAT), kullanıcı (`allianz`) ve dönem (2014/02) isteklerin içinde
yazılıdır; **ortam seçmeye gerek yoktur.**

1. `bruno-pentest-2014-02-basit.json` içindeki `API_KEY_BURAYA` metnini API anahtarıyla değiştirin,
   sonra Bruno → **Import Collection → Bruno Collection**. Bruno koleksiyonu kaydetmek için bir
   klasör sorar.
2. `pentest-2014-02-yukleme.xlsx`'i **o klasöre** kopyalayın (1. istek dosyayı oradan otomatik alır;
   bulamazsa Body → file alanından seçin).
3. İstekleri 1'den 5'e sırayla çalıştırın:

| # | İstek | Beklenen |
|---|---|---|
| 1 | Excel'i yükle | 200, `inserted: 12`, `failed: 0` |
| 2 | Verileri SBM'ye gönder (2014/02) | 200, `successCount: 6`, `failCount: 0` |
| 3 | Verileri SBM'den sorgula | 200, `successCount: 6` |
| 4 | `PENTEST1402-01`'i güncelle (MENKUL alınan prim 660.000) | 200, `data: true` |
| 5 | `PENTEST1402-01`'i tekrar sorgula | 200, MENKUL `alinanPrimTutari` 660000 |

Tekrar çalıştırılabilir: 1. adım `inserted: 0`, 2. adım `totalGroups: 0` döner (zaten gönderilmiş).

### 4.2 Kapsamlı koleksiyon (`bruno-pentest-2014-02.json`)

1. `API_KEY_BURAYA` metnini anahtarla değiştirip Bruno → **Import Collection → Bruno Collection** →
   `bruno-pentest-2014-02.json` (ortam seçmeye gerek yok; adres, kullanıcı ve dönem isteklerde yazılı).
2. Üç Excel'i Bruno'nun koleksiyonu kaydettiği klasöre kopyalayın; `.xlsx` olmayan / sahte dosya
   gerektiren isteklerde (2.3, 2.4) dosyayı Body → file alanından seçin.
3. **Klasör 0 — Hazırlık:** sağlık kontrolü, test verisini kontrol et, yükle, gönder.
4. **Klasör 1 — Normal akış:** doğru cevapların biçimini görün (referans).
5. **Klasör 2 — Dosya yükleme:** hatalı satırlar, iki dönem, uzantı, sahte `.xlsx`, dosyasız istek.
6. **Klasör 3 — API girdileri:** boş filtre, eksik dönem, ay 13, bozuk JSON, URL karakterli dosya
   no, SQL enjeksiyonu (`sort`), yanlış metot / içerik tipi.
7. **Klasör 4 — Başlık ve kimlik:** geçersiz kimlik tipi/numarası, uzun kullanıcı adı, başlık
   sahteciliği, API anahtarı olmadan / yanlış anahtarla istek (401).
8. **Klasör 5 — İş kuralları:** mükerrer gönderim (409), olmayan kayıt (404).
9. **Klasör 6 — Bilgi ifşası:** kapalı actuator uçları, kapalı Swagger (404).

## 5. Beklenen genel davranış

- Tüm cevaplar SBM zarfındadır: başarı `{result:true, status, data}`, hata
  `{result:false, status, error:{timestamp, reasons:[{field, code, message}]}}`.
- Hiçbir girdi **500** üretmemeli; cevapta stack trace, sınıf adı, iç adres, SQL ya da kütüphane
  mesajı olmamalı. Beklenmeyen hata: `500 ALZ-INTERNAL "Beklenmeyen bir hata oluştu."`.
- Hata mesajları gönderilen değeri (dosya adı, hücre içeriği) geri yazmaz.
- Tomcat seviyesinde reddedilen istekler de (ör. URL'de `%2f`) HTML değil, aynı JSON biçimde döner.
- Hata kodları: `401 ALZ-UNAUTHORIZED` (API anahtarı), `400 ALZ-VALIDATION` (alan/başlık), `400/405/413/415 ALZ-REQUEST`,
  `404 ALZ-NOT-FOUND`, `409 ALZ-STATUS-CONFLICT`, `422` SBM kodu, `502 CORE-00000`,
  `503 SEC-00001`.
- Excel: satır hataları dosyanın geri kalanını engellemez (`errors[]`); dosya diske yazılmaz,
  adı yalnız DB'de bir kolona kaydedilir. Formül hücreleri çalıştırılmaz.
- Loglarda token, `Authorization` ve TCKN/YKN maskelidir.

## 6. Bilinen ve kabul edilmiş durumlar

Raporda "bilinen" olarak işaretlenebilir; kararları geliştirici ekiptedir:

| Durum | Açıklama |
|---|---|
| Rate limit uygulamada yok | Gateway / altyapı katmanına bırakıldı |
| `X-User-Name`, `X-Requester-Id-*` istemciden geliyor | Yalnız geçerli API anahtarını taşıyan çağıran (UI backend) koyar |
| `/actuator/prometheus` ve `/actuator/health` açık | İzleme için. Dışarıdan erişim ingress'te kısıtlanmalı |
| Güvenlik başlıkları (`X-Content-Type-Options`, `X-Frame-Options`) | Gateway'e bırakıldı |
| Dosya boyutu sınırı | k8s'te Spring varsayılanı (1MB) |

## 7. Test sonrası

Test verisi (`PENTEST1402-…`, `PENTEST12…`, `PENTEST11…`, `PENTEST10…`) DB'den geliştirici tarafından silinir
(`Lokal Test/temizle-test-verisi.sql`). SBM TEST'teki kayıtlar silinemez; geçmiş bir dönemde
(2014/02) oldukları için gerçek aylara etki etmez.
