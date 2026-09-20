# YSV → SBM Entegrasyonu — Çalışma Prensibi

> Bu doküman `sbm-declaration-services` reposunun uçtan uca çalışma prensibini,
> alan sözleşmelerini ve kesinleşmiş kararları içerir. Kaynak: SBM REST dökümanları
> (`ysv-beyanname_gonder-guncelle.pdf`, `ysv-beyanname_sorgu.pdf`), ESB test
> konsolundan alınan **başarılı** istek/yanıt ekran görüntüleri, `alz-token-management`
> Bruno koleksiyonu ve `YSV-OPUS.xlsx`.
>
> **Çelişki kuralı:** Bu doküman ile `CLAUDE.md` çelişirse **bu doküman** geçerlidir
> (bkz. §10). Yeni bir çelişki görülürse kod değiştirmeden önce sorulur.

---

## 1. Proje ve kapsam

Yangın Sigorta Vergisi (YSV) beyanname verilerinin SBM'ye (Sigorta Bilgi ve Gözetim
Merkezi) **REST + ESB** üzerinden gönderilmesi, güncellenmesi ve sorgulanması.
Allianz Sigorta içi proje. Allianz'ın SBM'deki şirket kodu: **`045`**.

Üç repoluk teslimatın 1. adımı:

| Repo | İçerik | Durum |
|---|---|---|
| `sbm-declaration-services` | SBM'ye veri gönderen servis katmanı | **bu repo — aktif** |
| `sbm-declaration-ui-backend` | UI için backend | sonraki adım |
| `sbm-declaration-ui` | Angular arayüz | sonraki adım |

Teknoloji: Spring Boot 3.5.x / Java 21, Spring `RestClient` (RestTemplate/WebClient
kullanılmaz), Oracle DB, düz Maven (`mvn`, wrapper yok). Paket kökü
`tr.com.allianz.ysv.services`.

---

## 2. Uçtan uca akış (4 aşama)

```
[1] Excel (OPUS'tan türetilmiş YSV verisi)
      │  Swagger/REST'ten "yükle" → doğrula → satır satır insert
      ▼
    CUSTOMER.ALZ_SBM_DECL_PROCESS   (STATUS = NEW)
      │
[2] gönder / güncelle / sorgu tetiklenir
      │  her çağrıda alz-token-management'tan TAZE token alınır
      ▼
[3] SBM REST sözleşmesine uygun JSON body hazırlanır
      │  header: Authorization: Bearer <token>, Requester-ID-Type, Requester-ID-No
      ▼
[4] istek ESB'ye (http://esb.allianz.com.tr:12000) atılır
      │  ESB ortam bazlı olarak ilgili SBM ortamına yönlendirir
      ▼
    SBM  →  { "result": bool, "data": <...>, "status": int }
      │
    sonuç ALZ_SBM_DECL_PROCESS.STATUS + ALZ_SBM_DECL_LOG'a yazılır
```

Prod DB'de manuel script çalıştırmak **firma politikası gereği yasak** olduğundan,
1. aşamadaki veri girişi de uygulama üzerinden (Excel yükleme servisi) yapılır.
Silme işlemi yoktur; silme gerekirse tutarlar **0** olarak güncellenir.

---

## 3. AŞAMA 1 — Excel yükleme ve DB'ye insert

### 3.1 Amaç

Kullanıcı, o aya ait YSV beyanname Excel'ini Swagger üzerinden yükler. Servis dosyayı
okur, her satırı doğrular, geçerli satırları `CUSTOMER.ALZ_SBM_DECL_PROCESS` tablosuna
`STATUS = NEW` ile insert eder, hatalı satırları bir rapor olarak döner.

### 3.2 Excel formatı (`YSV-OPUS.xlsx`)

- İlk sheet okunur (ada göre değil; dosyadaki ad "Sheet2" olabilir).
- **Satır 1 = başlık**, veri satır 2'den başlar.
- 13 kolon, sırası:

| # | Excel başlığı | Tip (Excel'de) | Notlar |
|---|---|---|---|
| A | `ay` | sayı | 1–12 |
| B | `ilKodu` | sayı | 1–81 |
| C | `ilceKodu` | sayı | **`0` = büyükşehir (il seviyesi)** — SBM'ye gönderilmez |
| D | `sigortaSirketKodu` | sayı | OPUS iç kodu (`2320`) — **kullanılmaz**, DB'ye `045` yazılır |
| E | `sonOdemeTarihi` | **Excel seri tarih** | `46042` → `2026-01-20`. `LocalDate.of(1899,12,30).plusDays(seri)` |
| F | `yil` | sayı | YYYY |
| G | `ysvDosyaNo` | metin | ör. `YSV202513491`, max 36 |
| H | `alinanPrimTutari` | ondalık | float gürültüsü var → `BigDecimal`, scale 2, HALF_UP |
| I | `iptalPrimTutari` | ondalık | scale 2 |
| J | `menkulTipi` | **sayı `1`/`2`** (veya metin) | 1 → `MENKUL`, 2 → `GAYRIMENKUL`. Parser **savunmacı**: hem `1`/`2` hem `MENKUL`/`GAYRIMENKUL` (trim + büyük/küçük harf duyarsız) kabul edilir. İş biriminden Excel'i doğrudan `MENKUL`/`GAYRIMENKUL` metniyle vermesi **istenecek** (self-documenting, SBM/DB ile birebir); yine de sayısal gelirse dönüştürülür. DB'de CHECK `IN ('MENKUL','GAYRIMENKUL')` |
| K | `odenecekVergi` | ondalık | scale 2 |
| L | `vergiOrani` | sayı | ör. 10 |
| M | `vergiPrimTutari` | ondalık | scale 2 |

