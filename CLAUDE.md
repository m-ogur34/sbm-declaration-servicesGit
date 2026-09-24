# CLAUDE.md — sbm-declaration-services

Bu dosya, projede çalışacak agent için bağlam ve kesinleşmiş kararları içerir.
Kod yazmadan önce bu dosyanın tamamını oku. Bir çelişki görürsen kod değiştirmeden
önce sor.

> **ÖNCELİK:** Uçtan uca çalışma prensibinin güncel ve ayrıntılı hâli
> **`Claude/CALISMA-PRENSIBI.md`**'dedir (gerçek SBM/ESB dökümanlarına göre yazıldı).
> Proje işleyişi ve açık maddeler için **`Claude/PROJE-REHBERI.md`**; uçların ne yaptığı ve
> şemalar için **`Claude/SISTEM-NASIL-CALISIR.md`**; PROD gönderimi için
> **`Claude/CANLI-GONDERIM-AKISI.md`**; ortam/profil/Vault/DB bağlantısı için
> **`README.md` §5**. SBM/token dokümanlarının asılları `SBM ve Allianz Dökümanları/`
> altındadır. Bu dosya (`CLAUDE.md`) ile onlar çelişirse **`Claude/CALISMA-PRENSIBI.md`
> geçerlidir**. 2026-08-30'da güncellenen kararlar aşağıda işaretlendi (⟳); 2026-09-21/22
> güncellemeleri (⟲); 2026-09-23/24 kararları §14'te.

---

## 1. Proje nedir

Yangın Sigorta Vergisi (YSV) beyanname verilerinin SBM'ye (Sigorta Bilgi ve
Gözetim Merkezi) REST üzerinden gönderilmesi, güncellenmesi ve sorgulanması.
Allianz Sigorta içi proje. Şirket kodu: **045**.

Üç repodan oluşan bir teslimatın **1. adımı** bu repo:

| Repo | İçerik | Durum |
|---|---|---|
| `sbm-declaration-services` | SBM'ye veri gönderen servis katmanı | **bu repo — aktif** |
| `sbm-declaration-ui-backend` | UI için backend | sonraki adım |
| `sbm-declaration-ui` | Angular 17 arayüz | sonraki adım |

### Uçtan uca akış

1. ⟳ OPUS'tan türetilen YSV verisi Excel olarak **`POST /api/v1/declarations/upload`**
   ucundan yüklenir; servis doğrulayıp `CUSTOMER.ALZ_SBM_DECL_PROCESS`'e `STATUS=NEW`
   ile insert eder. (Prod DB'de manuel script firma politikası gereği yasak — eski
   "uygulama insert yapmaz" kararı geçersiz. Bkz. `Claude/CALISMA-PRENSIBI.md` §3.)
2. Bu repodaki **gönder / güncelle / sorgu** API'leri tetiklenir.
3. Her çağrıda `alz-token-management` servisinden **taze token** alınır.
4. İstek **ESB (OSB 12c)** üzerinden SBM'ye çıkar.

---

## 2. Teknoloji

