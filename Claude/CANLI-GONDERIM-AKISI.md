# Canlı Gönderim Akışı — YSV Beyannameleri (2026/08)

Bu doküman, Ağustos 2026 YSV beyannamelerinin **PROD**'da SBM'ye gönderilmesi için adım adım
akıştır. Her adımda ne yapılacağı, beklenen sonuç ve beklenmeyen bir sonuçta ne yapılacağı
yazılıdır. Sonraki aylar için de aynı akış kullanılır; yalnızca dönem ve sayılar değişir.

Kaynak dosya: `SBM ve Allianz Dökümanları/sbm_tsb veri gönderme çalışması Ağustos 2026.xlsx`

---

## 0. Başlamadan önce bilinmesi gerekenler

> ⚠️ **SBM'de silme yoktur ve yuva kilidi geri alınamaz.** SBM her il-ilçe-yıl-ay için tek
> beyanname kabul eder (`RISK-HAVUZU-00004`). Bir yuvaya bir kez gönderilen dosya no o yuvaya
> kalıcı olarak bağlanır: iptal (tutarları 0 ile güncelleme) tutarları sıfırlar, yuvayı
> boşaltmaz. Yanlış dosya no ile gönderilen yuva bir daha yalnızca o dosya no ile
> güncellenebilir. Bu yüzden **§2 (doğrulama) ve §4 (pilot) atlanmaz.**

- PROD'a **hiçbir zaman test verisi** yüklenmez ve gönderilmez (`PENTEST…`, `test-gelistirici.xlsx`
  vb.). SC-TEST/SC-UAT'ta yaşanan mükerrer yuva sorununun tek sebebi buydu.
- PROD DB'de elle script çalıştırmak firma politikası gereği yasaktır; bir hata DB'den elle
  düzeltilemez. Doğrulama bu yüzden yüklemeden **önce** yapılır.
- Tüm istekler `X-User-Name` başlığıyla atılır (DB'deki `*_BY_USER` kolonlarına yazılır; yoksa
  `SYSTEM` yazılır). Toplu gönderimde kimlik başlıkları (`X-Requester-Id-*`) gönderilmez; token
  servisi şirket VKN'sini döner (SBM Entegrasyon Dokümanı §5.1: kullanıcı müdahalesi olmayan toplu
  işlemde kurum vergi numarası).

Aşağıdaki örneklerde:

```bash
BASE="https://<prod-ingress-host>/sbm-declaration-services/api/v1/declarations"
USER_HDR="X-User-Name: <sicil-veya-kullanici-adi>"
XLSX="sbm_tsb veri gönderme çalışması Ağustos 2026.xlsx"
```

---

## 1. Ön koşullar

Hepsi ✅ olmadan §2'ye geçilmez.

