# sbm-declaration-servicesss

Yangın Sigorta Vergisi (YSV) beyanname verilerini Allianz Oracle veritabanından okuyup
**ESB üzerinden SBM'ye (Sigorta Bilgi ve Gözetim Merkezi)** ileten Spring Boot 3.5 / Java 25
REST servisi.

| Öğe | Değer |
|---|---|
| Base package | `tr.com.allianz.ysv.services` |
| Main class | `DeclarationServiceApplication` |
| Java | 25 (`maven.compiler.release`) |
| Spring Boot | 3.5.14 |
| Build | Maven (wrapper yok → `mvn clean verify`) |
| Runtime image | `harbor.allianz-tr.local/alz-base/redhat/ubi9-temurin-jdk25-rootless:u9.6-j25_36` |
| HTTP client | Spring `RestClient` (Apache HttpClient 5 üzerinde) |
| DB | Oracle (`com.oracle.ojdbc:ojdbc8` 19.3.0.0 + `orai18n`) + Spring Data JPA |
| Context path | `/sbm-declaration-services` |

---

## 1. İş akışı

```
OPUS → (manuel SQL script) → CUSTOMER.ALZ_SBM_DECL_PROCESS
                                      │
                          [gönder / güncelle / iptal / sorgu API]
                                      │
              1) alz-token-management'tan TOKEN al (her çağrıda YENİ, cache YOK)
                                      │
              2) ESB (tek URL) üzerinden SBM REST API'ye istek
                                      │
              3) request + response → CUSTOMER.ALZ_SBM_DECL_LOG (yasal kanıt)
                                      │
              4) ALZ_SBM_DECL_PROCESS.STATUS güncelle
```

Uygulama Excel **okumaz**. Veri veritabanına manuel script ile girer (`db/sample_insert.sql`),
uygulama yalnızca tablodan okur.

### Durum makinesi

```
NEW ──(gönder)──▶ PROCESSING ──(SBM 200 + result:true)──▶ SENT ──(sorgu onaylar)──▶ COMPLETED
                       │
                       └──(hata / result:false)──▶ ERROR  (tekrar denenebilir)
```

- **Gönder** (`POST`) yalnızca `NEW` / `ERROR` kayıtları işler.
- **Güncelle** ve **iptal** (`PUT`) yalnızca `SENT` / `COMPLETED` kayıtları işler; başarılı
  güncelleme kaydı yeniden `SENT` yapar (SBM tarafı tekrar doğrulanana kadar).
- **Sorgu** (`GET`) SBM onayı verirse `SENT` kayıtları `COMPLETED` olur.
- Aynı grubun paralel işlenmesi `@Lock(PESSIMISTIC_WRITE)` ile engellenir; her grup kendi
  transaction'ında işlenir, böylece son grubun hatası SBM'nin kabul ettiği grupları geri
  almaz.

### Gruplama

SBM bir beyannameyi **İl - İlçe - Yıl - Ay** ile tanır ve aynı kombinasyon için ikinci bir
kayıt gönderilirse RISK-HAVUZU-00004 döner. Bu yüzden gruplama anahtarı tam olarak bu dört
alandır:

```
GROUP BY (DECLARATION_YEAR, DECLARATION_MONTH, CITY_CODE, ilceKodu)
   → tek request
   → gruptaki kayıtlar ysvTutarList elemanı olur (menkul tipi başına bir eleman)
```

- `ilceKodu` grup anahtarında **SBM'nin göreceği** değerdir: payload ile aynı
  normalizasyondan geçer, yani `0` ve `null` aynı anlama gelir ve tek grupta toplanır.
- **`ysvDosyaNo` grup anahtarında yer almaz.** Dosya numarasını sigorta şirketi serbestçe
  belirlediği için anahtara konsaydı tek bir yasal beyanname birden fazla isteğe bölünürdü.
  Dosya numarası grubun kayıtlarından okunur; grupta birden fazla farklı dosya numarası
  varsa ilki gönderilir ve uygulama log'una **WARNING** yazılır.
- Aynı grupta aynı `menkulTipi` iki kez varsa **istek atılmadan önce** RISK-HAVUZU-00005 ile
  hata verilir.

### İlçe kodu kuralı

Uygulamada büyükşehir listesi ve ilçe doğrulaması **yoktur**. Tek kural:

- `DISTRICT_CODE` **null veya 0** ise → `ilceKodu` alanı JSON gövdesine **hiç yazılmaz**
  (`null` + `@JsonInclude(NON_NULL)`). `"ilceKodu": 0` asla gönderilmez.
- Diğer tüm durumlarda DB'deki değer **olduğu gibi** gönderilir.

Kaynak Excel iş birimi tarafından büyükşehir ve bağlı ilçeler bazında düzenleniyor; hatalı
il/ilçe kombinasyonlarını SBM zaten `RISK-HAVUZU-00007` / `RISK-HAVUZU-00008` ile bildiriyor
ve bu cevap `ERROR_DETAILS`'e yazılıyor. Aynı kararı lokalde tekrar vermek, iş birimince
doğru kabul edilen kayıtları gereksiz yere bloklardı.

---

## 2. Allianz VDI Kurulumu

