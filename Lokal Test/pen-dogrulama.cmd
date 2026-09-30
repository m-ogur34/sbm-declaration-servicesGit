@echo off
rem PEN bulgularinin duzeltmelerini dogrular (2.1, 2.2, 2.3, 2.4). SBM'ye ve DB'ye yazan istek YOKTUR.
rem Kullanim (cmd):
rem   set APIKEY=<ortamin API anahtari>
rem   set BASE=http://localhost:8081/sbm-declaration-services   (vermezseniz bu kullanilir)
rem   "Lokal Test\pen-dogrulama.cmd" > pen-dogrulama-sonuc.txt
setlocal
if "%BASE%"=="" set BASE=http://localhost:8081/sbm-declaration-services
if "%APIKEY%"=="" (echo APIKEY tanimli degil: set APIKEY=... & exit /b 1)
set API=%BASE%/api/v1/declarations
rem Excel'ler script'in yaninda ya da repodaki "Pen Test" klasorunde aranir.
set XSS=%~dp0pentest-2011-05-girdi-yansimasi.xlsx
set OK=%~dp0pentest-2011-01-yukleme.xlsx
if not exist "%XSS%" set XSS=%~dp0..\Pen Test\pentest-2011-05-girdi-yansimasi.xlsx
if not exist "%OK%" set OK=%~dp0..\Pen Test\pentest-2011-01-yukleme.xlsx
if not exist "%XSS%" (echo Excel bulunamadi: pentest-2011-05-girdi-yansimasi.xlsx & exit /b 1)
if not exist "%OK%" (echo Excel bulunamadi: pentest-2011-01-yukleme.xlsx & exit /b 1)

echo ===== 2.2 API anahtari =====
echo [T1] Anahtarsiz toplu gonder - beklenen 401 ALZ-UNAUTHORIZED
curl -s -w "  [HTTP %%{http_code}]" -X POST "%API%/send" -H "Content-Type: application/json" -d "{\"year\":2014,\"month\":2}"
echo.
echo [T2] Yanlis anahtar - beklenen 401
curl -s -w "  [HTTP %%{http_code}]" -X POST "%API%/send" -H "X-ApiKey: yanlis-anahtar" -H "Content-Type: application/json" -d "{\"year\":2014,\"month\":2}"
echo.
echo [T3] Anahtarsiz + bozuk JSON - beklenen 401 (400 degil: govde dogrulanmadan reddedilir)
curl -s -w "  [HTTP %%{http_code}]" -X POST "%API%/send" -H "Content-Type: application/json" -d "{"
echo.
echo [T4] Anahtarsiz kayit listesi (rapor 2.2 #1) - beklenen 401
curl -s -w "  [HTTP %%{http_code}]" "%API%/processes"
echo.
echo [T5] Anahtarsiz tekli sorgu (rapor 2.2 #6) - beklenen 401, SBM'ye gitmez
curl -s -w "  [HTTP %%{http_code}]" "%API%/PENTEST1402-01"
echo.
echo [T6] Dogru anahtar + bos filtre - beklenen 400 ALZ-VALIDATION field:filter (anahtar kabul edildi, SBM'ye gitmez)
curl -s -w "  [HTTP %%{http_code}]" -X POST "%API%/send" -H "X-ApiKey: %APIKEY%" -H "Content-Type: application/json" -d "{}"
echo.
echo [T7] actuator health anahtarsiz - beklenen 200 (k8s probe)
curl -s -w "  [HTTP %%{http_code}]" "%BASE%/actuator/health"
echo.
echo [T8] actuator prometheus anahtarsiz - beklenen 200 (izleme; govde yazdirilmaz)
curl -s -o nul -w "  [HTTP %%{http_code}]" "%BASE%/actuator/prometheus"
echo.

echo ===== 2.1 Girdi yansimasi =====
echo [T9] Dosya adinda script, .csv - beklenen 400 "Sadece .xlsx dosyasi yuklenebilir." (ad geri donmez)
curl -s -w "  [HTTP %%{http_code}]" -X POST "%API%/upload/validate" -H "X-ApiKey: %APIKEY%" -F "file=@%OK%;filename=PEN.zip<script>alert(1)</script>.csv"
echo.
echo [T10] Hucrelerde script/formul/yol - beklenen 200, 2 gecerli + 8 hata; mesajlarda script YOK, gecersiz dosya no null
curl -s -w "  [HTTP %%{http_code}]" -X POST "%API%/upload/validate" -H "X-ApiKey: %APIKEY%" -F "file=@%XSS%"
echo.
echo [T11] Dosya adinda yol (rapor 2.2 #2) - beklenen 200, sourceFileName "pentest-2011-01-yukleme.xlsx" (yol yok)
curl -s -w "  [HTTP %%{http_code}]" -X POST "%API%/upload/validate" -H "X-ApiKey: %APIKEY%" -F "file=@%OK%;filename=./../../../pentest-2011-01-yukleme.xlsx"
echo.
echo [T12] Icerigi Excel olmayan .xlsx - beklenen 400 "Excel dosyasi okunamadi." (kutuphane mesaji yok)
curl -s -w "  [HTTP %%{http_code}]" -X POST "%API%/upload/validate" -H "X-ApiKey: %APIKEY%" -F "file=@%~f0;filename=sahte.xlsx"
echo.

echo ===== 2.4 Tomcat hata sayfasi =====
echo [T13] URL'de %%2f (rapordaki istek) - beklenen 400 ve JSON (HTML / Tomcat yazisi yok)
curl -s -i "%API%/files/..%%2f%%23"
echo.
echo [T14] Olmayan yol - beklenen 404 JSON
curl -s -w "  [HTTP %%{http_code}]" "%BASE%/olmayan-yol"
echo.

echo ===== 2.3 Swagger =====
echo [T15] Swagger - LOKALDE 200/302 normal (dev profili, bilincli acik). k8s (UAT) icin: BASE=UAT adresi, /swagger-ui/index.html 404 olmali
curl -s -o nul -w "  swagger-ui [HTTP %%{http_code}]" "%BASE%/swagger-ui.html"
curl -s -o nul -w "  api-docs [HTTP %%{http_code}]" "%BASE%/v3/api-docs"
echo.
endlocal