- ⟲ Spring Boot 3.5.14 / **Java 25** (`java.version` = `maven.compiler.release` = 25)
- ⟲ Runtime image: `harbor.allianz-tr.local/alz-base/redhat/ubi9-temurin-jdk25-rootless:u9.6-j25_36`
  — **rootless**: Dockerfile'a `USER` / `adduser` / `chown` satırı eklenmez, image içi
  `HEALTHCHECK` yoktur (sağlık kontrolü k8s probe'ları ile, `helm/chart/values.yaml`)
- HTTP istemcisi: **Spring `RestClient`** (RestTemplate/WebClient kullanma)
- Oracle DB
- Java paket kökü: `tr.com.allianz.ysv.services`
  alt paketler: `client`, `config`, `controller`, `dto`, `mapper`, `service`
- Build: **düz Maven** (`mvn`) — wrapper yok

---

## 3. Proje iskeleti — accounting-services ile birebir

Referans proje `accounting-services`. Repo kökü **sadece** şunları içerir:

```
.gitignore
Dockerfile
pom.xml
readme.md
helm/
src/
```

### Bunları sil / oluşturma

Bu dosyalar accounting yapısında **yoktur**, repoda varsa kaldır:

- ❌ `mvnw`, `mvnw.cmd`, `.mvn/` (Maven wrapper)
- ❌ `.editorconfig`
- ❌ `Jenkinsfile`
- ❌ `lombok.config`
- ❌ CI/CD, IDE, formatter ve benzeri ek konfig dosyaları

Build komutu bu yüzden `./mvnw clean verify` değil, **`mvn clean verify`**'dır.
Dökümantasyonda, readme'de ve script'lerde `mvnw` geçen yerleri düzelt.

### src yapısı

```
src/main/java/tr/com/allianz/ysv/services/...
src/main/resources/
  application.yml
  application-dev.yml
src/test/java/tr/com/allianz/ysv/services/...
```

Accounting'de test tarafında `<Uygulama>ApplicationTest` ve `BaseUnitTestConfig`
sınıfları var; aynı deseni koru.

---

## 4. pom.xml — accounting referansı

Aşağıdaki yapı birebir örnek alınacak. Sadece `artifactId`, `name`, `description`
ve projeye özgü bağımlılıklar değişir.

### Parent ve koordinatlar

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.14</version>
  <relativePath/>
</parent>
<groupId>tr.com.allianz</groupId>
<artifactId>sbm-declaration-services</artifactId>
<version>0.0.1-SNAPSHOT</version>
```

### properties

⟲ `java.version` 25, `spring-framework.version` 6.2.19, `spring-boot.version` 3.5.14,
`spring-cloud.version` 2025.0.0, `build-packaging-type` jar,
`maven.compiler.source/target/release` 25, `project.build.sourceEncoding` UTF-8,
`ojdbc.version` 19.3.0.0, `springdoc-openapi-starter-webmvc-ui.version` **2.8.9**
(2.6.0/2.7.0 Spring 6.2'de `/v3/api-docs` 500 veriyor), `prometheus-metrics-bom.version`
**1.4.3**, `jackson-bom.version` 2.22.2, `tomcat.version` 10.1.59, `logback.version` 1.5.35,
`micrometer.version` **1.16.7**, `jacoco-maven-plugin.version` **0.8.14** (plugin bu
property'yi kullanır).

### dependencyManagement — güvenlik zafiyeti ezmeleri

Accounting'de bunlar **bilinçli olarak** ezilmiş; aynen taşı:

- `spring-framework-bom` (import)
- `jackson-bom` (import)
- `spring-data-jpa` ve `spring-data-commons` → 3.5.12
- `micrometer-bom` (import) → 1.16.7
- `prometheus-metrics-bom` (import) → 1.4.3 — **spring-cloud importundan önce**. Yoksa
  spring-cloud-starter-parent, Boot 3.5.0 BOM'u üzerinden `prometheus-metrics-config`'i 1.3.6'ya
  çekiyor, uygulama açılışta `ExporterProperties.getPrometheusTimestampsInMs` hatası veriyor.
- `micrometer-registry-prometheus` için dependencyManagement'ta **sabit sürüm yazılmaz**
  (1.8.2 yazılıydı → Boot 3.5 prometheus ucunu oluşturmuyordu, `/actuator/prometheus` 404).
- `spring-cloud-starter-parent` (import, `spring-boot` exclusion'ı ile)
- `spring-cloud-dependencies` (import)

`stax-ex` 1.7.8 ezmesi SOAP/JAXB kaynaklı — bu projede SOAP yok, **ekleme**.

### dependencies

Gerekli olanlar:
`spring-boot-starter-web`, `spring-boot-starter-actuator`,
`spring-boot-starter-data-jpa`, `spring-boot-starter-validation`,
`lombok` (optional), `com.oracle.ojdbc:ojdbc8`, `com.oracle.ojdbc:orai18n`,
`springdoc-openapi-starter-webmvc-ui`, `tomcat-annotations-api` 9.0.86,
`bcprov-jdk18on` 1.84, `spring-boot-starter-test` (test scope,
`junit-vintage-engine` exclusion'ı ile).

⟳ `poi-ooxml` **eklendi** (`${poi.version}` = 5.3.0). 1. aşama Excel yükleme servisi
bunu kullanır. Sadece okuma; yazma yok.

### profiles

- `k8s-profile`: `spring-boot-maven-plugin` repackage + `distributionManagement`
  → `https://sdlc.allianz.com.tr/nexus/content/repositories/kubernetes-snapshot/`
- `local-development` (`activeByDefault`): `spring-cloud-starter-vault-config` 4.3.0

### build

- `<finalName>${project.artifactId}</finalName>`
- ⟲ `jacoco-maven-plugin` **0.8.14** (Java 25 için) → `prepare-agent`, `report` (test fazı),
  `check` (verify fazı)
- `maven-surefire-plugin` → `<argLine>@{argLine}</argLine>` (JaCoCo ile uyum için şart)

### JaCoCo eşiği

⟳ Firma politikası: birim test kapsamı **≥ %90**.

```
element: BUNDLE
excludes: *Test, *Application, dto/**, entity/**, *Properties, ErrorResponse, *MapperImpl
INSTRUCTION COVEREDRATIO >= 0.90
BRANCH      COVEREDRATIO >= 0.90
```

---

## 5. Token — `alz-token-management`

YSV'nin kendi token servisi **yoktur ve olmayacaktır**. Merkezi servis kullanılır.

- TEST endpoint:
  `POST https://int-sc-test-auth.allianz.com.tr/alz-token-management/api/v1/tokens/sbm-token-generate`
  (path `sbm-token-generate`, `sbm-generate-token` **değil**)
- Request alanları: `clientName` (= `ysv`), `transactionId` (her istekte yeni UUID),
  `functionName`, `userName`, `companyCode` (= `045`)
- ⟳ `functionName` **ortam bazlı config**: `token-management.function-name` (default
  `test`). ⟲ `userName` artık gönderilmez (bkz. §6 üstü, kimlik kararı). Operasyona göre değişmez; kod bunu `OperationType`'tan türetmez.
- Response: `accessToken` + `clientCredentials`
  - `clientIdentityType` → SBM header `Requester-ID-Type`
  - `clientIdNumber` → SBM header `Requester-ID-No`
  - Bu iki header **hardcode edilmez**, token cevabından gelir.
- **Cache YOK.** Her gönder/güncelle/sorgu çağrısında yeni token alınır.
  Cache zaten `alz-token-management` tarafında.
- İlgili sınıflar: `TokenManagementService`, `TokenManagementProperties`, `TokenManagementDto`

### Kritik konfigürasyon kuralı

⟲ **Güncellendi:** token tüm ortamlarda **aynı değişkenlerle** alınır. `path`,
`client-name`, `user-name`, `company-code` ve timeout'lar
`helm/chart/common-configs/application.yml` içindedir; ortama göre değişen tek alan
**`base-url`**'dir ve `helm/chart/configs/application-<ortam>.yml` içinde verilir.
Ortak config'de `base-url` için default tutulmaz (eksikse uygulama açılmasın).

⟲ **Placeholder adı = Vault'un export ettiği env adı.** Spring `${camelCase}` yazımını
`TOKEN_MANAGEMENT_CLIENT_NAME` env'iyle eşleştiremez; sadece aynı adı ve tamamen büyük
harfli hâlini dener. Bu yüzden yml'de `${TOKEN_MANAGEMENT_CLIENT_NAME}`,
`${TOKEN_MANAGEMENT_USER_NAME}`, `${TOKEN_MANAGEMENT_COMPANY_CODE}`, `${SBM_COMPANY_CODE}`
yazılır — `helm/values/<ortam>.yaml` içindeki `export` satırlarıyla birebir aynı.

⟲ `sbm.company-code` (SBM `sigortaSirketKodu` + DB `COMPANY_CODE`) ile
`token-management.company-code` (token isteği alanı) **ayrı alanlardır**; aynı Vault
anahtarından beslenseler de birleştirilmez.

### Kaldırılmış olması gereken eski yapı

`SbmTokenService` (userCode/password ile doğrudan SBM authenticate), `SbmTokenDto`,
`sbm.auth.*` konfigürasyonu ve 25 dakikalık cache — **obsolete**. Kodda kalıntı
varsa temizle.

---

### ⟲ İşlemi yapanın kimliği ve Transaction-Id (2026-09-22)

- İstek başlıkları: `X-User-Name` (DB'deki *_BY_USER), `X-Requester-Id-Type` + `X-Requester-Id-No`
  (işlemi yapanın kimliği). Üçü tek `RequestContext` nesnesinde; controller'larda başlık sabiti
  **tutulmaz** (`RequestContextArgumentResolver`).
- Kimlik geldiyse token isteğine `clientIdentityType` / `clientIdentityNo` olarak gider; token
  servisi aynen döner, SBM'ye `Requester-ID-*` olarak iletilir. **Gelmediyse token isteğine kimlik
  ve `userName` konmaz → token servisi şirket VKN'sini döner** (SBM Entegrasyon Dokümanı §5.1:
  toplu işlemde kurum VKN'si). Config'te sabit kişi (`token-management.user-name`) **yoktur**.
- Token cevabı hem `clientIdentityNo` (dokümandaki ad) hem `clientIdNumber` (ortamdaki eski ad)
  okunur (`@JsonAlias`).
- Her SBM çağrısı için tek UUID: token `transactionId` = SBM `Transaction-Id` başlığı; retry'da
  aynı kalır. `ALZ_SBM_DECL_LOG`'a yeni kolon **eklenmedi**; Transaction-Id ve maskeli kimlik
  `LOG_MESSAGE` içinde.

### ⟲ Cevap biçimi = SBM dokümanı (2026-09-22)

- Tüm cevaplar SBM zarfında: başarı `{result:true, status, data}`, hata
  `{result:false, status, error:{timestamp, reasons:[{field, code, message}]}}`; `status` = HTTP kodu.
- Tekli uçlar (`/{ysvDosyaNo}/send`, `PUT /{ysvDosyaNo}`, `GET /{ysvDosyaNo}`, `/{ysvDosyaNo}/cancel`)
  SBM'nin gövdesini **aynen** ve SBM'nin HTTP koduyla döner (POST 201, reddi 422 …). SBM yerine
  ESB hata sayfası gelirse 502 + SBM hata biçimi (HTML istemciye verilmez).
- Toplu uçlar: `data = {totalGroups, successCount, failCount, results[]}`; `results` elemanı
  `ysvDosyaNo` + o beyanname için SBM'nin cevabı. `result` = hiç hata yoksa `true`.
- Kendi hatalarımız: 400 `ALZ-VALIDATION`/`ALZ-REQUEST`, 404 `ALZ-NOT-FOUND`, 409
  `ALZ-STATUS-CONFLICT`, 422 SBM kodlu ön doğrulama, 503 `SEC-00001`, 500 `ALZ-INTERNAL`.
- İptal SBM'de kabul edilirse DB tutarları da 0'lanır. GET log'unda `REQUEST_PAYLOAD` = gerçekte
  giden `GET <url>?…`.

### ⟲ API (2026-09-22)

- Anahtar `ysvDosyaNo`; dış API'de iç `id` / `processIds` **yoktur**.
- Toplu (filtre `{year, month, cityCode, ysvDosyaNoList}`) ve tekli (`/{ysvDosyaNo}`) gönder,
  güncelle, sorgula, iptal. Tekli güncelleme = DB + (SBM'deyse) PUT, tek çağrı.
- Excel yükleme upsert'tür: anahtar `ysvDosyaNo + menkulTipi`. Ayrıntı README §4.

## 6. ESB

- Tek URL: `http://esb.allianz.com.tr:12000`
- ESB ortam bazlı yönlendirmeyi **kendisi** yapar; uygulama ortama göre farklı
  SBM adresi seçmez.
- **pom.xml'e ESB için hiçbir dependency eklenmez.** Uygulama ESB URL'ine
  doğrudan HTTP isteği atar. (`tr.com.allianz:ysv-services-rest-client` diye bir
  bağımlılık **yok**; eski notlarda geçtiyse yanlıştır.)
- ⟳ SBM path'i (ESB'nin arkası): gönder/güncelle/sorgu **aynı** path
  ESB **Proxy Service** path'i `/sbmDeclarationServices` (SC-UAT'ta doğrulandı; üç işlem
  de aynı path). Proxy SBM Business Service'e (`.../v10/ysv-beyanname`) yönlendirir.
  Host:port `ESB_SERVER` (SC-UAT = `10.70.47.135:21011`), path `esb.ysv.*-path`
  (default `/sbmDeclarationServices`, `common-configs/application.yml`).
  Sorgu: `GET` + query string `?sigortaSirketKodu=045&ysvDosyaNo=...` (SBM Postman
  örneği; gövde yok). ⟲ Proxy GET route'u 2026-09-22'de çalışır doğrulandı
  (önceden `CORE-00004`).
- ⟳ `tr.com.allianz:ysv-services-rest-client` **eklenmez** (karar sabit); ESB düz HTTP
  `RestClient` ile çağrılır.

---

## 7. SBM sözleşmesi — alan tipleri (EN KRİTİK BÖLÜM)

SBM dökümanının **alan tipi tablosu** esastır. Dökümandaki güncellenmiş örnek
JSON'da her değer tırnak içinde string olarak gösterilmiş — **bu örnek yanlıştır,
uyma.** Tipler SOAP WSDL stub'ları ve sorgu response örneğiyle de doğrulanmıştır.

| Alan | JSON tipi |
|---|---|
| `ay`, `ilKodu`, `ilceKodu`, `yil`, `vergiOrani` | **number** |
| `alinanPrimTutari`, `iptalPrimTutari`, `odenecekVergi`, `vergiPrimTutari`, `gecmisAyIadeTutari` | **decimal (number)** |
| `sigortaSirketKodu`, `ysvDosyaNo`, `menkulTipi` | **string** |
| `sonOdemeTarihi` | **string** (`yyyy-MM-dd`) |

Diğer kurallar:

- `menkulTipi`: kaynak veride `1`/`2`. SBM'ye **`"MENKUL"` / `"GAYRIMENKUL"`**
  string olarak gönderilir. (1 = MENKUL, 2 = GAYRIMENKUL)
- `gecmisAyIadeTutari`: her `ysvTutarList` **elemanının içinde** yer alır, root'ta değil.
- `ilceKodu`: kaynak veride hiç boş gelmez. **`0` = beyanname büyükşehir (il)
  seviyesinde ödeniyor** demektir → bu durumda alan payload'a **hiç konmaz**
  (`0` gönderilmez). `@JsonInclude(NON_NULL)` bunu sağlar.
- POST (gönder) ile PUT (güncelle) ayrımı `@JsonInclude(NON_NULL)` ile yapılır.
- Silme işlemi yoktur; silme gerekirse tutarlar **0** olarak güncellenir.
- Hata cevapları: **HTTP 422**, gövdede `error.reasons[]`.
- ⟳ **Response zarfı her işlemde**: `{ "result": bool, "data": <...>, "status": int }`.
  POST → `data.ysvDosyaNo`; PUT → `data: true`; GET → `data: { beyanname +
  telefon/vkn/adres/unvan }`. Başarı = HTTP 2xx **ve** `result == true`. (Alanlar kök
  seviyede DEĞİL, `data` içinde.)

### Büyükşehir mantığı

`SbmMapper` içinde. İş birimi kaynak veriyi büyükşehir/ilçe kırılımına göre
zaten düzenleyerek veriyor. Bu yüzden **`BuyuksehirUtil` içindeki 30 ilin
hardcode listesi ve bloklayıcı validasyon gereksizdir** — veri olduğu gibi
gönderilir. İlgili SBM hataları bilgi amaçlı: `RISK-HAVUZU-00007`
(büyükşehirde ilçe gönderilemez), `RISK-HAVUZU-00008` (büyükşehir değilse ilçe
gönderilmelidir).

### Gruplama

`DeclarationGroupKey` = (**yıl, ay, ilKodu, ilceKodu**).
`ysvDosyaNo` anahtarın parçası **değildir**; grubun satırlarından okunur.

---

## 8. Helm

Referans yapı `accounting-services` ile birebir:

```
helm/
  chart/
    common-configs/application.yml      # ortak: logging, jpa, actuator, server
    configs/
      application-sc-test.yml
      application-sc-uat.yml
      application-prep.yml
      application-prod.yml
    templates/
      _helpers.tpl
      common-configmap.yaml
      configmap.yaml
      secret.yaml
    .helmignore
    Chart.yaml
    values.yaml
  values/
    sc-test.yaml
    sc-uat.yaml
    prep.yaml
    live.yaml
    dr.yaml
```

- `Chart.yaml`: apiVersion v2, `springboot-deployment` chart'ına bağımlı,
  repo `oci://harbor.allianz-tr.local/middleware`, versiyon `1.x.x`,
  condition `springboot-deployment.enabled`.
- `chart/values.yaml`: `global.repoName`, `global.bundleName`,
  `global.overrides` (içinde `esb.server`), podLogger image
  (`harbor.allianz-tr.local/allianz-release/pod-logger:v2`), `app.contextPath`,
  `app.image.repository.group: internal-release`,
  actuator liveness/readiness/startup probe'ları.
- `configmap.yaml` / `common-configmap.yaml`: `.Files.Glob` ile ilgili klasörü
  tarayıp ConfigMap'e basar (`{{ $path | trimPrefix "configs/" | indent 2 }}`).
- `secret.yaml`: `{{ (.Files.Glob "certs/*").AsSecrets | indent 2 }}`
- `_helpers.tpl`: `allianz.bundlename` tanımı (`global.overrides.bundleName`
  varsa onu, yoksa `global.bundleName`).

### DB bağlantısı — otomatik değil, iki parçalı

1. `helm/values/<ortam>.yaml` içinde `springboot-deployment.secretManager.vault`
   bloğu Vault'tan okuyup env değişkeni export eder:
   ```yaml
   secretManager:
     vault:
       secret: kv/data/UAT
       template: |
         {{ with secret "kv/data/UAT/data-source/<datasource-adi>" -}}
         export SPRING_DATASOURCE_USERNAME="{{ .Data.data.username }}"
         export SPRING_DATASOURCE_PASSWORD="{{ .Data.data.password }}"
         export SPRING_DATASOURCE_URL="{{ .Data.data.url }}"
         {{- end }}
   ```
2. `src/main/resources/application-dev.yml` içinde
   `spring.config.import: vault://` + `spring.cloud.vault.*`
   (host, port 443, scheme https, authentication: token, kv backend,
   `default-context: DEV/apps/<uygulama-adi>`).

Ayrıca `values/<ortam>.yaml` içinde ortam bazlı: `gateway.migration.mode: istio`,
`springboot.profile`, `sysType`, JVM args, cpu/memory limits,
`autoscale.minReplicas/maxReplicas`.

### application.yml (src/main/resources)

Accounting deseni: `server.port`, `server.servlet.context-path`,
`spring.application.name`, **`spring.profiles.active: dev`**, logging seviyeleri,
`app.cors.allowedOrigins` + `app.cors.active`.

---

## 9. Veritabanı

Script: `YSV-db-scripti.sql` (v2.1, sıfırdan CREATE, DROP içermez).
Şema: `CUSTOMER`, public synonym'lerle.

Tablolar:
- `ALZ_SBM_MUNICIPALITY` — belediye referans (CITY_CODE, DISTRICT_CODE, KEP_EMAIL, EMAIL, ...)
- `ALZ_SBM_DECL_PROCESS` — beyanname süreç tablosu
- `ALZ_SBM_DECL_LOG` — log tablosu

`ALZ_SBM_DECL_PROCESS` ↔ SBM alan eşlemesi:

| Kolon | SBM alanı |
|---|---|
| `DECLARATION_MONTH` | `ay` |
| `CITY_CODE` | `ilKodu` |
| `DISTRICT_CODE` | `ilceKodu` |
| `COMPANY_CODE` | `sigortaSirketKodu` |
| `PAYMENT_DATE` | `sonOdemeTarihi` |
| `DECLARATION_YEAR` | `yil` |
| `SBM_FILE_NO` | `ysvDosyaNo` |
| `RECEIVED_PREMIUM_AMOUNT` | `alinanPrimTutari` |
| `CANCELLED_PREMIUM_AMOUNT` | `iptalPrimTutari` |
| `MOVABLE_TYPE` (VARCHAR2) | `menkulTipi` |
| `PREV_MONTH_REFUND_AMOUNT` | `gecmisAyIadeTutari` |

Not: `MOVABLE_TYPE_ID NUMBER` → `MOVABLE_TYPE VARCHAR2` migration'ı yapılmıştır.

---

## 10. Loglama

Her gönder / güncelle / sorgu işlemi loglanır. `transactionId` log'larda izlenebilir
olmalı. SBM'den beklenmeyen hata gelirse response header'daki `Transaction-Id`
loglanmalı (SBM destek talebi için gerekiyor).

---

## 11. Derleme

- `mvn clean verify`
- Proje henüz **hiç derlenmedi**. İlk derleme Allianz VDI'da (Windows + IntelliJ +
  iç Nexus) yapılacak. MacBook'ta iç Nexus'a erişim olmadığı için bağımlılıklar
  inmeyebilir — bu beklenen bir durumdur.