Proje Allianz VDI ortamında (Windows + IntelliJ + iç Nexus + Oracle + ESB) derlenir ve
çalışır. Bağımlılıklar iç Nexus üzerinden çözülür; projede Nexus'a özel bir artifact
bağımlılığı yoktur.

### 2.1 Repoyu klonla

```cmd
cd C:\dev\projects
git clone https://github.com/m-ogur34/sbm-declaration-services.git
cd sbm-declaration-services
```

### 2.2 IntelliJ IDEA — Java 25 SDK

1. `File > Project Structure > SDKs` → **Java 25** (Temurin/Oracle JDK 25) ekli olmalı.
2. `File > Project Structure > Project` → *SDK: 25*, *Language level: 25*.
3. `File > Settings > Build, Execution, Deployment > Build Tools > Maven > Runner`
   → *JRE: Project SDK (25)*, *VM Options:* `-Dfile.encoding=UTF-8`.
4. `File > Settings > Editor > File Encodings` → *Global*, *Project* ve *Properties Files*
   için **UTF-8**, "Transparent native-to-ascii conversion" işaretli.
5. `File > Settings > Build, Execution, Deployment > Compiler > Annotation Processors`
   → **Enable annotation processing** (Lombok + MapStruct için zorunlu).
6. Lombok plugin kurulu olmalı.

### 2.3 Nexus `settings.xml` doğrulaması

`%USERPROFILE%\.m2\settings.xml` dosyasının iç Nexus'a yönlendiğini doğrula:

```cmd
type %USERPROFILE%\.m2\settings.xml
```

İçinde şunlar bulunmalı:

- `<mirror>` → Allianz Nexus group repository URL'i (`*` veya `external:*` için)
- `<server>` → Nexus kullanıcı adı/şifresi (ya da token)

Doğrulama:

```cmd
mvn -B dependency:resolve
```

Bu komut hatasız biterse Nexus erişimi tamamdır. Projede iç Nexus'a özel bir artifact
bağımlılığı yoktur; tüm bağımlılıklar Maven Central'ın Nexus proxy'sinden çözülür.

> Proje Maven Wrapper kullanmaz; `mvn` PATH'teki yerel Maven kurulumunu çağırır ve
> bağımlılık çözümü doğrudan `%USERPROFILE%\.m2\settings.xml` içindeki mirror/server
> tanımlarına göre Nexus proxy'sinden yapılır.

### 2.4 Derleme ve test

```cmd
mvn clean verify
```

Bu komut derler, testleri çalıştırır, JaCoCo raporunu üretir ve kapsam eşiğini kontrol eder.
Rapor: `target/site/jacoco/index.html`.

### 2.5 Lokal çalıştırma (dev profili)

`dev` profili de Oracle kullanır (H2 yoktur). Bağlantı bilgileri ortam değişkenlerinden
okunur:

```cmd
set SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//opusuat-scan.allianz-tr.local:1521/OPSSCUAT
set SPRING_DATASOURCE_USERNAME=<kullanici>
set SPRING_DATASOURCE_PASSWORD=<sifre>
mvn spring-boot:run
```

- Swagger UI: <http://localhost:8080/sbm-declaration-services/swagger-ui.html>
- Health: <http://localhost:8080/sbm-declaration-services/actuator/health>

### 2.6 Veritabanı

```sql
-- 1) Şema (verilen script, değiştirilmedi)
@db/setup_db.sql
-- 2) Excel'den türetilmiş 50 satırlık örnek veri (25 grup)
@db/sample_insert.sql
```

---

## 3. Test kapsamı (coverage)

`jacoco-maven-plugin` `verify` fazında raporu üretir ve eşiği kontrol eder.
`haltOnFailure=true` olduğu için eşik altındaki build **kırılır**.

| Seviye | Sayaç | Minimum |
|---|---|---|
| BUNDLE | LINE | **%90** |
| BUNDLE | BRANCH | **%85** |

Kapsam dışı bırakılanlar:

- `DeclarationServiceApplication`
- `dto/**`
- `entity/**`
- `config/*Properties` (property sınıfları ve iç sınıfları)
- `exception/ErrorResponse`
- MapStruct'ın ürettiği `*MapperImpl`

Lombok'un ürettiği getter/setter/builder/equals/hashCode kodu `lombok.config` içindeki
`lombok.addLombokGeneratedAnnotation = true` sayesinde JaCoCo tarafından sayılmaz.

Testler DB gerektirmez: repository'ler Mockito ile, REST çağrıları
`MockRestServiceServer` ile taklit edilir. `@DataJpaTest` kullanılmaz.

> Not: `prepare-agent` hedefi `initialize` fazına bağlıdır. `test` fazına bağlansaydı Maven,
> aynı fazdaki surefire'ı yaşam döngüsü sırasına göre **önce** çalıştırabilir ve ajan
> yüklenmeden test koştuğu için kapsam 0 çıkardı. `report` ve `check` `verify` fazındadır.

---

## 4. REST API

Base path: `/api/v1/declarations`. Anahtar **`ysvDosyaNo`**'dur (Excel'de ve SBM'de olan
numara); iç `id` dış API'de kullanılmaz.

### İsteği başlatanın bilgileri (header)

