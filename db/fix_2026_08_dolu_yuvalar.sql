-- =============================================================================
-- fix_2026_08_dolu_yuvalar.sql - 2026/8 gercek veri testi sonrasi DB duzeltmesi
-- =============================================================================
-- YALNIZ SC-TEST / SC-UAT. PROD'DA CALISTIRILMAZ.
--
-- Neden:
--  Asagidaki 17 beyannamenin il-ilce-donem yuvasi SBM test ortaminda bizim eski
--  PENTEST260801-26 test beyannamelerimizle dolu. POST'ta SBM "mukerrer, Dosya no:
--  PENTEST..." dedi; eski kod bunu "beyanname zaten SBM'de" sanip satirlari SENT yapti.
--  Gercekte bu dosya numaralariyla SBM'de beyanname YOK (sorgu 404 CORE-01001).
--  SENT kalirlarsa: listede gonderilmis gorunurler, "gonder" onlari atlar (yalniz
--  NEW/ERROR gonderilir), guncelle/sorgula 404 alir. ERROR yapinca DB gercegi soyler
--  ve yuva bosaltilinca normal "gonder" ile gonderilebilirler.
--
--  Ayrica DB'de kalan PENTEST260821-26 satirlari bu yuvalarin 5'inde gercek satirlarla
--  ayni gruba (yil, ay, il, ilce) dusuyor; toplu guncellemede iki beyannamenin satirlari
--  tek istege karisti (RISK-HAVUZU-00005 "mukerrer menkul tipi"). Bu yuzden silinir.
-- =============================================================================

-- 0) NEREDEYIM?  Beklenen: test/uat DB'si (PROD ise DUR)
SELECT sys_context('USERENV','DB_NAME') AS db_name, sys_context('USERENV','SESSION_USER') AS session_user FROM dual;

-- 1) ONCE: etkilenecek satirlar (beklenen 34 satir, hepsi SENT)
SELECT SBM_FILE_NO, MOVABLE_TYPE, CITY_CODE, DISTRICT_CODE, STATUS
FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
WHERE DECLARATION_YEAR = 2026 AND DECLARATION_MONTH = 8
  AND SBM_FILE_NO IN ('YSV2027776','YSV2027777','YSV2027778','YSV2027779','YSV2027780',
                      'YSV2027783','YSV2027785','YSV2027788','YSV2027789','YSV2027812',
                      'YSV2027813','YSV2027822','YSV2027823','YSV2027854','YSV2027878',
                      'YSV2027935','YSV2027936')
ORDER BY SBM_FILE_NO, MOVABLE_TYPE;

-- 2) 17 beyanname: SENT -> ERROR (beklenen: 34 rows updated)
UPDATE CUSTOMER.ALZ_SBM_DECL_PROCESS
   SET STATUS          = 'ERROR',
       ERROR_DETAILS   = 'RISK-HAVUZU-00004: il-ilce-donem SBM test ortaminda PENTEST beyannamesiyle dolu; bu dosya no SBM''de yok',
       DATE_UPDATED    = SYSTIMESTAMP,
       UPDATED_BY_USER = 'SQL-DUZELTME'
 WHERE DECLARATION_YEAR = 2026 AND DECLARATION_MONTH = 8
   AND STATUS = 'SENT'
   AND SBM_FILE_NO IN ('YSV2027776','YSV2027777','YSV2027778','YSV2027779','YSV2027780',
                       'YSV2027783','YSV2027785','YSV2027788','YSV2027789','YSV2027812',
                       'YSV2027813','YSV2027822','YSV2027823','YSV2027854','YSV2027878',
                       'YSV2027935','YSV2027936');

-- 3) DB'de kalan eski PENTEST satirlari (2026/8) ve loglari (beklenen: 12 satir)
DELETE FROM CUSTOMER.ALZ_SBM_DECL_LOG
 WHERE PROCESS_ID IN (SELECT ID FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
                       WHERE DECLARATION_YEAR = 2026 AND DECLARATION_MONTH = 8
                         AND SBM_FILE_NO LIKE 'PENTEST%');
DELETE FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
 WHERE DECLARATION_YEAR = 2026 AND DECLARATION_MONTH = 8
   AND SBM_FILE_NO LIKE 'PENTEST%';

-- Sayilar beklendigi gibiyse:
COMMIT;
-- Degilse: ROLLBACK;

-- 4) SONRA: 2026/8 durum dagilimi (beklenen: SENT/COMPLETED 562 satir, ERROR 34 satir)
SELECT STATUS, COUNT(*) AS satir, COUNT(DISTINCT SBM_FILE_NO) AS beyanname
FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
WHERE DECLARATION_YEAR = 2026 AND DECLARATION_MONTH = 8
GROUP BY STATUS ORDER BY STATUS;