- **`gecmisAyIadeTutari` kolonu bu Excel'de YOK.** SBM'de sonradan eklenmiş,
  zorunlu olmayan bir alan. Excel'de yoksa DB'ye `NULL` yazılır (0 değil).
- Tarih hücresi bazı dosyalarda gerçek "tarih formatlı" gelebilir → hem seri sayı,
  hem `yyyy-MM-dd`, hem `dd.MM.yyyy` metni kabul edilecek şekilde tolere edilir.

### 3.3 Excel → DB → SBM eşlemesi

| Excel | `ALZ_SBM_DECL_PROCESS` kolonu | SBM alanı |
|---|---|---|
| `ay` | `DECLARATION_MONTH` | `ay` |
| `ilKodu` | `CITY_CODE` | `ilKodu` |
| `ilceKodu` (0 → sakla) | `DISTRICT_CODE` | `ilceKodu` (0/null → gönderilmez) |
| — (sabit) | `COMPANY_CODE` = `045` | `sigortaSirketKodu` |
| `sonOdemeTarihi` | `PAYMENT_DATE` | `sonOdemeTarihi` (`yyyy-MM-dd`) |
| `yil` | `DECLARATION_YEAR` | `yil` |
| `ysvDosyaNo` | `SBM_FILE_NO` | `ysvDosyaNo` |
| `alinanPrimTutari` | `RECEIVED_PREMIUM_AMOUNT` | `alinanPrimTutari` |
| `iptalPrimTutari` | `CANCELLED_PREMIUM_AMOUNT` | `iptalPrimTutari` |
| `menkulTipi` (1/2 → metin) | `MOVABLE_TYPE` (`MENKUL`/`GAYRIMENKUL`) | `menkulTipi` |
| `odenecekVergi` | `TAX_AMOUNT` | `odenecekVergi` |
| `vergiOrani` | `TAX_RATIO` | `vergiOrani` |
| `vergiPrimTutari` | `TAX_PREMIUM_AMOUNT` | `vergiPrimTutari` |
| (Excel'de yok) | `PREV_MONTH_REFUND_AMOUNT` | `gecmisAyIadeTutari` |
| dosya adı | `SOURCE_FILE_NAME` | — |
| yükleyen kullanıcı | `CREATED_BY_USER` | — |
| sabit | `STATUS` = `NEW`, `DATE_CREATED` = now | — |

### 3.4 Endpoint tasarımı

- `POST /api/v1/declarations/upload` — `multipart/form-data`, tek `.xlsx` dosyası.
- Kullanıcı adı: diğer uçlarla tutarlı — `X-User-Name` header'ından alınır
  (iç gateway doldurur), yoksa `SYSTEM`. → `CREATED_BY_USER`.
- Yanıt: yazılan satır sayısı + hata listesi
  (`{ satirNo, ysvDosyaNo, hataKodu, mesaj }`).

### 3.5 Doğrulama ve hata kuralları (kesinleşmiş)

- **Tek dosya = tek ay.** Dosyadaki tüm satırların `yil`+`ay`'ı aynı olmalı; değilse
  dosya reddedilir.
- **Kısmi kabul:** geçerli satırlar yazılır, hatalı satırlar raporlanır (tüm dosya
  reddedilmez — sadece "tek ay" ihlalinde reddedilir).
- **Mükerrer `ysvDosyaNo`:** aynı `ysvDosyaNo` DB'de zaten varsa o satır **hata**
  verir (insert edilmez, güncelleme yapılmaz).
- Zorunlu alan boşsa, tip dönüşümü başarısızsa, `menkulTipi ∉ {1,2}` ise → satır hatası.
- `ilceKodu` boş gelmez; `0` geçerlidir (büyükşehir anlamına gelir).

### 3.6 Büyükşehir mantığı

İş birimi Excel'i büyükşehir/ilçe kırılımına göre zaten düzenleyerek veriyor. Uygulamada
**30 il hardcode listesi ve bloklayıcı validasyon YOK**. `ilceKodu`:

- `null` veya `0` → SBM payload'una **hiç konmaz** (`@JsonInclude(NON_NULL)`).
- Diğer her değer → DB'deki haliyle gönderilir.

Yanlış il/ilçe kombinasyonunu SBM kendi kodlarıyla bildirir
(`RISK-HAVUZU-00007` büyükşehirde ilçe gönderilemez, `RISK-HAVUZU-00008` büyükşehir
değilse ilçe gönderilmelidir) ve bu mesaj `ERROR_DETAILS`'e yazılır.

---

## 4. AŞAMA 2 — Token (`alz-token-management`)

YSV'nin kendi token servisi **yoktur ve olmayacaktır**. Merkezî `alz-token-management`
kullanılır. **Cache yoktur** — her gönder/güncelle/sorgu çağrısında (ve her retry
denemesinde) yeni token alınır. Cache zaten token servisinde.

### 4.1 İstek

```
POST  https://<ortam-auth-host>/alz-token-management/api/v1/tokens/sbm-token-generate
Content-Type: application/json

{
  "clientName":    "ysv",
  "transactionId": "<her istekte yeni UUID>",
  "functionName":  "test",
  "userName":      "WDA2422_16178",
  "companyCode":   "045"
}
```

- **Tüm parametreler ortam bazlıdır**, ortak/paylaşılan default yazılmaz:
  `base-url`, `path`, `client-name`, `user-name`, `company-code`. Her biri
  `helm/chart/configs/application-<ortam>.yml` içinde ayrı tanımlanır. Değeri
  bilinmeyen ortam boş bırakılır; `TokenManagementProperties` `@NotBlank` olduğundan
  uygulama o profil ile **başlamaz** (fail-fast — sessiz hata yerine açık hata).
- `functionName`: SBM'den bugün **`"test"`** ile tüm ortamlardan (TEST/UAT/PREP/PROD)
  token alınabiliyor. Operasyona özel isimler (`ysv-beyanname-gonder` vb.)
  **teyit edilmedi** → `functionName` config'e taşınır, default `"test"`. Token ekibiyle
  (Hüseyin Dağ / Ömer Faruk Ceylan) netleşince ortam config'inden değiştirilir.
- `transactionId`: her istekte yeni UUID; loglarda izlenebilir olmalı.

### 4.2 Yanıt

```json
{
  "accessToken": "eyJhbGciOiJIUzUxMiJ9....",
  "clientCredentials": {
    "clientIdentityType": 1,
    "clientIdNumber": "86773997310"
  }
}
```

- `accessToken` → SBM çağrısında `Authorization: Bearer <accessToken>`.
- `clientCredentials.clientIdentityType` → SBM header **`Requester-ID-Type`** (sayısal):
  `1` = T.C. Kimlik No, `2` = Vergi Kimlik No (VKN), `4` = Yabancı Kimlik No.
- `clientCredentials.clientIdNumber` → SBM header **`Requester-ID-No`** (kimlik no değeri).
- Her iki header **tüm servislerde zorunlu** (POST, PUT, GET). **Hardcode edilmez**,
  her çağrıda token cevabından okunur. Gerçek ESB çağrılarında `Requester-ID-Type: 1`
  + 11 haneli entegrasyon kullanıcısı (`koc`) T.C. No'su ile başarılı olundu.
- SBM dökümanı: kullanıcı müdahalesi olmayan toplu işlemlerde kurumun VKN'si de
  gönderilebilir; gönderilen kimliğin SBM'de önceden tanımlı olması gerekmez. Hangi
  kimlik/tip gönderileceği **token servisinin ayarıdır** — bu uygulama token ne
  dönerse onu iletir, değiştirmez.
- `accessToken` ve `clientIdNumber` loglara maskeli yazılır.
- Ortam bazlı konfig ve DB bağlantısının nasıl çözüldüğü: bkz. **`HELM-VE-KONFIG.md`**.

### 4.3 Eski yapı (obsolete — kod kalıntısı varsa temizlenir)

`SbmTokenService` (userCode/password ile doğrudan SBM authenticate), `SbmTokenDto`,
`sbm.auth.*` konfigürasyonu, 25 dakikalık cache.

---

## 5. AŞAMA 3 — SBM gönder / güncelle / sorgu

### 5.1 Endpoint'ler ve metotlar

Uygulama SBM'yi **doğrudan çağırmaz**; her istek ESB (OSB) **Proxy Service**'ine
gider, proxy SBM'ye yönlendirir.

| İşlem | Uygulamanın çağırdığı (ESB proxy) | Proxy → SBM (Business Service) | Metot |
|---|---|---|---|
| gönder | `<ESB_SERVER>/sbmDeclarationServices` | `.../v10/ysv-beyanname` | `POST` |
| güncelle | `<ESB_SERVER>/sbmDeclarationServices` | `.../v10/ysv-beyanname` | `PUT` |
| sorgu | `<ESB_SERVER>/sbmDeclarationServices` | `.../v10/ysv-beyanname` | `GET` |

- **SC-UAT'ta doğrulandı** (2026-09-01): `ESB_SERVER = http://10.70.47.135:21011`,
  proxy path `/sbmDeclarationServices` (üçü de aynı). Business Service SBM ŞİRKET TEST'e
  (`https://testrs.sbm.org.tr/...`) gider.
- Proxy path'i `esb.ysv.beyanname-path` / `sorgu-path` (default `/sbmDeclarationServices`,
  `common-configs/application.yml`), host:port `ESB_SERVER` (ortam bazlı) ile verilir.