| Header | Zorunlu | Anlamı |
|---|---|---|
| `X-User-Name` | Hayır | DB'deki `CREATED_BY_USER` / `SENT_BY_USER` / `UPDATED_BY_USER`. Yoksa `SYSTEM`. En fazla 100 karakter, kontrol karakteri içeremez |
| `X-Requester-Id-Type` | Hayır | İşlemi yapanın kimlik tipi: `1` T.C. Kimlik No, `2` VKN, `4` Yabancı Kimlik No |
| `X-Requester-Id-No` | Hayır | Kimlik numarası: TCKN/YKN 11, VKN 10 hane |

Kimlik ikilisi ya birlikte gelir ya hiç gelmez (aksi 400). **Geldiyse** token isteğine
`clientIdentityType` / `clientIdentityNo` olarak gider, token servisi aynen geri döner ve
SBM'ye `Requester-ID-Type` / `Requester-ID-No` başlığı olarak iletilir. **Gelmediyse** token
isteğine kimlik konmaz, token servisi `companyCode`'a göre **Allianz VKN'sini** döner — SBM
Entegrasyon Dokümanı §5.1'in toplu işlemler için tarif ettiği yol. Üçü de tek bir
`RequestContext` nesnesinde toplanır (`RequestContextArgumentResolver`).

> Uygulamada kimlik doğrulama yoktur; bu başlıkların güvenilir bir kaynaktan (gateway /
> UI backend) gelmesi beklenir. Uygulama yalnızca biçimi doğrular.

### Uçlar

| İşlem | Toplu (filtre gövdesi) | Tekli |
|---|---|---|
| Excel yükle (upsert) | `POST /upload` (multipart, `file`) | — |
| Gönder | `POST /send` | `POST /{ysvDosyaNo}/send` |
| Güncelle | `PUT /update` — DB'deki değerleri SBM'ye PUT eder | `PUT /{ysvDosyaNo}` — yeni tutarları DB'ye yazar, beyanname SBM'deyse aynı çağrıda PUT eder |
| Sorgula | `POST /query` — SBM'den doğrular, `COMPLETED` yapar | `GET /{ysvDosyaNo}` — SBM cevabını döner |
| İptal (tutarlar 0) | `POST /cancel` | `POST /{ysvDosyaNo}/cancel` |
| Listele | `GET /processes?status=&year=&month=&cityCode=&page=&size=&sort=` | — |

Filtre gövdesi (toplu uçlar):

```json
{ "year": 2026, "month": 8, "cityCode": 34, "ysvDosyaNoList": ["PENTEST260801"] }
```

`ysvDosyaNoList` doluysa diğer alanlar dikkate alınmaz (en fazla 1000 — Oracle `IN` sınırı).

Tekli güncelleme gövdesi — SBM'nin PUT gövdesiyle aynı yapıda:

```json
{
  "sonOdemeTarihi": "2026-09-20",
  "ysvTutarList": [
    { "menkulTipi": "MENKUL", "alinanPrimTutari": 2000000.00, "iptalPrimTutari": 50000.00,
      "odenecekVergi": 195000.00, "vergiOrani": 10, "vergiPrimTutari": 1950000.00 }
  ]
}
```

Listede olmayan menkul tipinin tutarları değişmez. Beyannamenin kimliğini kuran alanlar
(yıl, ay, il, ilçe, `ysvDosyaNo`) değiştirilemez; beyannamede olmayan bir menkul tipi
eklenemez. Cevap: `{ ysvDosyaNo, sentToSbm, success, errorCode, message, rows[] }`.

Toplu işlem sonucu:

```json
{ "totalGroups": 6, "successCount": 5, "failCount": 1,
  "failures": [ { "ysvDosyaNo": "...", "errorCode": "...", "message": "..." } ] }
```

### Excel yükleme (upsert)

Anahtar `ysvDosyaNo + menkulTipi`; bir dosya tek dönem içerir (değilse tüm dosya 400).

| Durum | Sonuç |
|---|---|
| Satır DB'de yok | `NEW` olarak eklenir |
| Satır DB'de var, değerler farklı | Güncellenir, öncesi/sonrası `ALZ_SBM_DECL_LOG`'a (`LOCAL_UPDATE`); `COMPLETED` → `SENT`. Dosya no cevaptaki `updatedFileNos`'a girer → SBM'ye taşımak için `PUT /update` gövdesinde `ysvDosyaNoList` olarak verilir |
| Satır DB'de var, değerler aynı | Dokunulmaz |
| İl/ilçe farklı, satır `PROCESSING`, dosya no başka dönemde, SBM'deki beyannameye yeni menkul tipi | Satır hatası (`ALZ-EXCEL-CONFLICT` / `ALZ-EXCEL-BUSY`) |
| Dosyada aynı anahtar iki kez | `ALZ-EXCEL-DUPLICATE` |

### Durum kuralları