| # | Kontrol | Nasıl doğrulanır | Beklenen |
|---|---|---|---|
| 1.1 | Uygulama PROD'da ayakta | `GET <host>/sbm-declaration-services/actuator/health` | `{"status":"UP"}` |
| 1.2 | Doğru sürüm deploy edildi (`/upload/validate` var) | §2'deki çağrı 404 dönmemeli | 200 |
| 1.3 | Helm config de deploy edildi | Uygulama logunda açılışta hata yok; `common-configs` ConfigMap'inde `batch_size: 50` görünüyor | — |
| 1.4 | PROD DB tabloları ve yetkiler | `GET $BASE/processes?year=2026&month=8&size=1` | 200, `totalElements: 0` (Ağustos henüz yüklenmedi) |
| 1.5 | Token servisi PROD'da `ysv` client'ını tanıyor | Ops/token ekibi (Hüseyin Dağ / Ömer Faruk Ceylan) teyidi | — |
| 1.6 | SBM PROD entegrasyon kullanıcısı ve şifresi token servisine tanımlı | Token ekibi teyidi (test kullanıcısı PROD'da çalışmaz) | — |
| 1.7 | PROD çıkış IP'si SBM'de whitelist'te | SBM/ağ ekibi teyidi. Değilse SBM çağrısında `SEC-00004` gelir (token alınırken `USER-00033` → bize 503) | — |
| 1.8 | PROD pod'undan ESB'ye erişim | §4'teki pilot gönderim bunu fiilen doğrular | — |

> 1.5–1.7 kod ile doğrulanamaz; teyitleri yazılı (e-posta / Jira) alınmalıdır.

---

## 2. Excel'i yüklemeden doğrula

```bash
curl -s -X POST "$BASE/upload/validate" -H "$USER_HDR" -F "file=@$XLSX"
```

DB'ye yazmaz, SBM'ye gitmez. `upload` ile aynı kuralları çalıştırır.

**Beklenen (Ağustos 2026, boş PROD DB):**

```json
{ "result": true, "status": 200,
  "data": { "totalRows": 596, "inserted": 596, "updated": 0,
            "insertedFileNos": [ …298 dosya no… ], "updatedFileNos": [], "failed": 0, "errors": [] } }
```

| Sonuç | Ne yapılır |
|---|---|
| Beklenen gibi | §3'e geç |
| `failed > 0` | **Dur, yükleme yapma.** `errors[]` satır numarası, dosya no ve sebebi gösterir. Excel iş biriminde düzeltilir, bu adım tekrarlanır |
| `inserted` 596'dan az, `updated > 0` | PROD DB'de Ağustos'a ait kayıt var. Kimin, ne zaman yüklediği (`GET $BASE/processes?year=2026&month=8`) netleşmeden devam edilmez |
| 400 "birden fazla dönem" | Excel'de farklı ay/yıl satırı var; iş birimine dönülür |

Ek göz kontrolü: `insertedFileNos` listesinde test verisi (`PENTEST…` vb.) olmamalı.

---

## 3. Excel'i yükle

```bash
curl -s -X POST "$BASE/upload" -H "$USER_HDR" -F "file=@$XLSX"
```

**Beklenen:** §2 ile **birebir aynı** sayılar (`inserted: 596`, `failed: 0`).

| Sonuç | Ne yapılır |
|---|---|
| §2 ile aynı | §4'e geç |
| §2'den farklı | Arada başka biri yükleme yapmış olabilir. `GET $BASE/processes?year=2026&month=8` ile kontrol et, netleşmeden gönderme |
| 500 `ALZ-INTERNAL` | Dosyanın **hiçbir satırı yazılmamıştır** (yükleme tek transaction). Uygulama logunda hata aranır; düzeltmeden tekrar denenmez |

Kontrol: `GET $BASE/processes?status=NEW&year=2026&month=8&size=1` → `totalElements: 596`.

---

## 4. Pilot: iki beyanname tek tek gönder

Toplu gönderimden önce PROD'da uçtan uca zinciri (token → ESB → SBM → DB) iki beyannameyle
doğrula. İkisi de gerçek veridir; zaten gönderilecekleri için sonradan geri alınmaları gerekmez.

| Pilot | Dosya no | Neyi doğrular |
|---|---|---|
| A | `YSV2027935` | İstanbul (il 34), büyükşehir → `ilceKodu` **gönderilmez** |
| B | `YSV2027888` | Edirne (il 22) ilçe 1295, büyükşehir değil → `ilceKodu` gönderilir |

```bash
curl -s -i -X POST "$BASE/YSV2027935/send" -H "$USER_HDR"
curl -s -i -X POST "$BASE/YSV2027888/send" -H "$USER_HDR"
```

**Beklenen:** her biri `HTTP 201`, `{"result":true,"status":201,"data":{"ysvDosyaNo":"YSV2027935"}}`.

Ardından SBM'den geri oku:

```bash
curl -s "$BASE/YSV2027935" -H "$USER_HDR"
curl -s "$BASE/YSV2027888" -H "$USER_HDR"
```

**Beklenen:** `200`, `data` içinde il/ilçe, dönem ve iki menkul tipinin tutarları Excel ile aynı;
satırlar `COMPLETED` olur.

| Sonuç | Anlamı / Ne yapılır |
|---|---|
| 201 + sorgu 200 | Zincir çalışıyor → §5 |
| 503 `SEC-00001` | Token alınamadı → 1.5 / 1.6 / 1.7 (token servisi, PROD kullanıcısı; SBM token alırken IP'yi reddederse (`USER-00033`) de bu hata görünür — ayrıntı token servisi logunda) |
| 422 `SEC-00004` | SBM çıkış IP'sini tanımıyor → 1.7. Toplu gönderime **geçilmez** |
| 502 `CORE-00000` | ESB'ye ulaşılamadı ya da ESB'den SBM dışı cevap geldi → 1.8, ESB ekibi. Logdaki `Transaction-Id` ile izlenir |
| 422 `RISK-HAVUZU-00003` | Ay veri girişine kapalı. SBM ile iletişime geçilir; toplu gönderim anlamsızdır |
| 422 `RISK-HAVUZU-00004` | Yuva SBM PROD'da dolu. Mesajdaki dosya no **bizimkiyse** satır `SENT` olur (zaten SBM'de); başkasıysa **dur**, kimin gönderdiği araştırılır |
| 422 diğer `RISK-HAVUZU-*` / `CORE-*` | Veri hatası; §7'deki tabloya bak |

---

## 5. Toplu gönderim

```bash
curl -s -X POST "$BASE/send" -H "$USER_HDR" -H "Content-Type: application/json" \
  -d '{"year":2026,"month":8}'
```

- Yalnızca `NEW` / `ERROR` satırlar gider; pilotlar (`COMPLETED`) tekrar gönderilmez.
- Her beyanname ayrı SBM çağrısı ve ayrı DB transaction'ıdır: biri reddedilirse diğerleri
  etkilenmez.
- Süre: 296 beyanname için yaklaşık **2 dakika**.

**Beklenen:**

```json
{ "result": true, "status": 200,
  "data": { "totalGroups": 296, "successCount": 296, "failCount": 0, "results": [ … ] } }
```

> ⚠️ **İstemci zaman aşımına düşerse tekrar tetikleme.** Gateway / Swagger / curl cevabı
> beklemeyi bıraksa bile sunucu gönderime devam eder. Birkaç dakika bekle, sonra
> `GET $BASE/processes?status=NEW&year=2026&month=8&size=1` ile kalanı kontrol et. Yanlışlıkla
> iki kez tetiklenirse zarar oluşmaz: ikinci çağrı gönderilmiş beyannameleri `409
> ALZ-STATUS-CONFLICT` ile atlar.

| Sonuç | Ne yapılır |
|---|---|
| `failCount: 0` | §6'ya geç |
| `failCount > 0` | `results[]` içinde `result:false` olanların `error.reasons[].code` değerine göre §7. Hatalı beyannameler `ERROR` durumundadır ve düzeltildikten sonra aynı çağrıyla (`POST /send`) yeniden gönderilebilir |
| Aynı hata tüm beyannamelerde (ör. `SEC-*`, `CORE-00000`) | Altyapı sorunu; veri düzeltmesi yapılmaz, sebep giderilip `POST /send` tekrar çağrılır (yalnızca `NEW`/`ERROR` olanlar gider) |

---

## 6. Toplu sorgu ve son kontrol

SBM'deki kaydı geri okuyup doğrulananları `COMPLETED` yapar:

```bash
curl -s -X POST "$BASE/query" -H "$USER_HDR" -H "Content-Type: application/json" \
  -d '{"year":2026,"month":8}'
```

**Beklenen:** `successCount: 298`, `failCount: 0`.

Son durum:

```bash
curl -s "$BASE/processes?status=COMPLETED&year=2026&month=8&size=1" -H "$USER_HDR"   # totalElements: 596
curl -s "$BASE/processes?status=ERROR&year=2026&month=8&size=1" -H "$USER_HDR"       # totalElements: 0
curl -s "$BASE/processes?status=SENT&year=2026&month=8&size=1" -H "$USER_HDR"        # totalElements: 0
```

Mutabakat: Excel'deki toplam `odenecekVergi` = **4.487.523,39 TL** (596 satır; 3 satırda negatif
değer vardır — YSV2027909, YSV2027939, YSV2028065 — iptal edilen prim alınandan büyük olduğu
için beklenen durumdur).