---

## 12. Bilinen açık konular

Tam liste `Claude/CALISMA-PRENSIBI.md` §11'de. Öne çıkanlar:

- ⟳ Firma politikası: birim test kapsamı **≥ %90**; proje **PEN testine** girecek
  — uygulama içi `ApiGuardFilter` 2026-09-18'de **kaldırıldı**; rate limit / erişim kontrolü
  gateway katmanında (`Claude/CALISMA-PRENSIBI.md` §14).
- ⟳ DB scriptleri tüm ortamlara deploy edilecek → `db/rollback_db.sql` eklendi.
  Lokal test: `Lokal Test/` (Bruno koleksiyonu + 2015/01 test Excel'leri); PEN: `Pen Test/`
  (2014/02). Test verisi **geçmiş dönemlerdedir** — SBM TEST'te gerçek ayların yuvalarını
  kilitlememesi için (bkz. §14).
- `functionName` (default `test`) ve `userName` token ekibiyle (Hüseyin Dağ /
  Ömer Faruk Ceylan) teyit edilecek.
- SBM REST şifresi (`koc` kullanıcısı) ve TEST/PRE/PROD için IP whitelist talebi beklemede.
- Teknik tasarım dökümanı yeni token mimarisine göre güncellenecek.
- ~~Veri anomalisi (Edirne ilçeli / Mardin ilçesiz)~~ — **kapandı (2026-09-23):** Edirne
  büyükşehir değil (ilçeli doğru), Mardin büyükşehir (ilçesiz doğru). Ağustos 2026 Excel'inde
  ilçesiz iller tam olarak 30 büyükşehir.
- İş birimine sorulacak: dosya no'lar her ay yeni mi üretiliyor? Belediye başına sabitse
  sonraki ay yüklemesi "başka dönemde kayıtlı" hatası verir.

---

## 13. Tuzaklar — bunları yapma

- ❌ Maven wrapper (`mvnw`, `.mvn/`) ekleme veya koruma.
- ❌ yml'de `${tokenManagementClientName}` gibi camelCase placeholder yazma — çözülmez,
  uygulama açılmaz. Vault export adını birebir kullan.
- ❌ Dockerfile'a `USER` / `adduser` / `chown` / `HEALTHCHECK` ekleme — base image rootless
  UBI9; `addgroup`/`adduser`/`wget` yok.
- ❌ `sbm.company-code` ile `token-management.company-code`'u tek property'de birleştirme.
- ❌ Token isteğine config'ten sabit bir `userName` / kimlik koyma — kimlik istekten gelir, yoksa şirket VKN'si.
- ❌ Hata cevabına istisna mesajı / iç adres yazma (PEN). Ayrıntı yalnızca loga.
- ❌ helm `templates/*configmap.yaml`'da `range` gövdesini girintileme — 0. kolonda olmalı, yoksa ConfigMap geçersiz olur.
- ❌ `.editorconfig`, `Jenkinsfile`, `lombok.config` gibi ek dosyalar bırakma.
- ❌ Token cache'i ekleme.
- ❌ `Requester-ID-Type` / `Requester-ID-No` header'larını hardcode etme.
- ❌ `ilceKodu`'yu büyükşehir için `0` gönderme — alanı tamamen çıkar.
- ❌ ESB için pom'a dependency ekleme (`ysv-services-rest-client` dâhil).
- ❌ Ortama göre farklı SBM URL'i seçme — ESB tek URL, yönlendirmeyi kendi yapar.
- ❌ `menkulTipi`'ni SBM'ye sayısal gönderme (`MENKUL`/`GAYRIMENKUL` string). Not:
  Excel'den `1`/`2` gelirse `MovableType.fromExcel` ile dönüştürülür.
- ❌ RestTemplate / WebClient kullanma.
- ❌ SBM cevabındaki `ysvDosyaNo`/beyanname alanlarını kök seviyede okuma — `data` içinde.
- ⚠️ Alan tipleri: kod tipli JSON gönderir (sayısal alanlar tırnaksız); tırnaklı örnek
  JSON'a göre hepsini string yapma. VDI'da 422 gelirse ilgili alan tekil olarak string'e
  çevrilir (bkz. `Claude/CALISMA-PRENSIBI.md` §5.2, §11/1).

---

## 14. 2026-09-23/24 kararları

- **`POST /upload/validate`**: `upload` ile aynı kurallar (ortak `plan`), DB'ye yazmaz, SBM'ye
  gitmez, kilit almaz (`readOnly`). Plan DB'den okunan entity'leri **değiştirmez** (JPA
  dirty-checking ile istemsiz yazma olmasın); güncellemeleri yalnız `upload` uygular.
  Cevapta `insertedFileNos` + `updatedFileNos`.
- **Toplu uçlar** (`send/update/query/cancel`): `ysvDosyaNoList` **ya da** `year + month`
  zorunlu; `{}` → 400 `ALZ-VALIDATION` (`field: filter`). `{}` tüm dönemleri işliyordu.
- **`ysvDosyaNo` deseni** `^[A-Za-z0-9_-]+$` (`SbmMapper.YSV_DOSYA_NO_PATTERN`): tekli uçların
  path'i ve Excel satır doğrulaması aynı sabiti kullanır (SBM sorgu URL'ine parametre eklenemesin).