| Olay | Sonuç |
|---|---|
| POST başarısız | `ERROR` — kayıt SBM'ye girmedi, yeniden gönderilebilir |
| POST `RISK-HAVUZU-00004` (mükerrer) | `SENT` — beyanname SBM'de zaten var (ör. eski SOAP'tan); PUT ile yönetilir |
| PUT başarısız | Satır **önceki durumunda kalır**, sadece `ERROR_DETAILS` yazılır — `ERROR` olsaydı "gönder" kaydı tekrar POST ederdi |
| `COMPLETED` satırın değeri değişti (tekli güncelleme / Excel) | `SENT` — SBM'deki veriyle artık aynı değil, yeniden doğrulanmalı |
| `PROCESSING` satır düzenlenmek istendi | 400 |

### İzlenebilirlik — `Transaction-Id`

Her SBM çağrısı için tek bir UUID üretilir; hem token isteğinin `transactionId`'si hem SBM'nin
`Transaction-Id` başlığı olarak gider (SBM Entegrasyon Dokümanı §5.2), tekrar denemelerde de
aynı kalır. SBM aynı değeri cevapta geri döner. `ALZ_SBM_DECL_LOG.LOG_MESSAGE` şunu içerir:

```
PUT PENTEST260801 başarılı (HTTP 200, Transaction-Id: 3f2a…, Requester: 1/12*******01, kullanıcı: muhammed.ogur)
```

TCKN/YKN maskelenir (ilk ve son iki hane), VKN olduğu gibi yazılır. Token servisi logu,
uygulama logu, DB ve SBM destek talebi tek numarayla eşleşir.

### Hata cevapları

Tüm hatalar tek biçimdedir: `{ "timestamp", "path", "code", "message", "details" }`.

