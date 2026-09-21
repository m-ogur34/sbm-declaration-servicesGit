#!/usr/bin/env bash
# Ornek Excel ile ucdan uca test. VDI'da (veya ortama erisimi olan makinede) calistirin.
#   chmod +x test-adimlari.sh && ./test-adimlari.sh
# Lokal calisan uygulama icin BASE'i degistirmeyin; deploy sonrasi ortam URL'ini verin:
#   BASE=https://int-sc-uat-elementer.allianz.com.tr/sbm-declaration-services ./test-adimlari.sh
set -u
BASE="${BASE:-http://localhost:8080/sbm-declaration-services}"
XLSX="${XLSX:-pentest-ornek-beyanname.xlsx}"
CT='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

adim() { echo; echo "======== $* ========"; }
cikti() { echo "--- HTTP $1"; shift; echo "$*"; }

adim "0) Saglik"
curl -s -w '\n--- HTTP %{http_code}\n' "$BASE/actuator/health"

adim "1) Excel yukle  ($XLSX)"
curl -s -w '\n--- HTTP %{http_code}\n' -X POST "$BASE/api/v1/declarations/upload" \
  -H 'X-User-Name: pentest' -F "file=@$XLSX;type=$CT"
echo ">> Beklenen: inserted=12, failed=0"

adim "2) Kayitlari listele (NEW)"
curl -s -w '\n--- HTTP %{http_code}\n' \
  "$BASE/api/v1/declarations/processes?status=NEW&year=2026&month=8&page=0&size=50&sort=id,desc"
echo ">> Beklenen: 12 satir, COMPANY_CODE 045, il 34/35/6 icin districtCode 0"

adim "3) Gonder - sadece il 34 (1 grup / 2 satir)"
curl -s -w '\n--- HTTP %{http_code}\n' -X POST "$BASE/api/v1/declarations/send" \
  -H 'Content-Type: application/json' -H 'X-User-Name: pentest' \
  -d '{"year":2026,"month":8,"cityCode":34}'
echo ">> Beklenen: totalGroups=1, successCount=1"

adim "4) Durum kontrolu (SENT olmali)"
curl -s -w '\n--- HTTP %{http_code}\n' \
  "$BASE/api/v1/declarations/processes?status=SENT&year=2026&month=8"

adim "5) Guncelle - ayni grup"
curl -s -w '\n--- HTTP %{http_code}\n' -X PUT "$BASE/api/v1/declarations/update" \
  -H 'Content-Type: application/json' -H 'X-User-Name: pentest' \
  -d '{"year":2026,"month":8,"cityCode":34}'
echo ">> Beklenen: totalGroups=1, successCount=1"

adim "6) Sorgula - PENTEST260801"
curl -s -w '\n--- HTTP %{http_code}\n' "$BASE/api/v1/declarations/query/PENTEST260801" \
  -H 'X-User-Name: pentest'
echo ">> Beklenen: result=true, data{...}. ESB GET route'u duzelmediyse CORE-00004"

adim "7) Ayni dosyayi tekrar yukle (mukerrer kontrolu)"
curl -s -w '\n--- HTTP %{http_code}\n' -X POST "$BASE/api/v1/declarations/upload" \
  -H 'X-User-Name: pentest' -F "file=@$XLSX;type=$CT"
echo ">> Beklenen: inserted=0, failed=12, hepsi ALZ-EXCEL-DUPLICATE"

adim "8) Hatali satir dosyasi"
curl -s -w '\n--- HTTP %{http_code}\n' -X POST "$BASE/api/v1/declarations/upload" \
  -H 'X-User-Name: pentest' -F "file=@pentest-hatali-satirlar.xlsx;type=$CT"
echo ">> Beklenen: HTTP 400 - dosyada iki farkli donem var (ay 7 ve 8)"

adim "9) Negatif - gecersiz filtre"
curl -s -w '\n--- HTTP %{http_code}\n' -X POST "$BASE/api/v1/declarations/send" \
  -H 'Content-Type: application/json' -d '{"year":1999,"month":13,"cityCode":99}'
echo ">> Beklenen: HTTP 400, code=ALZ-VALIDATION"