- Sorgu, gönder ile **aynı path**tir; ayrı bir `/sorgu` eki **yoktur**.
- Sorgu **GET + query string** ile gider: `?sigortaSirketKodu=045&ysvDosyaNo=...`
  (SBM dökümanındaki Postman örneği; gövde yoktur). SC-UAT'ta OSB proxy'si GET'te bu
  parametreleri SBM'ye taşımıyordu → `CORE-00004`; düzeltme **ESB tarafında** (proxy GET
  route'u). Uygulama SBM sözleşmesine uygun; ESB proxy düzelince sorgu çalışır.
- Üç işlemde de header: `Authorization: Bearer ...`, `Requester-ID-Type`,
  `Requester-ID-No`, `Content-Type: application/json`.

### 5.2 Alan tipleri (SBM sözleşmesi)

SBM dökümanının **alan tipi tablosu** esastır:

| Alan | Tip | Metot | Zorunlu |
|---|---|---|---|
| `ay` | number | POST | E |
| `ilKodu` | number | POST | E |
| `ilceKodu` | number | POST | H (büyükşehirde gönderilmez) |
| `yil` | number | POST | E |
| `sigortaSirketKodu` | string (max 3) | POST/PUT | E — daima `045` |
| `sonOdemeTarihi` | string `yyyy-MM-dd` | POST/PUT | E |
| `ysvDosyaNo` | string (max 36) | POST/PUT | E |
| `alinanPrimTutari` | decimal | POST/PUT | E |
| `iptalPrimTutari` | decimal | POST/PUT | E |
| `menkulTipi` | string `MENKUL`/`GAYRIMENKUL` | POST/PUT | E |
| `odenecekVergi` | decimal | POST/PUT | E |
| `vergiOrani` | number | POST/PUT | E |
| `vergiPrimTutari` | decimal | POST/PUT | E |
| `gecmisAyIadeTutari` | decimal | POST/PUT | H (sonradan eklendi; negatif olabilir) |