- **İl/ilçe düzeltme:** SBM'ye hiç ulaşmamış beyanname (`NEW` ya da `RISK-HAVUZU-00006..00009`
  ile `ERROR`) düzeltilmiş Excel ile yeni il/ilçeye taşınabilir; tüm menkul satırları aynı yeni
  il/ilçeyle dosyada olmalı, yeni yuva boş olmalı. Timeout/5xx sonrası `ERROR` hariç.
- **Tekli güncelleme:** `vergiPrimTutari` / `odenecekVergi` negatif olabilir (SBM izin veriyor;
  iptal > alınan). `alinanPrimTutari` / `iptalPrimTutari` ≥ 0 kalır.
- **`/processes` sort:** izinli alan listesi dışı → 400 (DB'ye gitmeden).
- **PROD'da Swagger kapalı** (`configs/application-prod.yml`).
- **Config:** k8s'te `-Dspring.config.location=/app-config/,/app-config-common/` classpath
  `application.yml`'i **okumaz**. k8s'te geçerli olması gereken her ayar
  `helm/chart/common-configs/application.yml`'de olmalı (`hibernate.jdbc.batch_size`,
  `order_inserts/updates`, `open-in-view: false` oraya taşındı). Global `jackson non_null`
  kaldırıldı; zarf ve SBM/token istekleri sınıf seviyesinde `@JsonInclude(NON_NULL)` taşır.
- **Excel yükleme performansı:** "başka dönemde kayıtlı" kontrolü satır başına değil, 1000'lik
  gruplarla tek sorgu (`findExistingFileNos`).
- **Bilinçli olarak yapılmayanlar** (kullanıcı kararı): `discard` ucu, Excel aralık kontrolleri
  (DB constraint → tüm dosya 500), `application-dr.yml`, güvenlik başlığı filtresi, multipart
  limitinin k8s'e taşınması. Tekrar önerilmez.
- **Test verisi geçmiş dönemlerde:** SBM'de silme yok, yuva ilk dosya no'ya kalıcı bağlanır;
  test verisi gerçek ayları kullanırsa (2026/08'de olduğu gibi) SBM TEST'te gerçek veri
  `RISK-HAVUZU-00004` alır. Lokal: 2015/01 (`TESTDEV…`), PEN: 2014/02 (`PENTEST25…`).