| HTTP | `code` | Ne zaman |
|---|---|---|
| 400 | `ALZ-VALIDATION` | Alan/parametre/başlık doğrulaması (`details`'te alan bazlı mesajlar) |
| 400/405/413/415 | `ALZ-REQUEST` | Bozuk JSON, eksik parametre, yanlış metot, dosya >10MB, içerik tipi |
| 404 | `ALZ-NOT-FOUND` | Dosya no DB'de yok |
| 502 | SBM kodu (ör. `CORE-01001`) | SBM isteği reddetti |
| 503 | `SEC-00001` | Token alınamadı |
| 500 | `ALZ-INTERNAL` | Beklenmeyen hata — mesaj genel, `details` boş; ayrıntı yalnızca uygulama logunda |

İstemciye hiçbir durumda istisna mesajı, sınıf adı ya da iç adres dönülmez.

---

## 5. Profiller ve konfigürasyon

| Profil | Konum | Not |
|---|---|---|
| `dev` | `src/main/resources/application-dev.yml` | Lokal geliştirme, Oracle |
| `sc-test` | `helm/chart/configs/application-sc-test.yml` | |
| `sc-uat` | `helm/chart/configs/application-sc-uat.yml` | |
| `prep` | `helm/chart/configs/application-prep.yml` | |
| `prod` | `helm/chart/configs/application-prod.yml` | `live.yaml` ve `dr.yaml` kullanır |

**Ortak ayarlar** `helm/chart/common-configs/application.yml` içindedir: logging seviyeleri,
`spring.jpa`, actuator, `sbm.company-code`, `sbm.retry` ve **token isteğinin ortam bağımsız
parametreleri** (`path`, `client-name`, `user-name`, `company-code`, timeout'lar) — token
tüm ortamlarda aynı değişkenlerle alındığı için bunlar ortakta tutulur.

`configs/application-<env>.yml` dosyalarında ortama özgü olanlar bulunur:
`spring.application.name`, ESB base URL'i, log seviyesi ve **`token-management.base-url`**
(auth host'u ortama göre değişir). Ortak config'de base-url için bilerek default
tutulmaz — bir ortam dosyası yüklenmezse uygulama sessizce yanlış auth ortamına gitmek
yerine açılmaz.

`src/main/resources` altında sadece `application.yml` ve `application-dev.yml` vardır.

Veritabanı kullanıcı adı/şifre/URL **koda veya yml'e yazılmaz**: Vault template'i
`SPRING_DATASOURCE_URL / USERNAME / PASSWORD` ortam değişkenlerini export eder, Spring
bunları otomatik okur (`helm/values/<ortam>.yaml`).

```
SC-UAT : jdbc:oracle:thin:@//opusuat-scan.allianz-tr.local:1521/OPSSCUAT
SC-PREP: jdbc:oracle:thin:@//opusprep-scan.allianz-tr.local:1521/opusprep
SC-PROD: jdbc:oracle:thin:@//opusprod-scan.allianz-tr.local:1453/OPUSAUX
Çıkış IP (SBM whitelist): 195.87.49.10
```

### ESB ve token

Uygulama SBM adreslerine **doğrudan gitmez**; tüm istekler tek ESB URL'ine gider, ortam
yönlendirmesini ESB yapar.

**ESB entegrasyonu için Nexus'tan artifact çekilmiyor; ESB ortam URL'ine doğrudan
`RestClient` ile istek atılıyor (teyit edildi).** `pom.xml`'de ESB'ye ait hiçbir bağımlılık
yoktur.

```yaml
esb:
  base-url: ${ESB_SERVER:http://esb.allianz.com.tr:12000}
  ysv:
    # ESB (OSB) Proxy Service path'i — gönder/güncelle/sorgu ÜÇÜ DE aynı.
    # Proxy, SBM Business Service'ine (.../v10/ysv-beyanname) yönlendirir.
    beyanname-path: /sbmDeclarationServices   # EsbProperties default'u
    sorgu-path: /sbmDeclarationServices
sbm:
  company-code: ${SBM_COMPANY_CODE}
  retry:
    max-attempts: 2          # yalnızca SEC-00002 ve 5xx için
```

Sorgu `GET` + query string ile gider: `?sigortaSirketKodu=045&ysvDosyaNo=...` (gövde yok).
SC-UAT'ta doğrulanan proxy adresi: `ESB_SERVER=http://10.70.47.135:21011`.

ESB adresi **koda gömülü değildir**. `helm/values/<ortam>.yaml` içindeki
`global.overrides.esb.server` değeri pod'a `ESB_SERVER` ortam değişkeni olarak geçer ve
yukarıdaki `${ESB_SERVER:...}` ifadesi bunu okur. `esb.allianz.com.tr` için her ortamda DNS
kaydı bulunmayabildiğinden (legacy SOAP client pom'unda bu not ve UAT için `10.70.52.149`
IP'si var) adres IP ile override edilebilir.

#### Token parametreleri ortam bazlıdır

Token **tüm ortamlarda aynı değişkenlerle** alınır; ortama göre değişen tek şey auth
host'udur. Gizli değerler yml'e yazılmaz, Vault'tan ortam değişkeni olarak gelir.

```yaml
# common-configs/application.yml — ortam bağımsız
token-management:
  path: /alz-token-management/api/v1/tokens/sbm-token-generate
  client-name: ${TOKEN_MANAGEMENT_CLIENT_NAME}
  user-name: ${TOKEN_MANAGEMENT_USER_NAME}
  company-code: ${TOKEN_MANAGEMENT_COMPANY_CODE}
  connect-timeout: 30s
  read-timeout: 30s

# configs/application-<ortam>.yml — ortama özgü
token-management:
  base-url: https://int-sc-uat-auth.allianz.com.tr
```

`function-name` yml'de yazılı değildir; `TokenManagementProperties` içindeki default
`"test"` geçerlidir, gerekirse ortam config'inden ezilir.

> **Placeholder adı = Vault'un export ettiği ortam değişkeni adı.** Spring, `${camelCase}`
> yazımını `TOKEN_MANAGEMENT_CLIENT_NAME` gibi bir env adıyla eşleştiremez (yalnızca aynı
> adı ve tamamen büyük harfli hâlini dener). Bu yüzden placeholder'lar `values/<ortam>.yaml`
> içindeki `export` satırlarıyla **birebir aynı** yazılır.

| yml placeholder | Vault template export'u | Vault anahtarı |
|---|---|---|
| `${TOKEN_MANAGEMENT_CLIENT_NAME}` | `TOKEN_MANAGEMENT_CLIENT_NAME` | `tokenManagementClientName` |
| `${TOKEN_MANAGEMENT_USER_NAME}` | `TOKEN_MANAGEMENT_USER_NAME` | `tokenManagementUserName` |
| `${TOKEN_MANAGEMENT_COMPANY_CODE}` | `TOKEN_MANAGEMENT_COMPANY_CODE` | `tokenManagementCompanyCode` |
| `${SBM_COMPANY_CODE}` | `SBM_COMPANY_CODE` | `tokenManagementCompanyCode` |

`sbm.company-code` (SBM sözleşmesindeki `sigortaSirketKodu` ve DB'deki `COMPANY_CODE`) ile
`token-management.company-code` (token isteğinin alanı) **ayrı alanlardır**; bugün aynı
Vault anahtarından beslenseler de ayrı tutulur.

`TokenManagementProperties` sınıfı `@Validated` ve zorunlu alanları `@NotBlank`. Bir ortam
değişkeni eksikse **uygulama başlangıçta hata verir ve ayağa kalkmaz**; eksiklik ilk
beyanname gönderiminde değil, deploy anında görünür.

Her istekten önce yeni token alınır (**cache yoktur**). SBM'ye giden header'lar token
yanıtından üretilir:

| Header | Kaynak |
|---|---|
| `Authorization` | `Bearer <accessToken>` |
| `Requester-ID-Type` | `clientCredentials.clientIdentityType` |
| `Requester-ID-No` | `clientCredentials.clientIdNumber` |

`accessToken` ve `clientIdNumber` log'a **asla maskesiz** yazılmaz (ilk 10 karakter + `***`).
`Authorization` header'ı `ALZ_SBM_DECL_LOG` payload'ına yazılmaz.

---

## 6. SBM hata kodları

| Kod | Anlamı | Aksiyon |
|---|---|---|
| `RISK-HAVUZU-00002` | Başka şirket adına işlem yapılamaz | `sigortaSirketKodu` = 045 kontrolü |
| `RISK-HAVUZU-00003` | İlgili ay veri girişine kapalı | Retry etme, `ERROR` |
| `RISK-HAVUZU-00004` | Mükerrer beyanname (il-ilçe-yıl-ay) | Retry etme |
| `RISK-HAVUZU-00005` | Mükerrer menkul tipi | Gruplama hatası, gönderim öncesi yakalanır |
| `RISK-HAVUZU-00006` | İl bulunamadı | Veri hatası |
| `RISK-HAVUZU-00007` | Büyükşehirde ilçe gönderilemez | `ilceKodu` gönderilmez |
| `RISK-HAVUZU-00008` | Büyükşehir değilse ilçe gönderilmelidir | `ilceKodu` zorunlu |
| `RISK-HAVUZU-00009` | İlçe bulunamadı | Veri hatası |
| `SEC-00001` | Kimlik doğrulama bilgileri gönderilmelidir | Token boş |
| `SEC-00002` | Token geçersiz/süresi dolmuş | **1 kez** yeni token alıp retry |
| `SEC-00003` | Erişim izni yok | Retry etme |
| `SEC-00004` | IP doğrulanamadı | Retry etme, whitelist sorunu |
| `SEC-00005..08` | Header hataları | Retry etme |
| `CORE-00000` | Beklenmeyen hata | `Transaction-Id` ile SBM'ye destek talebi |
| `CORE-00001` | HTTP metodu desteklenmiyor | Endpoint/metot kontrolü |
| `CORE-00005/00006` | Format hatası | Veri hatası |
| `CORE-00009` | Kaynak bulunamadı | Endpoint yanlış |
| `CORE-01000` | Zorunlu alan boş | Veri hatası |
| `CORE-01001` | Kayıt bulunamadı | Sorguda normal |
| `CORE-01004/01008` | Değer aralığı / uzunluk hatası | Veri hatası |

`reasons[]` listesi birleştirilip `ALZ_SBM_DECL_PROCESS.ERROR_DETAILS` alanına yazılır
(2000 karakteri aşarsa kırpılır) ve kayıt `ERROR` olur. Yanıt header'ındaki
**`Transaction-Id`** her çağrıda loglanır — SBM destek talebinde bu isteniyor.

**Veri silme:** SBM'de silme yoktur. `POST /cancel` tüm tutar alanlarını `0` yaparak `PUT`
gönderir.

---

## 7. Proje yapısı

```
src/main/java/tr/com/allianz/ysv/services/
├── DeclarationServiceApplication.java
├── config/         RestClientConfig, EsbProperties, TokenManagementProperties,
│                   SbmProperties, OpenApiConfig
├── controller/     DeclarationController
├── dto/            request/, response/, internal/
├── entity/         DeclarationProcess, DeclarationLog, Municipality
├── enums/          ProcessStatus, MovableType, OperationType, LogLevel, SbmErrorCode
├── exception/      SbmIntegrationException, TokenException, GlobalExceptionHandler,
│                   ErrorResponse
├── mapper/         SbmMapper, ProcessMapper
├── repository/     DeclarationProcessRepository, DeclarationLogRepository,
│                   MunicipalityRepository
├── service/        DeclarationService, DeclarationGroupProcessor, SbmClientService,
│                   TokenManagementService, DeclarationLogService
└── util/           DistrictCodeResolver, DateUtil, JsonUtil, MaskUtil

db/       setup_db.sql (değiştirilmedi), sample_insert.sql (50 satır)
docs/     api-examples.http
```

### Helm ağacı

```
helm/
├── chart/
│   ├── common-configs/application.yml
│   ├── configs/
│   │   ├── application-prep.yml
│   │   ├── application-prod.yml
│   │   ├── application-sc-test.yml
│   │   └── application-sc-uat.yml
│   ├── templates/
│   │   ├── _helpers.tpl
│   │   ├── common-configmap.yaml
│   │   ├── configmap.yaml
│   │   └── secret.yaml
│   ├── .helmignore
│   ├── Chart.yaml
│   └── values.yaml
└── values/
    ├── dr.yaml
    ├── live.yaml
    ├── prep.yaml
    ├── sc-test.yaml
    └── sc-uat.yaml
```

### Hangi dosya ne işe yarar

Bu repo **umbrella chart**'tır: Deployment / Service / Ingress / HPA şablonları bu repoda
**yoktur**, Allianz'ın ortak `springboot-deployment` chart'ından gelir
(`chart/Chart.yaml` → `oci://harbor.allianz-tr.local/middleware`, `1.x.x`). Bu repodaki
`templates/` yalnızca üç ConfigMap/Secret şablonu ile helper'ı içerir.

| Dosya | Ne yapar | Ne zaman değişir |
|---|---|---|
| `chart/Chart.yaml` | Parent chart bağımlılığı ve chart sürümü | Parent chart sürümü yükselince |
| `chart/values.yaml` | **Ortamdan bağımsız uygulama tanımı**: bundle/repo adı, context path, image repo grubu, actuator probe'ları, podLogger, ingress host'u | Uygulama genelinde bir şey değişince |
| `chart/common-configs/application.yml` | **Tüm ortamlarda geçerli Spring ayarları** → `<bundle>-common-config` ConfigMap'i | Ortak bir ayar değişince |
| `chart/configs/application-<ortam>.yml` | **Yalnızca o ortama özgü Spring ayarları** → hepsi tek `<bundle>-config` ConfigMap'ine yazılır, pod `SPRING_PROFILES_ACTIVE` ile kendine ait olanı okur | Ortama özel bir ayar değişince |
| `chart/templates/*.yaml` | `.Files.Glob` ile yukarıdaki iki klasörü ConfigMap'e basar; `certs/` varsa Secret üretir | Neredeyse hiç |
| `helm/values/<ortam>.yaml` | **Deploy anında `-f` ile verilen ortam dosyası**: profil, JVM args, CPU/memory, replica, Vault şablonu | Ortam kaynakları/gizli değerleri değişince |

Kural: bir ayar **tüm ortamlarda aynıysa** `common-configs/application.yml`'e yazılır.
Referans `accounting-services` projesinde de böyledir; orada
`configs/application-sc-uat.yml` yalnızca üç satırdır. Bizde de ortam dosyalarında sadece
`token-management.base-url` (auth host'u) ve log seviyesi vardır.

### `helm/values/<ortam>.yaml` içindeki bloklar

```yaml
springboot-deployment:        # bu anahtarın ALTINDAKİ her şey parent chart'a geçer
  gateway:
    migration:
      mode: istio             # servis mesh / gateway geçiş modu
  app:
    env:
      java:
        args: -Xms3g -Xmx3g   # JAVA_ARGS → Dockerfile ENTRYPOINT'inde kullanılır
      springboot:
        profile: sc-uat       # SPRING_PROFILES_ACTIVE → configs/application-sc-uat.yml
      sysType: SC-UAT         # Allianz platform etiketi (log/monitoring tarafı)
    resources: ...            # pod CPU/memory limit ve request'leri
    secretManager:
      vault:
        secret: kv/data/UAT   # Vault yolu
        template: |           # Vault agent bu şablonu render edip env değişkeni export eder
          export SPRING_DATASOURCE_URL=...
          export TOKEN_MANAGEMENT_CLIENT_NAME=...
  autoscale:                  # app'in ALTINDA DEĞİL — springboot-deployment seviyesinde
    minReplicas: 1
    maxReplicas: 1
```

`global:` bloğu **yalnızca `chart/values.yaml`'da** bulunur; ortam dosyalarında yoktur.
Helm'in yerleşik `global` mekanizmasıdır: hem bu chart hem de alt chart okur.

| `global` anahtarı | Anlamı |
|---|---|
| `repoName`, `bundleName` | Artifact adı ve ConfigMap/Secret adlarının öneki |
| `overrides.bundleName` | Bundle adını ezmek için (`_helpers.tpl` → `allianz.bundlename`) |
| `overrides.esb.server` | Pod'a `ESB_SERVER` ortam değişkeni olarak geçer ve `${ESB_SERVER:http://esb.allianz.com.tr:12000}` ifadesini ezer. **Normalde boş** — tüm ortamlar aynı ESB girişini kullanır; yalnızca DNS çözülmeyen bir ortamda IP vermek için |
| `overrides.chart.ingress.skipHostPrefix` | Ingress host'una ortam öneki (`int-`) eklenip eklenmeyeceği |

Vault yolları: `sc-test → kv/data/TEST`, `sc-uat → kv/data/UAT`, `prep → kv/data/PREP`,
`live` ve `dr → kv/data/PROD`. `live`/`dr` için `-Xms4g -Xmx4g`, memory limit `6Gi`,
`minReplicas: 1`, `maxReplicas: 5`.

> ⚠️ Klasör adının `chart` mı `charts` mı olduğu Jenkins pipeline ile teyit edilmeli.
> Bu repo `accounting-services` projesindeki gibi tekil `chart` kullanıyor.

## 7.1 Test ve PEN test paketi

| Dosya | İçerik |
|---|---|
| `PENTEST-REHBERI.md` | Ortam URL'leri, kimlik doğrulama modeli, tüm uçların curl'leri, negatif senaryolar, bilgi sızıntısı kontrolleri |
| `bruno-collection.json` | Bruno koleksiyonu (JSON) — gönder / güncelle / sorgula + 4 ortam (`sc-test`, `sc-uat`, `prep`, `prod`). Bruno'da *Import Collection → Bruno Collection* ile yüklenir |
| `pentest-ornek-beyanname.xlsx` | 12 geçerli satır / 6 beyanname grubu, dönem 2026-8 |
| `pentest-hatali-satirlar.xlsx` | Doğrulama yollarını tetikleyen hatalı satırlar |
| `db/cleanup_test_data.sql` | Test verisini temizleme (SC-TEST / SC-UAT) |

Ortam URL deseni: `https://<önek>elementer.allianz.com.tr/sbm-declaration-services`
(`int-sc-test-`, `int-sc-uat-`, `int-prep-`, `int-`).

---

## 8. Açık Konular

1. **Sorgu (GET) ESB proxy route'u.** Uygulama SBM sözleşmesine uygun şekilde `GET` +
   query string (`?sigortaSirketKodu=045&ysvDosyaNo=...`) gönderiyor; gövde yok
   (`esb.ysv.sorgu-method` property'si kaldırıldı). SC-UAT'ta OSB proxy'si GET'te bu
   parametreleri SBM Business Service'ine taşımadığı için `CORE-00004` alınıyor →
   **düzeltme ESB tarafında.** Not: SBM dökümanı sorgu isteğini gövdeli bir JSON örneğiyle
   de gösteriyor; proxy düzelince hangi varyantın beklendiği netleşecek.
2. **`gecmisAyIadeTutari`.** Güncel SBM dökümanında alan `ysvTutarList`'in her elemanında
   yer alıyor; kod da onu tutar kalemine (`SbmAmountItem`) koyuyor, root'a değil. DB'de
   değer varsa gönderiliyor, yoksa `@JsonInclude(NON_NULL)` ile payload'dan çıkarılıyor.
   Gerçek bir gönderimle uçtan uca doğrulanmadı.
3. **Token `functionName` değeri.** Uygulama artık operasyona göre isim türetmiyor;
   `token-management.function-name` ayarından okuyor, default `"test"` (SC-TEST/SC-UAT'ta
   çalıştığı doğrulandı). Token ekibi (Hüseyin Dağ / Ömer Faruk Ceylan) operasyona özel
   isim isterse ortam config'inden ezilir.
4. **alz-token-management parametreleri.** Token tüm ortamlarda aynı değişkenlerle
   alınıyor: `path`, `client-name`, `user-name`, `company-code` ve timeout'lar ortak
   config'te; ortama göre değişen tek alan `base-url` (her ortamın auth host'u).
   Gizli değerler Vault'tan ortam değişkeni olarak geliyor (bkz. §5 tablosu).
   `TokenManagementProperties` `@Validated` + `@NotBlank` olduğu için eksik ayarla uygulama
   **başlangıçta hata verir ve ayağa kalkmaz**; sorun ilk beyanname gönderiminde değil,
   deploy anında görünür.
5. **Alan tipleri: number mı string mi?** SBM dökümanının alan türü tablosu, PDF'deki istek
   örneği, sorgu yanıt örneği ve legacy SOAP WSDL stub'ları sayısal alanların JSON
   **number** olduğunu doğruluyor. Dökümanın güncellenmiş istek örneğinde değerler tırnaklı
   gösteriliyor; bu, tip tablosuyla çelişen bir örnek olarak değerlendirildi ve number
   tercih edildi. `CORE-00005` alınırsa çözüm: ilgili alanlara
   `@JsonFormat(shape = JsonFormat.Shape.STRING)` eklemek — DTO'lar tek noktada
   (`SbmDeclarationRequest`, `SbmAmountItem`) olduğu için tek satırlık bir değişiklik.
6. **İlçe kodu doğrulaması uygulamada yapılmıyor.** Kaynak Excel iş birimi tarafından
   düzenleniyor ve hatalı kayıtlar SBM'nin `RISK-HAVUZU-00007` / `RISK-HAVUZU-00008`
   hatalarıyla yakalanıyor. Uygulamada büyükşehir listesi tutulmuyor; tek kural
   `DISTRICT_CODE` null/0 ise alanın gönderilmemesi.
7. **ESB proxy path'i — çözüldü.** SC-UAT'ta doğrulandı (2026-09-01): üç işlem de
   `/sbmDeclarationServices` proxy path'ini kullanıyor, proxy SBM Business Service'ine
   (`.../v10/ysv-beyanname`) yönlendiriyor. `EsbProperties` default'u budur; ortam farkı
   yalnızca `ESB_SERVER` (host:port). SC-UAT = `http://10.70.47.135:21011`; diğer
   ortamların adresleri ESB ekibinden alınacak (`helm/values/<ortam>.yaml` içinde TODO).
8. **ESB DNS kaydı.** Legacy SOAP client pom'unda "esb.allianz.com.tr olarak bir dns kaydı
   bulunmamakta" notu ve UAT için `10.70.52.149` IP'si var. Adres bu yüzden
   `${ESB_SERVER:...}` üzerinden okunuyor ve `helm/values/<ortam>.yaml` içindeki
   `global.overrides.esb.server` ile IP olarak override edilebiliyor. Her ortamda hangi
   adresin geçerli olduğu ESB ekibinden alınmalı.
9. **`global.overrides.esb.server` → `ESB_SERVER` eşlemesi — DOĞRULANMAMIŞ VARSAYIM.**
    Uygulama `esb.base-url` değerini `${ESB_SERVER:...}` ifadesiyle okuyor, yani pod'da
    `ESB_SERVER` adında bir ortam değişkeni bekliyor. `helm/chart/values.yaml` içindeki
    `global.overrides.esb.server` alanını Allianz `springboot-deployment` subchart'ının bu
    ortam değişkenine çevirdiği **varsayıldı**; subchart `oci://harbor.allianz-tr.local/middleware`
    üzerinden geldiği ve bu repoda kaynağı bulunmadığı için sözleşmesi görülemedi.
    Middleware ekibiyle doğrulanmalı.

    Varsayım tutmazsa uygulama kodu değişmez, yalnızca `helm/values/<ortam>.yaml`
    değişir: değişken `app.env` altında doğrudan tanımlanır. Her values dosyasında bunun
    yorum satırı hâlinde örneği duruyor:

    ```yaml
    springboot-deployment:
      app:
        env:
          # Anahtar adı subchart sözleşmesine göre custom / variables / extraEnv olabilir.
          custom:
            - name: ESB_SERVER
              value: http://10.70.52.149:12000
    ```

    Bu durumda `global.overrides.esb.server` alanı kullanılmadan kalır; iki yöntem aynı anda
    kullanılmamalı.