**Kod native JSON tipleri gönderir** (sayısal alanlar tırnaksız). SBM başarılı
yanıtları da (sorgu capture'ı) sayısal alanları tırnaksız döner. Not: SBM tırnaklı
string sayıları da kabul ediyor (bir test çağrısı böyle başarılı oldu), ama sözleşme
tabloya göre tiplidir. → **VDI'da ilk doğrulanacak madde:** tipli gövde ile bir POST
denenip 422 gelmediği teyit edilir; gelirse ilgili alan string'e çevrilir.

Kurallar:
- `menkulTipi`: kaynakta `1`/`2`; SBM'ye `"MENKUL"`/`"GAYRIMENKUL"` string.
- `gecmisAyIadeTutari`: her `ysvTutarList` **elemanının içinde**, root'ta değil.
  DB'de değer yoksa alan gönderilmez. Cancel akışında `0` gönderilir.
- `ilceKodu` büyükşehirde payload'a **hiç konmaz** (`0` gönderilmez).
- POST ile PUT ayrımı `@JsonInclude(NON_NULL)` ile: PUT gövdesinde `ay/yil/ilKodu/
  ilceKodu` alanları `null` bırakılır, serileşmez.

### 5.3 Request gövdeleri

**Gönder (POST):**
```json
{
  "ay": 8,
  "ilKodu": 35,
  "sigortaSirketKodu": "045",
  "sonOdemeTarihi": "2026-08-31",
  "yil": 2026,
  "ysvDosyaNo": "YSV202513492",
  "ysvTutarList": [
    {
      "alinanPrimTutari": 1,
      "iptalPrimTutari": 1,
      "menkulTipi": "MENKUL",
      "odenecekVergi": 25000,
      "vergiOrani": 10,
      "vergiPrimTutari": 250000,
      "gecmisAyIadeTutari": -1100
    }
  ]
}
```
(Büyükşehir satırında `ilceKodu` hiç yer almaz.)

**Güncelle (PUT):** `ay/yil/ilKodu/ilceKodu` yok; geri kalanı POST ile aynı.
`ysvTutarList` menkul tipi başına bir eleman içerir.

**Sorgu (GET):** query string — `?sigortaSirketKodu=045&ysvDosyaNo=<...>` (gövde yok).

### 5.4 Response zarfı — **tüm işlemlerde `{ result, data, status }`**

ESB'den alınan **başarılı** örnekler:

| İşlem | Örnek yanıt |
|---|---|
| POST | `{ "result": true, "data": { "ysvDosyaNo": "1111111128" }, "status": 201 }` |
| PUT | `{ "result": true, "data": true, "status": 200 }` |
| GET | `{ "result": true, "data": { <beyanname alanları> }, "status": 200 }` |

Sorgu `data` içeriği (gerçek capture — dökümandaki alanlara ek olarak
`telefon/vkn/adres/unvan/ilceKodu` de gelir):
```json
{
  "sigortaSirketKodu": "045",
  "ilceKodu": null,
  "telefon": "8503999999",
  "ay": 8,
  "vkn": "8000013270",
  "ysvDosyaNo": "YSV202513492",
  "sonOdemeTarihi": "2026-08-31",
  "ysvTutarList": [
    { "vergiPrimTutari": 250000, "alinanPrimTutari": 1, "vergiOrani": 10,
      "odenecekVergi": 25000, "menkulTipi": "MENKUL", "iptalPrimTutari": 1 }
  ],
  "ilKodu": 35,
  "adres": "Allianz Tower ... Ataşehir/İstanbul",
  "yil": 2026,
  "unvan": "ALLİANZ SİGORTA ANONİM ŞİRKETİ"
}
```

**Başarı kriteri:** HTTP 2xx **ve** `result == true`.
- POST'ta üretilen `ysvDosyaNo` → `data.ysvDosyaNo` (root değil).
- PUT'ta `data` boolean gelir.
- GET'te tüm beyanname `data` altındadır.

### 5.5 Hata yanıtı — HTTP 422

```json
{
  "result": false,
  "status": 422,
  "error": {
    "timestamp": "2026-02-02T21:45:38.783",
    "reasons": [
      { "field": "ilceKodu", "code": "CORE-01004",
        "message": "ilceKodu alanının değeri 1 - 4 arasında olmalıdır.",
        "rejectedValue": "21" }
    ]
  }
}
```

- Beklenmeyen hatada, **Response Header'daki `Transaction-Id`** loglanır ve SBM destek
  taleplerine eklenir (SBM bunu istiyor).
- `error.reasons[]` birleştirilip `ERROR_DETAILS`'e (max 2000 karakter) yazılır.

### 5.6 Gruplama

`DeclarationGroupKey = (yıl, ay, ilKodu, ilceKodu)`. SBM İl-İlçe-Yıl-Ay başına tek
beyanname kabul eder (`RISK-HAVUZU-00004`). `ysvDosyaNo` anahtarın parçası **değildir**;
grubun satırlarından okunur. Grubun satırları `ysvTutarList` elemanlarına dönüşür —
menkul tipi başına bir eleman (`RISK-HAVUZU-00005` mükerrer menkul tipini reddeder).

### 5.7 Retry

Sadece **`SEC-00002`** (token geçersiz/süresi dolmuş) ve **HTTP 5xx** yeniden denenir
(varsayılan `maxAttempts = 2`). Her denemede yeni token alınır. Diğer tüm hatalar
veri/yetki sorunudur; yeniden gönderim mükerrer beyanname riski taşır.

### 5.8 Durum geçişleri (`ALZ_SBM_DECL_PROCESS.STATUS`)

```
NEW ──gönder──▶ PROCESSING ──(2xx & result:true)──▶ SENT ──sorgu onayı──▶ COMPLETED
                    │
                    └──(hata / result:false)──▶ ERROR   (SEC-00002/5xx ise retry edilir)
```
- `send` (POST): `NEW`, `ERROR` durumundakileri alır.
- `update` / `cancel` (PUT): `SENT`, `COMPLETED` durumundakileri alır.
- Grup bazında pesimistik satır kilidi ile aynı grubun iki kez gönderilmesi engellenir.
- Batch tek transaction değildir (uzun uzak çağrılar boyunca kilit tutmamak için);
  her grup kendi transaction'ında işlenir.

---

## 6. AŞAMA 4 — ESB

- **Tek giriş noktası:** `http://esb.allianz.com.tr:12000` (ortam bazlı override:
  `ESB_SERVER` env — bazı ortamlarda DNS yok, IP gerekebilir, ör.
  `http://10.70.52.149:12000`).
- ESB'nin test/sc-uat/prep/live ortamları vardır; her ortamın ESB'si isteği **kendi
  eşleştiği SBM ortamına** yönlendirir. Uygulama ortama göre farklı SBM adresi seçmez;
  sadece ortamın ESB base-url'ini bilir.
- **Bağlantı yöntemi: düz HTTP (Spring `RestClient`).** `tr.com.allianz:ysv-services-rest-client`
  gibi bir istemci kütüphanesi **eklenmez** (iç Nexus'ta varlığı teyitli değil, düz
  HTTP yeterli). pom.xml'e ESB için hiçbir bağımlılık girmez.
- HTTP istemcisi **Apache HttpClient 5** tabanlıdır (bağlantı havuzu + connect/read
  timeout'ların ayrı ayrı kontrolü). Sorgu GET + query string olduğu için gövde sorunu yok.
- ESB proxy path'i **SC-UAT'ta doğrulandı**: `/sbmDeclarationServices` (üç işlem de),
  `common-configs/application.yml` içinde default. Ortam farkı sadece `ESB_SERVER`
  (host:port); SC-UAT = `http://10.70.47.135:21011`.
- **Açık:** proxy'nin **sorgu (GET) route'u** `sigortaSirketKodu`/`ysvDosyaNo`'yu SBM'ye
  taşımıyor (`CORE-00004`) — ESB tarafında düzeltilecek. POST/PUT sorunsuz. Proxy'nin
  bu alanları gövdeden mi query string'den mi okuyacağı netleşince uygulama hizalanır.

---

## 7. SBM hata kodları (referans)

| Kod | Anlam / aksiyon |
|---|---|
| `RISK-HAVUZU-00002` | Başka sigorta şirketi adına işlem yapılamaz (`sigortaSirketKodu` sadece kendi kodu) |
| `RISK-HAVUZU-00003` | İlgili ay veri girişine kapalı (ay kapaması sonrası) |
| `RISK-HAVUZU-00004` | Mükerrer beyanname — İl-İlçe-Yıl-Ay başına tek kayıt |
| `RISK-HAVUZU-00005` | Mükerrer menkul tipi gönderilemez |
| `RISK-HAVUZU-00006` | İl bulunamadı (hatalı il kodu) |
| `RISK-HAVUZU-00007` | Büyükşehirde ilçe gönderilemez |
| `RISK-HAVUZU-00008` | Büyükşehir değilse ilçe gönderilmelidir |
| `RISK-HAVUZU-00009` | İlçe bulunamadı (hatalı ilçe kodu) |
| `SEC-00001` | Kimlik doğrulama bilgileri gönderilmelidir (token boş/eksik) |
| `SEC-00002` | Token geçersiz veya süresi dolmuş → **retry** |
| `SEC-00003` | Bu kaynağa erişim izniniz yok |
| `SEC-00004` | IP adresi doğrulanamadı (IP whitelist) |
| `SEC-00005/06/07` | Header boyut/eksik/format hatası |
| `SEC-00008` | `Requester-ID-No` header değeri hatalı |
| `CORE-00000` | Sistemde beklenmeyen hata — `Transaction-Id` ile SBM'ye destek talebi |
| `CORE-00001` | Desteklenmeyen HTTP metodu |
| `CORE-00005` | `{0}` alanının formatı hatalı |
| `CORE-00006` | Gelen veri beklenen formatta değil / geçersiz |
| `CORE-00009` | Kaynak bulunamadı (endpoint adresi hatalı olabilir) |
| `CORE-01000` | `{0}` alanı zorunludur (`field` alanında belirtilir) |
| `CORE-01001` | Kayıt bulunamadı |
| `CORE-01004` | `{0}` alanının değeri `{1}`–`{2}` arasında olmalıdır |
| `CORE-01008` | `{0}` alanının uzunluğu `[{1}]`–`[{2}]` arasında olmalıdır |

---

## 8. Loglama

- Her gönder / güncelle / sorgu işlemi `ALZ_SBM_DECL_LOG`'a yazılır
  (`OPERATION_TYPE`, `LOG_LEVEL`, `LOG_MESSAGE`, `REQUEST_PAYLOAD`, `RESPONSE_PAYLOAD`).
- `transactionId` (token isteği) ve SBM `Transaction-Id` (response header) uygulama
  loglarında izlenebilir olmalı.
- `Authorization` başlığı ve token değerleri audit payload'una **yazılmaz**;
  uygulama loglarında maskelenir.

---

## 9. Veritabanı

Şema `CUSTOMER`, public synonym'lerle.

DB scriptleri (`db/`):

| Dosya | Amaç |
|---|---|
| `setup_db.sql` | Prod şeması — sıfırdan CREATE (CUSTOMER. + synonym + grant) |
| `rollback_db.sql` | `setup_db.sql`'in tersi — toleranslı PL/SQL, tüm ortamlara deploy edilebilir |
| `sample_insert.sql` | 50 satır gerçekçi hacimli örnek veri (25 grup, hepsi `NEW`) |
| `sample_data_scenarios.sql` | Küçük etiketli senaryo seti (S1–S10) — her kod yolunu tetikler; şema öneksiz |
| `local/local_setup.sql` | Lokal Oracle şeması (öneksiz, synonym/grant yok) |
| `local/local_rollback.sql` | Lokal şema geri alma |


- `ALZ_SBM_MUNICIPALITY` — belediye referans (CITY_CODE, DISTRICT_CODE, KEP/e-posta, ...)
- `ALZ_SBM_DECL_PROCESS` — beyanname süreç tablosu (§3.3 eşlemesi)
- `ALZ_SBM_DECL_LOG` — yasal kanıt / REST API log tablosu

Kısıtlar: `MOVABLE_TYPE IN ('MENKUL','GAYRIMENKUL')`,
`STATUS IN ('NEW','PROCESSING','SENT','ERROR','COMPLETED')`,
`DECLARATION_MONTH 1..12`, `DECLARATION_YEAR 2000..2099`.

DB bağlantısı Vault'tan gelir (`spring.cloud.vault`, `default-context
DEV/apps/sbm-declaration-services`); k8s ortamında Vault agent template
`SPRING_DATASOURCE_*` env değişkenlerini export eder.

OPUS ortam JDBC (referans, `Ortamlarin.DB.Erisim.bilgileri.docx`):
- SC-UAT: `jdbc:oracle:thin:@//opusuat-scan.allianz-tr.local:1521/OPSSCUAT`
- SC-PREP: `jdbc:oracle:thin:@//opusprep-scan.allianz-tr.local:1521/opusprep`
- SC-PROD: `jdbc:oracle:thin:@//opusprod-scan.allianz-tr.local:1453/OPUSAUX`

---

## 10. `CLAUDE.md` ile çözülen çelişkiler (karar kaydı — 2026-08-30)

| # | Konu | Eski `CLAUDE.md` | Karar (bu doküman) |
|---|---|---|---|
| 1 | Excel ingest | "Uygulama insert yapmaz; `poi-ooxml` ekleme" | Prod'da manuel script yasak → **Excel yükleme + insert servisi yazılır**, `poi-ooxml` eklenir (§3) |
| 2 | ESB bağlantısı | "düz HTTP" vs docx'teki `ysv-services-rest-client` | **Düz HTTP (RestClient)**, kütüphane eklenmez (§6) |
| 3 | Alan tipleri | Tipli JSON (tablo), tırnaklı örnek "yanlış" | Tipli JSON **korunur**; VDI'da 1. POST ile doğrulanır, 422 gelirse ilgili alan string'e çevrilir (§5.2) |
| 4 | SBM response zarfı | Kod alanları kök seviyede okuyor | Tüm yanıtlar `{ result, data, status }` — `data` üzerinden okunur (§5.4) |
| 5 | Sorgu endpoint/metot | `.../ysv-beyanname/sorgu` | Path gönderle **aynı**; `GET` + **query string** `?sigortaSirketKodu=045&ysvDosyaNo=...` (SBM Postman örneği, §5.1). OSB proxy GET route'u düzeltilene kadar `CORE-00004` — ESB tarafı. |
| 6 | Token `functionName` | enum'da sabit operasyon isimleri | Config'e taşınır, default `"test"` (§4.1) |

Doğrulandı, kod zaten doğru: `Authorization: Bearer`; `Requester-ID-*` token'dan;
token cache yok; her çağrıda taze token; `Transaction-Id` loglanıyor;
`@JsonInclude(NON_NULL)` ile POST/PUT ayrımı; `DeclarationGroupKey`.

---

## 11. VDI'da doğrulanacak açık maddeler

1. **Tipli gövde:** SC-TEST'e tipli JSON ile bir POST — 422 gelmiyor mu? (Gelirse §5.2 fallback.)
2. **ESB sorgu (GET) route'u:** proxy `/sbmDeclarationServices` GET akışı
   `sigortaSirketKodu`+`ysvDosyaNo`'yu SBM Business Service'e aktarmıyor (`CORE-00004`).
   ESB ekibi düzeltince: proxy gövde mi query string mi bekliyor → uygulama hizalanır.
   (POST/PUT ve proxy path `/sbmDeclarationServices` SC-UAT'ta doğrulandı.)
3. **`ysv-services-rest-client`** iç Nexus'ta var mı — yoksa §6 kararı zaten geçerli,
   varsa bile eklenmeyecek (bilgi amaçlı).
4. **Token `functionName`:** token ekibi (Hüseyin Dağ / Ömer Faruk Ceylan) operasyona
   özel isim istiyor mu, yoksa `"test"` kalıcı mı.
5. **SBM REST şifresi (`koc` kullanıcısı)** ve TEST/PRE/PROD IP whitelist talepleri.
6. **Veri anomalisi (iş birimine):** 14 satır büyükşehir olduğu halde ilçe kodlu
   (il 22 Edirne), 2 satır büyükşehir olmadığı halde ilçe kodu 0 (il 47 Mardin).
7. **`mvn clean verify`** — ilk kez VDI'da (iç Nexus'lu) derlenecek.
8. **Excel `menkulTipi` formatı:** iş biriminden `MENKUL`/`GAYRIMENKUL` metniyle
   istenecek (§3.2). Sayısal `1`/`2` gelmeye devam ederse parser dönüştürür.
9. **PEN test kapsamı** (§14): rate limit + erişim kontrolü gateway/altyapı
   katmanında; uygulama içi ApiGuard kaldırıldı (2026-09-18).

---

## 12. Lokal test (geliştiricinin kendi makinesi)

Lokalde de test edebilmek için gerçek bir Oracle örneğine ihtiyaç var (in-memory DB
yok; şema prod ile aynı kalmalı).

1. **Lokal Oracle:** Oracle XE / Free (Docker) veya kurumsal lokal örnek.
   ```
   CREATE USER ysv IDENTIFIED BY ysv;
   GRANT CONNECT, RESOURCE, UNLIMITED TABLESPACE TO ysv;
   ```
2. **Şema:** `db/local/local_setup.sql` (öneksiz DDL). Geri alma: `db/local/local_rollback.sql`.
3. **Test verisi:** `db/sample_data_scenarios.sql` (S1–S10) ve/veya `db/sample_insert.sql`.
4. **Profil:** `src/main/resources/application-local.yml` — Vault yok, doğrudan JDBC:
   ```yaml
   spring:
     config:
       activate:
         on-profile: local
     cloud:
       vault:
         enabled: false
     datasource:
       url: jdbc:oracle:thin:@//localhost:1521/XEPDB1
       username: ysv
       password: ysv
   ```
   Çalıştırma: `mvn spring-boot:run -Dspring-boot.run.profiles=local`
5. **Token + ESB:** lokalde `int-sc-test-auth...` ve `esb.allianz.com.tr` VDI/VPN
   dışında erişilemez. Lokal uçtan uca test için bu iki çağrı mock'lanır ya da
   sadece 1. aşama (Excel → DB) + repository/mapper katmanı lokalde test edilir;
   gerçek SBM/ESB testi VDI'da yapılır.

### Kompleks veri testi (unutulmayacak)

`sample_data_scenarios.sql` **S3** ve **S4** grupları aynı beyannamede hem `MENKUL`
hem `GAYRIMENKUL` satırı içerir → `ysvTutarList` 2 elemanlı POST/PUT üretmeli.
**S7** aynı grupta iki `MENKUL` satırı ile yerel `RISK-HAVUZU-00005` guard'ını,
**S8** aynı grupta farklı `ysvDosyaNo` ile `resolveFileNo` davranışını test eder.

---

## 13. Test ve kalite politikası

- **Birim test kapsamı ≥ %90** (firma politikası). JaCoCo `check` kuralı
  `INSTRUCTION` ve `BRANCH` için **minimum 0.90**'a çekilir
  (mevcut: 0.85 / 0.80). `haltOnFailure = true`; `verify` fazında kırar.
- Kapsam dışı (JaCoCo `excludes`): `*Application`, `dto/**`, `entity/**`,
  `*Properties`, `ErrorResponse`, `*MapperImpl`.
- Yeni eklenen her sınıf (Excel import, response zarfı, sorgu GET+query string) için
  birim testi zorunlu; kapsam eşiği bunları da kapsar.
- `mvn clean verify` yeşil olmadan PR açılmaz.

---

## 14. Güvenlik / PEN test (firma politikası)

Proje penetrasyon testine girecek. En az şu iki başlıktan geçmeli:

### 14.1 Rate limiting / API key — **uygulamada YOK (2026-09-18 kararı)**

Uygulama içi `ApiGuardFilter` (token-bucket rate limit + `X-Api-Key`) kaldırıldı.
Rate limit ve kimlik doğrulama iç gateway / altyapı katmanına bırakıldı; servis bu
konuda kod veya `api-guard.*` config taşımaz.

### 14.2 Broken access control

Uygulama uçlarına **kimlik doğrulaması olmadan doğrudan istek** atılamamalı.
`/api/v1/declarations/**` uçlarında kimlik kontrolü iç gateway'e bırakıldı (§14.1).
Servis tarafında kalanlar:
- Actuator: sadece `health`, `info`, `metrics`, `prometheus` açık; `env`,
  `beans`, `mappings`, `heapdump`, `threaddump` **kapalı**.
- Hata cevaplarında stack trace / iç detay sızmamalı (`GlobalExceptionHandler`).
- `Authorization`, token, `Requester-ID-No` **loglara yazılmaz** (maskeli).
- Güvenlik başlıkları (`X-Content-Type-Options`, `X-Frame-Options` vb.).
- Girdi doğrulama: dosya boyutu / tipi (yalnız `.xlsx`), satır sayısı üst sınırı.
