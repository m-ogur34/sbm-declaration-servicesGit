# Proje Rehberi ve Canlı Öncesi Değerlendirme

> Kaynaklar: `ysv-beyanname_gönder-güncelle.pdf`, `ysv-beyanname_sorgu.pdf`,
> `token.alabilmek.icin.ornek.request-response.docx`,
> `sbm_tsb veri gönderme çalışması Temmuz 2026.xlsx` (580 satır) ve reponun kodu.
> Tarih: 2026-09-21. Diğer dokümanlarla çelişirse sıralama:
> `CALISMA-PRENSIBI.md` → bu dosya → `CLAUDE.md`.
>
> **İncelenen ağaç: `main` (306271f + düzeltmeler, 2026-09-21).** Aşağıdaki
> 1/2/3/4/7/12 no'lu maddeler bu commit'te **düzeltildi**; tablo kayıt amaçlı duruyor. Lokal `feature/SBMD-13`
> dalı 13 commit geride; aradaki Java farkı sadece **yorum/javadoc silme** (tek
> istisna aşağıdaki §0.1 derleme hatası). `helm/chart/configs/*`, `helm/values/*`,
> `src/test/*` ve `ExcelDeclarationParser` iki dalda **aynı**.

---

## 0. Yönetici özeti — canlıyı bloklayan maddeler

| # | Öncelik | Alan | Bulgu |
|---|---|---|---|
| 1 | ✅ giderildi | Build | `origin/main` **derlenmiyor**: `DeclarationLogService.java:25` — javadoc silinirken açılış `/**` gitmiş, `responsePayload raw response body` + `*/` başıboş kalmış (§0.1) |
| 2 | ✅ giderildi | Vault/Config | Ortam yml'lerindeki `${tokenManagementClientName}` / `${tokenManagementUserName}` / `${tokenManagementCompanyCode}` placeholder'ları, Vault'un export ettiği `TOKEN_MANAGEMENT_CLIENT_NAME` env adlarıyla **eşleşmez** → uygulama hiçbir k8s ortamında ayağa kalkmaz |
| 3 | ✅ giderildi | Vault | `TOKEN_MANAGEMENT_COMPANY_CODE` hiçbir `values/*.yaml` içinde export edilmiyor; `token-management.company-code` `@NotBlank` → başlatma hatası |
| 4 | ✅ SC-UAT için | ESB | `sc-test.yaml` / `sc-uat.yaml` içinde `global.overrides.esb.server: ~` → SC-UAT'ta doğrulanan `http://10.70.47.135:21011` yerine default `esb.allianz.com.tr:12000` kullanılır |
| 5 | **P1** | **Güncelleme** | Güncellenecek veriyi DB'ye yazacak hiçbir yol yok + SBM'de zaten var olan (`RISK-HAVUZU-00004`) beyanname **asla** PUT edilemez, sadece tekrar POST edilir (§5) |
| 6 | **P1** | **Güncelleme** | Başarısız PUT → satır `ERROR` → `ERROR` `SENDABLE` kümesinde → bir sonraki `send` aynı beyannameyi **tekrar POST eder** (mükerrer beyanname riski) |
| 7 | ✅ giderildi | Helm | `sc-test.yaml` / `sc-uat.yaml` içinde `sysType` ve `autoscale` yanlış seviyede girintilenmiş → sessizce yok sayılır |
| 8 | P2 | Vault | Datasource `opusAgencyDataSource` + `username_allzwebpol` (accounting'den kopya). Smoke test `AZSDB_40895` ile yapıldı → pod'un bağlanacağı kullanıcı `CUSTOMER.ALZ_SBM_*` tablolarını görmüyor olabilir |
| 9 | P2 | Güncelleme/İptal | `cancel` SBM'de tutarları sıfırlar ama DB'de iz bırakmaz; sonraki `update` canlı tutarları geri gönderir |
| 10 | P2 | SBM sözleşmesi | PDF'teki cevaplar **düz** (`ysvDosyaNo` kök seviyede); kod sadece `data.*` okuyor. ESB capture'ı `data` zarflı — ikisini de tolere etmek gerekir |
| 11 | P3 | Excel | Aynı `ysvDosyaNo`'nun dosyada iki kez geçmesi (MENKUL + GAYRIMENKUL) sadece `saveAll` döngü **sonrasında** olduğu için çalışıyor; kırılgan ve testle korunmuyor |
| 12 | ✅ giderildi | Docker | Base image **rootless UBI9**'a çevrilmiş ama `RUN addgroup -S … && adduser -S …` (Alpine/BusyBox sözdizimi) ve `USER allianz` satırları duruyor → image **build olmaz**; `HEALTHCHECK` `wget` çağırıyor, UBI9'da `wget` yok |
| 13 | kısmen | Config | `helm/chart/common-configs/application.yml` ortam config'inin kopyasıyla değiştirilmiş: `token-management` bloğu (PREP auth URL'i ile) artık **ortak** config'te — "token parametreleri ortama özel, ortak default yazılmaz" kuralına aykırı. Ayrıca hikari havuzu, Oracle dialect, `open-in-view:false`, jackson ve `sbm.retry` ayarları kayboldu; `management` bloğu geçersiz `spring.management` altına düşmüş |

