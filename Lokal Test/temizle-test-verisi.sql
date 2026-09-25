-- =============================================================================
-- temizle-test-verisi.sql — Lokal ve PEN test verisini DB'den siler
-- =============================================================================
-- YALNIZ SC-TEST / SC-UAT. PROD'DA CALISTIRILMAZ (firma politikasi: prod'da elle script yasak).
--
-- Silinen: dosya no'su TESTDEV1501-% (lokal test, donem 2015/01) , PENTEST1402-% (PEN, 2014/02) ve UATTEST1301-% (UAT, 2013/01)
-- olan satirlar ve loglari. Gercek veriye ve baska donemlere dokunmaz.
--
-- Not: SBM tarafinda silme yoktur. Temizleyip tekrar yukler ve gonderirseniz SBM TEST
-- "RISK-HAVUZU-00004 mukerrer beyanname" doner; mesajdaki dosya no ayni oldugu icin uygulama
-- satiri SENT yapar ve test kaldigi yerden surer (koleksiyonlar tekrar kosulabilir).
-- =============================================================================

-- 0) NEREDEYIM?  Beklenen: test/uat DB'si (PROD ise DUR)
SELECT sys_context('USERENV','DB_NAME') AS db_name, sys_context('USERENV','SESSION_USER') AS session_user FROM dual;

-- 1) ONCE: silinecekler
SELECT SBM_FILE_NO, MOVABLE_TYPE, DECLARATION_YEAR, DECLARATION_MONTH, STATUS
  FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
 WHERE SBM_FILE_NO LIKE 'TESTDEV1501-%' OR SBM_FILE_NO LIKE 'PENTEST1402-%' OR SBM_FILE_NO LIKE 'UATTEST1301-%'
 ORDER BY SBM_FILE_NO, MOVABLE_TYPE;

-- 2) SIL (once log, sonra surec tablosu — FK)
DELETE FROM CUSTOMER.ALZ_SBM_DECL_LOG
 WHERE PROCESS_ID IN (SELECT ID FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
                       WHERE SBM_FILE_NO LIKE 'TESTDEV1501-%' OR SBM_FILE_NO LIKE 'PENTEST1402-%' OR SBM_FILE_NO LIKE 'UATTEST1301-%');
DELETE FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
 WHERE SBM_FILE_NO LIKE 'TESTDEV1501-%' OR SBM_FILE_NO LIKE 'PENTEST1402-%' OR SBM_FILE_NO LIKE 'UATTEST1301-%';

-- Sayilar beklendigi gibiyse:
COMMIT;
-- Degilse: ROLLBACK;
