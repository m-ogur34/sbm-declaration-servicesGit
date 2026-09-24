# Lokal Test

Projeyi kendi makinende (ya da SC-TEST / SC-UAT'ta) uçtan uca denemek için.

| Dosya | Ne işe yarar |
|---|---|
| `bruno-lokal-test-2015-01.json` | Bruno koleksiyonu — 8 klasör, 29 istek, sırayla çalıştırılır |
| `test-lokal-2015-01.xlsx` | 12 satır / 6 beyanname (`TESTDEV1501-01..06`), dönem **2015/01** |
| `test-lokal-2015-01-guncelleme.xlsx` | Aynı dönem: `-01` ve `-03` tutarları değişmiş, `-07` yeni — upsert ve PUT testi |
| `temizle-test-verisi.sql` | Test verisini DB'den siler (yalnız SC-TEST / SC-UAT) |

## Neden 2015/01?

SBM'de silme yoktur ve her il-ilçe-dönem yuvası ona **ilk gönderilen dosya no'ya kalıcı olarak
bağlanır**. Test verisi gerçek bir ayı kullanırsa (2026/08'de olduğu gibi) o ayın gerçek verisi SBM
TEST'te `RISK-HAVUZU-00004 mükerrer beyanname` alır. Test verisi bu yüzden gerçek verinin hiç
gönderilmeyeceği geçmiş bir dönemdedir. PEN verisi ayrı dönemdedir (2014/02), ikisi çakışmaz.

> Geçmiş ay SBM TEST'te "veri girişine kapalı" (`RISK-HAVUZU-00003`) dönerse: Bruno ortamındaki
> `yil`/`ay` değişkenlerini ve Excel'deki `ay`/`yil`/dosya no'yu başka bir geçmiş aya çevirin.

## Kullanım

1. Uygulamayı başlat (README §2.5). `local` ortamı `http://localhost:8081` kullanır.
2. Bruno → **Import Collection → Bruno Collection** → `bruno-lokal-test-2015-01.json`.
3. Sağ üstten ortamı seç (`local`, `sc-test` ya da `sc-uat`).
4. Excel gönderen isteklerde (1.x, 5.1, 5.2, 6.2, 7.7) **Body → file** alanından dosyayı seç
   (Bruno içe aktarırken dosya yolunu taşımaz).
5. Klasörleri **sırayla** çalıştır: 0 → 7. Her isteğin **Docs** sekmesinde beklenen sonuç yazılı.

Baştan başlamak için: `temizle-test-verisi.sql` (yalnız test DB'si), sonra 1.1'den tekrar.