Token mekanizması, ESB çağrı yapısı, alan tipleri ve Excel formatı **verilen dokümanlarla uyumlu**.
Sorunlar konfigürasyon paketleme ve güncelleme akışının durum yönetiminde.

### 0.1 `origin/main` şu an derlenmiyor

`src/main/java/.../service/DeclarationLogService.java`:

```java
    private final DeclarationLogRepository declarationLogRepository;

 responsePayload raw response body        // ← açılış /** yok
     */                                   // ← başıboş kapanış
    @Transactional(propagation = Propagation.REQUIRES_NEW)
```

`javac` çıktısı: `DeclarationLogService.java:25: error: ';' expected` (2 hata).
Düzeltme: bu iki satırı silmek. Repodaki **tek** dengesiz yorum bloğu burası;
diğer 34 dosyada javadoc silme işlemi temiz yapılmış.

---

## 1. Mimari harita — hangi sınıf ne yapıyor

```
                       ┌──────────────────────────────┐
  Excel (.xlsx) ─────► │ DeclarationImportController  │  POST /api/v1/declarations/upload
                       └──────────────┬───────────────┘
                                      ▼
                       ┌──────────────────────────────┐   satır bazlı tip/format doğrulama
                       │ ExcelDeclarationParser       │──►ParsedRow + ExcelRowError
                       └──────────────┬───────────────┘
                                      ▼
                       ┌──────────────────────────────┐   tek (yıl,ay) kontrolü, mükerrer
                       │ DeclarationImportService     │   ysvDosyaNo kontrolü, STATUS=NEW
                       └──────────────┬───────────────┘
                                      ▼
                             ALZ_SBM_DECL_PROCESS
                                      │
  Operatör/UI ─────────────────────────┼───────────────────────────────────────────────
                                      ▼
                       ┌──────────────────────────────┐  /send /update /cancel /query /processes
                       │ DeclarationController        │
                       └──────────────┬───────────────┘
                                      ▼
                       ┌──────────────────────────────┐  aday satırları seç, (yıl,ay,il,ilçe)
                       │ DeclarationService           │  bazında grupla, grupları sırayla işle
                       └──────────────┬───────────────┘
                                      ▼
                       ┌──────────────────────────────┐  @Transactional + satır kilidi,
                       │ DeclarationGroupProcessor    │  durum geçişleri, hata kaydı
                       └───────┬──────────────┬───────┘
                               ▼              ▼
                    ┌─────────────────┐  ┌──────────────────────┐
                    │ SbmMapper       │  │ DeclarationLogService│ REQUIRES_NEW → ALZ_SBM_DECL_LOG
                    │ entity → JSON   │  └──────────────────────┘
                    └────────┬────────┘
                             ▼
                    ┌─────────────────┐   her çağrıda taze token
                    │ SbmClientService│◄──┤ TokenManagementService ├──► alz-token-management
                    └────────┬────────┘
                             ▼
                        ESB (OSB Proxy)  ──►  SBM  ysv-beyanname
```

