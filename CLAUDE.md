# CLAUDE.md — sbm-declaration-services

Bu dosya, projede çalışacak agent için bağlam ve kesinleşmiş kararları içerir.
Kod yazmadan önce bu dosyanın tamamını oku. Bir çelişki görürsen kod değiştirmeden
önce sor.

> **ÖNCELİK:** Uçtan uca çalışma prensibinin güncel ve ayrıntılı hâli
> **`CALISMA-PRENSIBI.md`**'dedir (gerçek SBM/ESB dökümanlarına göre yazıldı).
> Ortam/profil/Vault/DB bağlantısı için **`HELM-VE-KONFIG.md`**. Bu dosya (`CLAUDE.md`)
> ile onlar çelişirse **`CALISMA-PRENSIBI.md` geçerlidir**. 2026-08-30'da güncellenen
> kararlar aşağıda işaretlendi (⟳).

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
   "uygulama insert yapmaz" kararı geçersiz. Bkz. `CALISMA-PRENSIBI.md` §3.)
2. Bu repodaki **gönder / güncelle / sorgu** API'leri tetiklenir.
3. Her çağrıda `alz-token-management` servisinden **taze token** alınır.
4. İstek **ESB (OSB 12c)** üzerinden SBM'ye çıkar.

---

## 2. Teknoloji

- Spring Boot 3.5.x / **Java 21**
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

`java.version` 21, `spring-framework.version` 6.2.19, `spring-boot.version` 3.5.14,
`spring-cloud.version` 2025.0.0, `build-packaging-type` jar,
`maven.compiler.source/target` 21, `project.build.sourceEncoding` UTF-8,
`ojdbc.version` 19.3.0.0, `springdoc-openapi-starter-webmvc-ui.version` 2.7.0,
`jackson-bom.version` 2.22.0, `tomcat.version` 10.1.56, `logback.version` 1.5.35,
`micrometer.version` 1.15.12.

### dependencyManagement — güvenlik zafiyeti ezmeleri

Accounting'de bunlar **bilinçli olarak** ezilmiş; aynen taşı:

- `spring-framework-bom` (import)
- `jackson-bom` (import)
- `spring-data-jpa` ve `spring-data-commons` → 3.5.12
- `micrometer-bom` (import) → 1.15.12
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
- `jacoco-maven-plugin` 0.8.11 → `prepare-agent`, `report` (test fazı),
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
  `test`). Operasyona göre değişmez; kod bunu `OperationType`'tan türetmez.
- Response: `accessToken` + `clientCredentials`
  - `clientIdentityType` → SBM header `Requester-ID-Type`
  - `clientIdNumber` → SBM header `Requester-ID-No`
  - Bu iki header **hardcode edilmez**, token cevabından gelir.
- **Cache YOK.** Her gönder/güncelle/sorgu çağrısında yeni token alınır.
  Cache zaten `alz-token-management` tarafında.
- İlgili sınıflar: `TokenManagementService`, `TokenManagementProperties`, `TokenManagementDto`

### Kritik konfigürasyon kuralı

`alz-token-management` request parametrelerinin **tamamı ortam bazlıdır**:
`base-url`, `path`, `clientName`, `functionName`, `userName`, `companyCode`.
Hiçbiri ortak/paylaşılan default olarak yazılmaz — hepsi
`helm/chart/configs/application-<ortam>.yml` içinde ayrı ayrı tanımlanır.

### Kaldırılmış olması gereken eski yapı

`SbmTokenService` (userCode/password ile doğrudan SBM authenticate), `SbmTokenDto`,
`sbm.auth.*` konfigürasyonu ve 25 dakikalık cache — **obsolete**. Kodda kalıntı
varsa temizle.

---

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
  örneği; gövde yok). Proxy GET route'u parametreleri taşımadığı sürece `CORE-00004` —
  düzeltme ESB tarafında.
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

Tam liste `CALISMA-PRENSIBI.md` §11'de. Öne çıkanlar:

- ⟳ Firma politikası: birim test kapsamı **≥ %90**; proje **PEN testine** girecek
  — uygulama içi `ApiGuardFilter` 2026-09-18'de **kaldırıldı**; rate limit / erişim kontrolü
  gateway katmanında (`CALISMA-PRENSIBI.md` §14).
- ⟳ DB scriptleri tüm ortamlara deploy edilecek → `db/rollback_db.sql` eklendi.
  Lokal test için `db/local/*` + `db/sample_data_scenarios.sql` + `application-local.yml`.
- `functionName` (default `test`) ve `userName` token ekibiyle (Hüseyin Dağ /
  Ömer Faruk Ceylan) teyit edilecek.
- SBM REST şifresi (`koc` kullanıcısı) ve TEST/PRE/PROD için IP whitelist talebi beklemede.
- Teknik tasarım dökümanı yeni token mimarisine göre güncellenecek.
- Veri anomalisi (iş birimine sorulacak): 14 satır büyükşehir olmasına rağmen ilçe
  kodlu (il 22 Edirne), 2 satır büyükşehir olmadığı halde ilçe kodu 0 (il 47 Mardin).

---

## 13. Tuzaklar — bunları yapma

- ❌ Maven wrapper (`mvnw`, `.mvn/`) ekleme veya koruma.
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
  çevrilir (bkz. `CALISMA-PRENSIBI.md` §5.2, §11/1).
