# TEST-PLAN — YSV → SBM entegrasyonu

> VDI'da adım adım işaretlenecek çalışma listesi. Referans: `CALISMA-PRENSIBI.md`,
> `HELM-VE-KONFIG.md`. Durum: `[ ]` yapılacak · `[x]` tamam · `[!]` sorun/beklemede.

---

## Durum özeti (2026-09-02, SC-UAT lokal smoke)

| Aşama | Sonuç |
|---|---|
| Derleme + testler (VDI) | ✅ BUILD SUCCESS, JaCoCo geçti |
| Uygulama (local profil, SC-UAT DB) | ✅ ayağa kalkıyor, `health = UP` |
| 1. Excel → DB | ✅ 580 satır, `COMPANY_CODE=045`, seri tarih→LocalDate, `menkulTipi` 1/2→metin |
| 2. Token | ✅ SC-TEST auth'tan, `functionName=test`, maskeli log |
| 3. Gönder (POST) | ✅ ESB→SBM zinciri çalışıyor. SBM `RISK-HAVUZU-00004` (Temmuz verisi zaten SOAP ile gönderilmiş). **Tipli JSON kabul ediliyor** — CORE-00005/00006 yok. |
| 3. Güncelle (PUT) | ✅ `successCount: 1`. PUT gövdesinde `ay/yil/ilKodu/ilceKodu` yok; `sigortaSirketKodu + sonOdemeTarihi + ysvDosyaNo + ysvTutarList` (7 alanlı) var. |
| 3. İptal (cancel) | ⏳ test edilmedi |
| 3. Sorgu (GET) | `[!]` ESB **Proxy Service GET route'u** `sigortaSirketKodu`/`ysvDosyaNo`'yu SBM'ye taşımıyor → `CORE-00004`. Business Service query-string ile çalışıyor (OSB test konsolunda doğrulandı). **ESB tarafında düzeltilecek.** Uygulama + parse hazır. |

**Gerçek ESB adresi (SC-UAT):** `ESB_SERVER=http://10.70.47.135:21011`, proxy path
`/sbmDeclarationServices` (üç işlem de). Business Service → `https://testrs.sbm.org.tr/...`
(SBM ŞİRKET TEST). Proxy path artık `common-configs/application.yml` + `EsbProperties`
default'unda — sadece `ESB_SERVER` env gerekiyor.

**Çalıştırma (IntelliJ Run Config → Environment variables):**
```
SPRING_PROFILES_ACTIVE=local
LOCAL_DB_URL=jdbc:oracle:thin:@//opusuat-scan.allianz-tr.local:1521/OPSSCUAT
LOCAL_DB_USER=AZSDB_40895
LOCAL_DB_PASSWORD=<SC-UAT şifre>
ESB_SERVER=http://10.70.47.135:21011
```
(`ESB_YSV_BEYANNAME_PATH` / `ESB_YSV_SORGU_PATH` artık gerekmiyor — default `/sbmDeclarationServices`.)

---

## 0. Ön koşullar

- [x] VDI'da repo güncel: `git checkout feature/SBMD-13 && git pull`
- [x] Java 21 + Maven (düz `mvn`)
- [x] İç Nexus erişimi

---

## 1. Derleme (VDI, iç Nexus)

- [x] `mvn clean verify`
  - [x] Bağımlılıklar indi (`poi-ooxml:5.3.0` dahil)
  - [x] testler yeşil
  - [x] JaCoCo `check` geçti (INSTRUCTION/BRANCH ≥ %90)