| Sınıf | Sorumluluk |
|---|---|
| `ExcelDeclarationParser` | İlk sheet, başlık adına göre kolon eşleme (sıra bağımsız), tip dönüşümü (seri tarih/gerçek tarih, `1`/`2` → `MovableType`, tutarlar `BigDecimal` scale 2 HALF_UP). Satır hatası diğer satırları durdurmaz. Sınır: 20.000 satır |
| `DeclarationImportService` | Tek (yıl,ay) kuralı (ihlalde 400), DB'de mükerrer `ysvDosyaNo` reddi, `COMPANY_CODE=045`, `STATUS=NEW` insert |
| `DeclarationService` | Aday seçimi (filtre: yıl/ay/il veya `processIds`), `DeclarationGroupKey` = (yıl, ay, ilKodu, ilceKodu) ile gruplama, grup grup çağırma. Batch **tek transaction değil** |
| `DeclarationGroupProcessor` | Tek grup = tek SBM isteği. Pesimistik kilit → durum uygunluk kontrolü → `PROCESSING` → çağrı → `SENT`/`ERROR` + audit log |
| `SbmMapper` | Entity listesi → `SbmDeclarationRequest`. POST'ta `ay/yil/ilKodu/ilceKodu` dolu, PUT'ta `null` (⇒ `@JsonInclude(NON_NULL)` ile payload'a girmez). `ilceKodu` null/0 → alan hiç yok. Mükerrer menkul tipi → `RISK-HAVUZU-00005` |
| `TokenManagementService` | Her çağrıda yeni UUID `transactionId` ile token; cache yok; `accessToken`/`clientIdNumber` maskeli loglanır |
| `SbmClientService` | Token al → header'ları kur → ESB'ye istek → cevabı `SbmCallResult`'a çevir. Hata **fırlatılmaz, döndürülür** (audit yazılabilsin diye). Retry sadece `SEC-00002` ve HTTP 5xx |
| `DeclarationLogService` | `REQUIRES_NEW` — beyanname transaction'ı rollback olsa bile yasal kanıt kaybolmaz |

---

## 2. Uçtan uca akışlar

### 2.1 Excel yükleme
```
POST /sbm-declaration-services/api/v1/declarations/upload
Header: X-User-Name: <kullanıcı>      (yoksa SYSTEM)
Body  : multipart/form-data, file=<*.xlsx>
→ 200 { fileName, totalRows, insertedRows, failures:[{rowNumber,ysvDosyaNo,errorCode,message}] }
```
Reddedilen tüm dosya halleri: .xlsx değil, sheet/başlık yok, zorunlu kolon eksik,
20.000 satır aşımı, dosyada birden fazla (yıl, ay).

### 2.2 Gönder (POST)
```
POST /api/v1/declarations/send   { "year":2026, "month":7 }        (veya "processIds":[...])
```
`NEW`+`ERROR` satırlar → gruplanır → grup başına: token → ESB POST → 2xx & `result==true` ise `SENT`.

### 2.3 Güncelle / İptal (PUT)
```
PUT  /api/v1/declarations/update  { "year":2026, "month":7 }
POST /api/v1/declarations/cancel  { "processIds":[123,124] }   ← tutarları 0 gönderir
```
`SENT`+`COMPLETED` satırlar → PUT gövdesi 4 alan + `ysvTutarList`.

### 2.4 Sorgu (GET)
```
GET /api/v1/declarations/query/{ysvDosyaNo}
```
ESB'ye `GET <base>/sbmDeclarationServices?sigortaSirketKodu=045&ysvDosyaNo=...`;
başarılıysa ilgili satırlar `SENT` → `COMPLETED`.

### 2.5 Durum makinesi (kodun bugünkü hâli)
```
NEW ──send──► PROCESSING ──2xx & result:true──► SENT ──query──► COMPLETED
                   │                              │                │
                   └──hata──► ERROR ──send──┐     └──update/cancel─┘
                                 ▲          │              │
                                 └──────────┘              ▼ (hata)
                                                         ERROR   ← BURASI SORUNLU (§5)
```

---

## 3. SBM sözleşmesi uyumu (PDF ↔ kod)

| Alan | PDF | Kod | Durum |
|---|---|---|---|
| `ay`, `ilKodu`, `ilceKodu`, `yil` | Number, **sadece POST** | `Integer`, PUT'ta `null` | ✅ |
| `sigortaSirketKodu` | String max 3, POST/PUT | `String`, `045`, uzunluk kontrolü var | ✅ |
| `sonOdemeTarihi` | LocalDate | `@JsonFormat("yyyy-MM-dd")` | ✅ |
| `ysvDosyaNo` | String max 36 | uzunluk kontrolü var | ✅ |
| `alinanPrimTutari`, `iptalPrimTutari`, `odenecekVergi`, `vergiPrimTutari` | Decimal | `BigDecimal`, null → `0` | ✅ |
| `vergiOrani` | Number | `Integer`, null → `0` | ✅ |
| `menkulTipi` | Tabloda "String, 1=Menkul 2=Gayrimenkul", örnekte `"MENKUL"`/`"GAYRIMENKUL"` | `MENKUL`/`GAYRIMENKUL` | ✅ (SC-UAT'ta doğrulandı) |
| `gecmisAyIadeTutari` | **PDF'te hiç yok** | DB'de değer varsa gönderilir | ⚠️ Excel'de kolon yok → hiç gönderilmiyor. Sözleşmeye eklenene kadar riski yok |
| `ilceKodu = 0` | Büyükşehirde gönderilmemeli | `DistrictCodeResolver` alanı tamamen çıkarır | ✅ |
| Hata cevabı | HTTP 422 + `error.reasons[]` | Tüm `reasons` birleştirilip `ERROR_DETAILS`'e (2000 kr) | ✅ |
| `Transaction-Id` response header | Destek talebi için loglanmalı | Her çağrıda loglanıyor | ✅ |

**Uyuşmayan iki nokta:**

1. **Cevap zarfı.** PDF'teki örnekler **düz**:
   `{"result":true,"status":200,"ysvDosyaNo":"..."}` ve sorguda beyanname alanları da kök
   seviyede. Kod ise `data` zarfını okuyor (`SbmDeclarationResponse.extractYsvDosyaNo()`,
   `SbmQueryResponse.data`). ESB'den alınan gerçek capture `data` zarflı olduğu için kod
   doğru davranıyor; ancak SBM/ESB düz cevap dönerse **sorgu sessizce boş `data` ile
   başarılı** görünür. Önerilen: `data` yoksa kök seviyeye düşen bir fallback.
2. **Sorgu isteğinin taşıyıcısı.** PDF'te sorgu REQUEST'i **JSON gövde** olarak gösterilmiş
   (girdi tablosunda metot "POST" yazıyor, başlıkta "GET"). Kod query string kullanıyor.
   SC-UAT'ta ESB proxy GET route'u parametreleri taşımadığı için `CORE-00004` alınıyor —
   düzeltme ESB tarafında. Testte **iki varyant da** denenmeli.

---

## 4. Alan bazlı değerlendirme

### 4.1 Token mekanizması — ✅ uyumlu
Docx'teki örnekle birebir: `POST https://int-sc-test-auth.allianz.com.tr/alz-token-management/api/v1/tokens/sbm-token-generate`,
gövde `clientName/transactionId/functionName/userName/companyCode`, cevap
`accessToken` + `clientCredentials{clientIdentityType, clientIdNumber}`.
`Requester-ID-Type`/`Requester-ID-No` hardcode değil, token cevabından; cache yok; loglar maskeli.

Açık maddeler:
- `function-name` hiçbir ortam yml'inde yazılı değil (sadece sınıf default'u `test`).
  Görünürlük için her ortam config'ine açıkça yazılması önerilir.
- `userName = WDA2422_16178` kişisel görünümlü bir hesap; gece çalışan toplu iş için
  servis hesabı olup olmayacağı token ekibiyle netleşmeli (zaten açık madde).

### 4.2 ESB katmanı — ✅ yapı doğru, ❌ adres konfigürasyonu eksik
Tek base-url, üç işlem aynı proxy path, düz `RestClient` (Apache HttpClient 5),
gidilen tam URL loglanıyor, GET'te gövde gönderilmiyor. **Ama** `sc-test.yaml`/`sc-uat.yaml`
içinde `esb.server: ~`; SC-UAT'ta doğrulanan `http://10.70.47.135:21011` yazılmalı.
`prep/live/dr` dosyalarında `global.overrides.esb.server` anahtarı hiç yok.

### 4.3 Vault ve konfigürasyon — ❌ üç blokan
1. `helm/chart/configs/application-*.yml` dosyalarındaki
   `client-name: ${tokenManagementClientName}` satırları. Spring, `${tokenManagementClientName}`
   placeholder'ını ortam değişkeni ararken sadece aynı ad ve `TOKENMANAGEMENTCLIENTNAME`
   biçimini dener; Vault ise `TOKEN_MANAGEMENT_CLIENT_NAME` export ediyor → placeholder
   **çözülemez, uygulama başlamaz**.
   **Çözüm (en temizi): bu üç satırı yml'den tamamen silmek.** `TOKEN_MANAGEMENT_CLIENT_NAME`
   env'i Spring'in gevşek bağlama kuralıyla `token-management.client-name`'e zaten otomatik
   oturur. (Alternatif: `${TOKEN_MANAGEMENT_CLIENT_NAME}` yazmak.)
2. `TOKEN_MANAGEMENT_COMPANY_CODE` hiç export edilmiyor. Vault şablonuna eklenmeli
   (`SBM_COMPANY_CODE` ile aynı Vault anahtarından).
3. `SWAGGER_API_KEY` export ediliyor ama kodda okuyan yok (`ApiGuardFilter` kaldırıldı) →
   temizlenmeli.

Ayrıca:
- `values/sc-test.yaml` ve `sc-uat.yaml`: `sysType` `springboot:` altında, `autoscale`
  `app:` altında girintilenmiş; `prep/live/dr`'de bir üst seviyede. Bu hâliyle ikisi de
  parent chart tarafından okunmaz.
- Datasource `opusAgencyDataSource` / `username_allzwebpol` / `URL_OPUS*_FINANS`
  accounting'den kopya. Lokal smoke `AZSDB_40895` ile yapıldı; pod'un bağlanacağı
  kullanıcının `CUSTOMER.ALZ_SBM_DECL_PROCESS` üzerinde SELECT/INSERT/UPDATE yetkisi
  **deploy öncesi** doğrulanmalı.
- Paketlenmiş `application.yml` içinde `spring.profiles.active: dev` sabit. Chart
  `SPRING_PROFILES_ACTIVE` vermezse pod dev profiliyle açılır ve TEST Vault'una gider.
- `bootstrap.yml` Spring Cloud 2025'te (bootstrap starter olmadan) okunmaz — yanıltıcı,
  kaldırılabilir.
- `configs/application-sc-test|prep|prod.yml` içindeki `spring.management:` bloğu geçersiz
  (doğrusu kök `management:`), `spring.datasource.opus|cognos` blokları bu projede
  kullanılmıyor — accounting kopyası.

### 4.4 Excel import ↔ verilen dosya — ✅ uyumlu
`sbm_tsb veri gönderme çalışması Temmuz 2026.xlsx` üzerinde yapılan analiz:

| Kontrol | Sonuç |
|---|---|
| Sheet / başlık | `Sheet2`, 13 kolon, beklenen 13 başlıkla birebir ✅ |
| Veri satırı | 580 ✅ (limit 20.000) |
| Dönem | `ay=7`, `yil=2026` — tek dönem ✅ |
| `sonOdemeTarihi` | Gerçek tarih hücresi (`2026-08-20`), seri sayı değil → `isCellDateFormatted` dalı çalışır ✅ |
| `menkulTipi` | Tamamı `1`/`2` → `MovableType.fromExcel` ✅ |
| `ilceKodu` | 58 satırda `0` → payload'a hiç konmaz ✅ |
| `sigortaSirketKodu` | Tamamı `2320` (OPUS kodu) → yok sayılır, DB'ye `045` ✅ |
| `vergiOrani` | Tamamı `10`, hücre formatı kesirli (`# ?/?`) ama sayısal okunur ✅ |
| `ysvDosyaNo` | 290 farklı numara, her biri **2 satırda** (MENKUL + GAYRIMENKUL); max 10 karakter ✅ |
| Gruplama | 290 grup × 2 satır; hiçbir grupta mükerrer menkul tipi yok, grup içi dosya no tek ✅ |
| `gecmisAyIadeTutari` | Kolon yok → `NULL` → payload'a girmez ✅ |

Dikkat edilecekler:
- **Mükerrer `ysvDosyaNo` kontrolü kırılgan.** `existsBySbmFileNo` her satır için DB'ye
  bakar; aynı dosyadaki ikinci satır sadece `saveAll` döngüden **sonra** çağrıldığı için
  (arada flush olmadığı için) kabul ediliyor. Birisi `saveAll`'ı döngü içine alırsa
  dosyanın yarısı reddedilir. Bu davranışı koruyan bir test yok → test eklenmeli.
- **Yuvarlama.** `odenecekVergi` 45726.248 → 45726.25 (scale 2, HALF_UP). DB kolonu
  `NUMBER(15,2)` olduğu için kaçınılmaz; SBM'nin `vergiPrimTutari × vergiOrani` çapraz
  kontrolü yapmadığı ilk POST'ta teyit edilmeli.
- **Süre.** 580 satır = 290 grup = 290 token + 290 ESB çağrısı, tek senkron HTTP isteği
  içinde sırayla. Read timeout 60s **çağrı başına**; toplam süre gateway timeout'unu
  aşabilir. İlk canlı çalıştırma `cityCode` veya `processIds` ile parçalı yapılmalı.

### 4.5 Helm chart iskeleti — ✅ accounting deseniyle uyumlu
`Chart.yaml` (springboot-deployment 1.x.x, OCI repo), `common-configmap.yaml`/`configmap.yaml`
`.Files.Glob` deseni, `secret.yaml` `AsSecrets`, `_helpers.tpl` `allianz.bundlename`,
probe'lar `/sbm-declaration-services/actuator/health/*` — hepsi yerinde.
Eksikler yukarıdaki 4.3'te.

---

## 5. Güncelleme akışı — neden yanlış (haklısınız)

Gövde formatı doğru (SC-UAT'ta `successCount: 1` ile doğrulandı): PUT'ta
`sigortaSirketKodu + sonOdemeTarihi + ysvDosyaNo + ysvTutarList`, `ay/yil/ilKodu/ilceKodu` yok.
Sorun **gövdede değil, akışın kendisinde**:

### 5.1 Güncellenecek veriyi sisteme sokacak yol yok
DB'ye veri yazan tek uç Excel yükleme; o da `ysvDosyaNo` DB'de varsa satırı **reddediyor**.
Yani düzeltilmiş bir Excel yüklenemiyor. `/update` ise DB'deki (eski) tutarları SBM'ye
tekrar gönderiyor. Sonuç: **güncelleme fiilen aynı veriyi yeniden göndermekten ibaret.**
Gerçek bir güncelleme ancak DB'de elle UPDATE ile mümkün — ki prod'da firma politikası
gereği yasak.

### 5.2 SBM'de zaten var olan beyanname güncellenemiyor
SC-UAT testinde yaşanan gerçek durum: Temmuz verisi daha önce SOAP ile gönderilmiş →
POST → `RISK-HAVUZU-00004` (mükerrer beyanname) → satır `ERROR`.
Artık o satır:
- `UPDATABLE = {SENT, COMPLETED}` olduğu için **PUT edilemez**,
- `SENDABLE = {NEW, ERROR}` olduğu için sadece **tekrar POST edilebilir** → sonsuza kadar
  aynı `RISK-HAVUZU-00004`.

SOAP'tan REST'e geçişte tüm mevcut beyannameler bu duruma düşer. Bu hâliyle canlıda
**geçmiş dönem hiçbir beyanname güncellenemez.**

### 5.3 Başarısız PUT, satırı yeniden POST'lanabilir hâle getiriyor
`markError` her hatada `STATUS=ERROR` yazıyor. PUT hatası (ör. geçici 5xx, `SEC-00004`
IP problemi) sonrası satır `ERROR` → bir sonraki `send` batch'i onu alıp **POST** eder.
SBM'de kayıt zaten var → `RISK-HAVUZU-00004`, kötü senaryoda mükerrer kayıt.

### 5.4 Başarılı PUT, `COMPLETED` durumunu `SENT`'e düşürüyor
`markSent` işlem tipine bakmadan `SENT` yazıyor; SBM sorgusuyla doğrulanmış
(`COMPLETED`) bir beyanname güncellendiğinde doğrulama bilgisi kayboluyor.

### 5.5 `cancel` DB'de iz bırakmıyor
SBM'de tutarlar sıfırlanıyor ama `ALZ_SBM_DECL_PROCESS`'teki tutarlar aynı kalıyor ve
durum `SENT` oluyor. Sonraki bir `/update` iptal edilmiş beyannamenin **canlı tutarlarını
geri gönderir**. `CANCELLED` durumu veya tutarların DB'de de sıfırlanması gerekir.

### 5.6 Önerilen minimum düzeltme seti
1. `ERROR`'ı ikiye ayır: `SEND_ERROR` (POST edilebilir) ve `UPDATE_ERROR` (PUT edilebilir);
   `markError` işlem tipine göre yazsın. En küçük hâli: PUT hatasında durum `SENT`'te
   kalsın, sadece `ERROR_DETAILS` yazılsın.
2. `RISK-HAVUZU-00004` alan satırlar otomatik olarak "SBM'de mevcut" kabul edilip
   `SENT`'e alınsın (audit log'a gerekçesiyle) — böylece SOAP'tan devralınan beyannameler
   PUT ile yönetilebilir.
3. `markSent`: PUT başarılıysa `COMPLETED` olan satır `COMPLETED` kalsın.
4. `cancel`: DB tutarlarını da sıfırla ve `CANCELLED` durumu ekle (`UPDATABLE`'a dahil).
5. Veri güncelleme yolu aç: ya "aynı `ysvDosyaNo` varsa `NEW`/`ERROR` satırı güncelle"
   modlu yükleme (`?mode=upsert`), ya da satır tutarlarını değiştiren ayrı bir PUT ucu.
   Her iki hâlde de değişiklik `ALZ_SBM_DECL_LOG`'a yazılmalı (manuel script yasağının
   karşılığı budur).

---

## 6. Canlı öncesi yapılacaklar listesi

**P1 — bunlar olmadan deploy edilmemeli**
- [x] `DeclarationLogService.java:25` derleme hatasını düzelt (§0.1) — bu olmadan hiçbir şey test edilemez
- [x] `Dockerfile`: `RUN addgroup/adduser` + `USER allianz` kaldırıldı (rootless UBI9 zaten non-root); `HEALTHCHECK` kaldırıldı (k8s probe'ları `chart/values.yaml`'da, UBI9'da `wget` yok)
- [x] `feature/SBMD-13` kapatıldı; geliştirme `main` üzerinden sürüyor
- [x] Placeholder adları Vault export adlarıyla hizalandı: `${TOKEN_MANAGEMENT_CLIENT_NAME}` / `${TOKEN_MANAGEMENT_USER_NAME}` / `${SBM_COMPANY_CODE}` (ortak + 4 ortam config'i)
- [x] `token-management.company-code` zaten export edilen `${SBM_COMPANY_CODE}`'a bağlandı → Vault şablonuna dokunmaya gerek kalmadı
- [x] `values/sc-uat.yaml` → `global.overrides.esb.server: http://10.70.47.135:21011`; diğer ortamların ESB adresini ESB ekibinden al
- [ ] Güncelleme akışı düzeltmeleri (§5.6/1-3) — en azından "başarısız PUT tekrar POST'lanmasın"
- [ ] Pod'un Vault'tan aldığı DB kullanıcısının `CUSTOMER.ALZ_SBM_*` yetkilerini doğrula

**P2**
- [x] `sc-test.yaml`/`sc-uat.yaml` girinti düzeltmesi (`sysType`, `autoscale`)
- [ ] `cancel` için DB tarafı (§5.6/4)
- [ ] Cevap zarfı fallback'i: `data` yoksa kök seviyeden oku
- [ ] `SWAGGER_API_KEY` export'unu kaldır; `bootstrap.yml`'i kaldır; `spring.management`/`datasource.opus|cognos` artıklarını temizle
- [x] `common-configs/application.yml`: `sbm.company-code` + `sbm.retry` geri eklendi
- [ ] `common-configs/application.yml`: hikari havuzu, Oracle dialect, `open-in-view: false`, jackson ayarları hâlâ eksik; `management` bloğu geçersiz `spring.management` altında
- [ ] **Karar gerekiyor:** token `base-url` ortak config'te `int-prep-auth`; ortam dosyaları bunu eziyor ama bir ortam dosyası yüklenmezse tüm ortamlar PREP auth'a gider. Ortak değer ya kaldırılmalı ya da doğru ortak adres yazılmalı
- [ ] `spring.profiles.active: dev` default'unu kaldır veya chart'ın `SPRING_PROFILES_ACTIVE` verdiğini doğrula

**P3**
- [ ] Aynı dosyada mükerrer `ysvDosyaNo` (2 satır) senaryosu için import testi
- [ ] `function-name`'i ortam yml'lerine açıkça yaz
- [ ] Büyük dosyada parçalı gönderim / gateway timeout ölçümü

---

## 7. Test senaryoları (verilen Excel ile)

Ön koşul: `SPRING_PROFILES_ACTIVE`, `ESB_SERVER`, DB ve token değişkenleri set.
Taban URL: `http://localhost:8080/sbm-declaration-services`

| # | Senaryo | Beklenen |
|---|---|---|
| T1 | `upload` — Temmuz 2026 dosyası | `insertedRows: 580`, `failures: []`, DB'de 580 `NEW`, `COMPANY_CODE=045`, 58 satırda `DISTRICT_CODE=0` |
| T2 | Aynı dosyayı tekrar `upload` | 580 satır da `ALZ-EXCEL-DUPLICATE` — hiçbiri insert edilmez |
| T3 | Dosyayı bozup (`ay` kolonunda iki farklı değer) yükle | HTTP 400, hiçbir satır yazılmaz |
| T4 | `send` `{"year":2026,"month":7,"cityCode":1}` | Tek grup → tek POST; payload'da `ilceKodu` **yok** (il 1 büyükşehir, `ilceKodu=0`), `ysvTutarList` 2 elemanlı |
| T5 | `send` `{"year":2026,"month":7,"cityCode":2}` | `ilceKodu` **var** (ör. 1105), 5 grup → 5 POST |
| T6 | Tipli gövde doğrulaması | `CORE-00005`/`CORE-00006` gelmemeli (SC-UAT'ta doğrulandı, canlıda tekrar) |
| T7 | `update` aynı filtreyle | PUT gövdesinde `ay/yil/ilKodu/ilceKodu` yok, 4 alan + liste |
| T8 | `query/{ysvDosyaNo}` | ESB GET route düzeldiyse `result:true`; satırlar `COMPLETED` |
| T9 | `cancel` `{"processIds":[..]}` | SBM'de tutarlar 0; **DB'de tutarların değişmediğini not et** (§5.5) |
| T10 | Token hata yolu: `userName`'i bozup `send` | `TokenException` → grup `ERROR`, `ERROR_DETAILS` dolu, SBM'ye hiçbir şey gitmemiş |
| T11 | Tam dosya `send` (290 grup) | Süre ölçülür; gateway timeout'a takılırsa parçalı gönderim kararı |

Her adımda kontrol edilecek: `ALZ_SBM_DECL_LOG`'da `REQUEST_PAYLOAD`/`RESPONSE_PAYLOAD`,
uygulama logunda `Transaction-Id` ve token `transactionId`, `Authorization` başlığının
hiçbir yere yazılmadığı.