---

## 7. Hata kodlarına göre aksiyon

Kod, cevaptaki `error.reasons[].code` alanındadır; satırın `ERROR_DETAILS` kolonuna da yazılır.

| Kod | Anlamı | Aksiyon |
|---|---|---|
| `RISK-HAVUZU-00003` | Ay veri girişine kapalı | SBM ile iletişim. Yeniden deneme anlamsız |
| `RISK-HAVUZU-00004` | Yuvada başka beyanname var | Mesajdaki dosya no bizimkiyse satır otomatik `SENT` olur (zaten SBM'de). Başkasıysa: kimin gönderdiği araştırılır; SBM'de silme olmadığı için o yuvaya ancak o dosya no ile PUT yapılabilir |
| `RISK-HAVUZU-00005` | Aynı menkul tipi iki kez | Excel'de aynı dosya no + menkul tipi tekrar ediyor. Normalde doğrulamada yakalanır |
| `RISK-HAVUZU-00006` / `00009` | İl / ilçe bulunamadı | Veri hatası; iş birimine. **Not:** bugünkü kodla bu satırın il/ilçesi Excel ile düzeltilemez |
| `RISK-HAVUZU-00007` | Büyükşehirde ilçe gönderilemez | Excel'de ilçe kodu 0 olmalı; iş birimine. Aynı not geçerli |
| `RISK-HAVUZU-00008` | Büyükşehir değilse ilçe gönderilmeli | Excel'de ilçe kodu eksik; iş birimine. Aynı not geçerli |
| `CORE-01000/01004/01008`, `CORE-00005/00006` | Alan zorunlu / aralık / uzunluk / format | Veri hatası; `field` alanı hangi alan olduğunu söyler |
| `SEC-00001` (503) | Token alınamadı | Token servisi erişimi / PROD kullanıcı tanımı |
| `SEC-00002` | Token geçersiz | Uygulama 1 kez yeni token ile otomatik dener; sürerse token ekibi |
| `SEC-00004` | IP doğrulanamadı | Whitelist (token alırken aynı sorun `USER-00033` olarak token servisinde görünür; bize 503 `SEC-00001` döner) |
| `SEC-00005..08` | Başlık hataları | Uygulama hatası; logla birlikte geliştiriciye |
| `CORE-00000` (502) | ESB / SBM beklenmeyen hata | `Transaction-Id` ile SBM'ye (Pusula) destek talebi |

**SBM destek talebi:** Her çağrının `Transaction-Id`'si `ALZ_SBM_DECL_LOG.LOG_MESSAGE`'da ve
uygulama logundadır. SBM her destek talebinde bu değeri ister.

---

## 8. Gönderim sonrası düzeltmeler

| İhtiyaç | Yol |
|---|---|
| Tutar / son ödeme tarihi değişti (birden çok beyanname) | Düzeltilmiş Excel → önce `/upload/validate`, sonra `/upload`. Cevaptaki `updatedFileNos` → `PUT $BASE/update` gövdesinde `{"ysvDosyaNoList":[…]}` |
| Tek beyannamenin tutarı değişti | `PUT $BASE/{ysvDosyaNo}` — DB'yi günceller ve aynı çağrıda SBM'ye PUT eder |
| Beyanname geri çekilmeli | `POST $BASE/{ysvDosyaNo}/cancel` — SBM'de tutarlar 0 olur (silme yoktur, yuva bu dosya no'da kalır) |
| İl / ilçe yanlış | SBM'ye **gitmişse** değiştirilemez: iptal + yeni dosya no ile yeni beyanname. Gitmemişse (`ERROR`) bugün düzeltme yolu yoktur — geliştiriciyle konuşulur |

---

## 9. Yapılmaması gerekenler

- ❌ `/upload/validate` sonucu görülmeden `/upload`.
- ❌ Pilot (§4) başarılı olmadan toplu gönderim.
- ❌ Filtresiz toplu çağrı (`{}` gövdesi) — **tüm dönemlerin** uygun kayıtlarını işler. Her zaman
  `{"year":2026,"month":8}` ya da `ysvDosyaNoList` ile çağrılır. Özellikle `POST /cancel` için.
- ❌ Toplu gönderim sürerken tekrar tetikleme (zaman aşımı olsa bile önce durumu kontrol et).
- ❌ PROD DB'de elle `UPDATE` / `DELETE`.
- ❌ PROD'da test dosyası (`Pen Test/*.xlsx`) yükleme.