- [x] İlk çalıştırmada iki runtime uyumsuzluğu çıktı ve düzeltildi:
  - `io.prometheus:prometheus-metrics-bom:1.3.10` eklendi (`ExpositionFormats` NoSuchMethodError)
  - `springdoc-openapi 2.6.0 → 2.8.9` (Spring 6.2'de kaldırılan `ControllerAdviceBean(Object)` → `/v3/api-docs` 500)

**Sonuç:** ✅ BUILD SUCCESS

---

## 2. Veritabanı

### 2a. Lokal Oracle — atlandı (SC-UAT kullanıldı)

### 2b. SC-UAT
- [x] Tablolar SC-UAT'ta mevcut (`ALZ_SBM_DECL_PROCESS`, `_LOG`, public synonym'lerle)
- [ ] `db/rollback_db.sql` elde (diğer ortam deploy'ları için)

**Sonuç:** ✅ (SC-UAT hazır)

---

## 3. Uygulama ayağa kaldırma

### 3a. Lokal profil (SC-UAT DB'ye)
- [x] IntelliJ Run Config, `SPRING_PROFILES_ACTIVE=local` + `LOCAL_DB_*` + `ESB_SERVER`
- [x] `The following 1 profile is active: "local"` (dikkat: **küçük harf** `local`)
- [x] `HikariPool-1 - Start completed`, `Tomcat started on port 8080`
- [x] `GET /actuator/health` → `{"status":"UP"}`
- [x] Swagger: `http://localhost:8080/sbm-declaration-services/swagger-ui.html`

### 3b. k8s (SC-TEST/UAT) — `HELM-VE-KONFIG.md` §5
- [ ] `helm dependency update ./helm/chart`
- [ ] deploy + `kubectl exec ... env | grep SPRING_PROFILES_ACTIVE / SPRING_DATASOURCE_`
- [ ] `helm/values/<ortam>.yaml` → `ESB_SERVER` (SC-UAT için `http://10.70.47.135:21011`)
- [ ] `API_GUARD_API_KEY` secret'ı
- [ ] liveness/readiness 200

**Sonuç:** ✅ 3a tamam · ⏳ 3b bekliyor

---

## 4. 1. AŞAMA — Excel yükleme

- [x] `POST /api/v1/declarations/upload` — `sbm_tsb ... Temmuz 2026.xlsx` (580 satır)
  - [x] Yanıt: `{ totalRows: 580, inserted: 580, failed: 0, errors: [] }`
  - [x] DB: `NEW/MENKUL/290`, `NEW/GAYRIMENKUL/290`; `COMPANY_CODE='045'` (Excel 2320 değil)
  - [x] `PAYMENT_DATE = 2026-08-20` (Excel seri `46254`)
  - [x] `MOVABLE_TYPE` sadece `MENKUL`/`GAYRIMENKUL` (Excel'de `1`/`2`)
  - [x] `SOURCE_FILE_NAME`, `CREATED_BY_USER` dolu
- [!] Not: Excel başlığında `alinanPrimTutari1`/`iptalPrimTutari1` yazım hatası vardı;
      Excel düzeltilerek çözüldü (kod tarafı toleransı bilinçli olarak eklenmedi).
- [ ] Diğer senaryolar (bozuk satır, mükerrer dosya, çift dönem, `.csv`) — birim testlerinde var, uçtan uca test edilmedi

**Sonuç:** ✅

---

## 5. 2. AŞAMA — Token

- [x] `send`/`update` tetiklendiğinde: `Requesting SBM token: transactionId=..., functionName=test, operation=...`
- [x] Her çağrıda yeni `transactionId`
- [x] `SBM token acquired: ... accessToken=eyJhbGciOi***, clientIdNumber=8677399731***` (maskeli)
- [x] `clientCredentials.clientIdentityType`/`clientIdNumber` alınıyor → `Requester-ID-*` header'larına yazılıyor

**Sonuç:** ✅

---

## 6. 3. + 4. AŞAMA — SBM gönder/güncelle/sorgu (ESB üzerinden)

### 6a. Gönder (POST)
- [x] `POST /api/v1/declarations/send` body `{ "year":2026, "month":7, "cityCode":1 }`
- [x] Header'lar: `Authorization: Bearer`, `Requester-ID-Type: 1`, `Requester-ID-No`
- [x] **Tipli JSON** — `{"ay":7,"ilKodu":1,"yil":2026,...}` (sayısal alanlar tırnaksız). SBM
      `CORE-00005/00006` **vermedi** → tipli gövde kabul ediliyor. §11/1 kapandı.
- [x] Büyükşehir (il=1, ilçe=0) → payload'da `ilceKodu` **yok**
- [x] `ysvTutarList` 2 eleman (MENKUL + GAYRIMENKUL)
- [x] SBM cevabı: `RISK-HAVUZU-00004 Mükerrer beyanname` (Temmuz verisi zaten SBM'de) →
      kod `error.reasons[]` zarfını çözdü, satırlar `ERROR` + `ERROR_DETAILS`, log `RES` dolu
- [ ] Temiz `successCount:1` — SBM'de olmayan bir beyanname ile denenmedi

### 6b. Güncelle (PUT) / İptal
- [x] `PUT /api/v1/declarations/update` body `{ "year":2026, "month":7, "cityCode":1 }` →
      `{ totalGroups:1, successCount:1, failCount:0 }`. (Önce satırlar elle `SENT` yapıldı.)
- [x] PUT gövdesinde `ay/yil/ilKodu/ilceKodu` **yok**; `sigortaSirketKodu`, `sonOdemeTarihi`,
      `ysvDosyaNo`, `ysvTutarList` (her elemanda 7 tutar alanı) var — dökümanın alan
      tablosundaki "POST/PUT" alanlarının tamamı
- [ ] `POST /api/v1/declarations/cancel` — test edilmedi

### 6c. Sorgu (GET)
- [!] `GET /api/v1/declarations/query/YSV2027486` → SBM `CORE-00004 [sigortaSirketKodu] zorunlu`
- Denenenler: (1) `GET` + query string, (2) `GET` + JSON gövde — ikisinde de aynı hata
- **Neden:** ESB **Proxy Service** (`/sbmDeclarationServices`) GET route'u `sigortaSirketKodu`
  ve `ysvDosyaNo`'yu SBM Business Service'e aktarmıyor. Business Service query-string ile
  çalışıyor (OSB test konsolunda `{"result":true,"data":{...}}` doğrulandı).
- **Aksiyon:** ESB ekibi proxy'nin GET akışına query-param aktarımı ekleyecek. Proxy gövde
  mi query string mi bekleyecek netleşince uygulama hizalanır (git'te iki varyant da mevcut).
- Uygulama tarafı hazır: `SbmQueryResponse` + `SbmQueryData` (telefon/vkn/adres/unvan dahil)
  gerçek SBM cevabıyla birebir.

### 6d. Hata / retry
- [x] SBM 4xx (`RISK-HAVUZU-00004`, `CORE-00004`) → `error.reasons[]` `ERROR_DETAILS`'e, retry yok
- [ ] `SEC-00002` / 5xx retry — canlı senaryo denenmedi
- [x] Beklenmeyen hatada `Transaction-Id` loglanıyor (POST akışında görüldü)

**Sonuç:** 6a ✅ · 6b ✅ · 6c `[!]` (ESB) · 6d kısmen ✅

---

## 7. PEN test savunma katmanı

Rate limit / API key uygulamada yok (ApiGuard 2026-09-18'de kaldırıldı; gateway katmanında).

- [ ] actuator: sadece `health/info/metrics/prometheus`

---

## 8. Entegrasyon smoke (VDI ağı)

- [x] Token servisi: `https://int-sc-test-auth.allianz.com.tr/...` → erişiliyor (token alındı)
- [x] ESB: `esb.allianz.com.tr:12000` **erişilemiyor** (timeout) · `10.70.52.149:12000` yanlış
      (connection reset) · **`10.70.47.135:21011` doğru** (SBM cevabı geldi)
- [x] `ESB_SERVER` ile IP override çalışıyor

**Sonuç:** ✅ (doğru adres: `http://10.70.47.135:21011`)

---

## Açık maddeler

- [x] ~~Tipli gövde 422 kontrolü~~ — SBM tipli JSON'u kabul etti (6a)
- [x] ~~ESB proxy path'i~~ — `/sbmDeclarationServices` (SC-UAT), config'e yazıldı
- [!] **ESB proxy sorgu (GET) route'u** — `sigortaSirketKodu`/`ysvDosyaNo` SBM'ye taşınmıyor. ESB ekibi.
- [ ] Token `functionName` — token ekibi (Hüseyin Dağ / Ömer Faruk Ceylan): `test` kalıcı mı?
- [ ] SBM REST şifresi (`koc`) + TEST/PRE/PROD IP whitelist
- [ ] SC-TEST/PREP/PROD ESB `host:port` (`helm/values/<ortam>.yaml` → `ESB_SERVER`)
- [ ] Veri anomalisi (iş birimi): büyükşehir + ilçe kodlu satırlar
- [ ] İş biriminden Excel `menkulTipi`'ni metin (`MENKUL`/`GAYRIMENKUL`) isteme
- [ ] `cancel` (iptal) uçtan uca testi
- [ ] PEN test savunma katmanı (§7) — k8s ortamında
