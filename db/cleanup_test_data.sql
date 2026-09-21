-- =============================================================================
-- cleanup_test_data.sql - TEST VERISINI TEMIZLEME
-- =============================================================================
-- Sadece islem verisini siler: ALZ_SBM_DECL_LOG + ALZ_SBM_DECL_PROCESS.
-- Referans tablosu ALZ_SBM_MUNICIPALITY'ye DOKUNMAZ.
--
-- !! PROD'DA CALISTIRILMAZ !!  Once asagidaki kontrolu calistirip hangi
-- veritabaninda oldugunuzu dogrulayin. Firma politikasi geregi prod DB'de
-- manuel script yasaktir; bu dosya yalniz SC-TEST / SC-UAT icindir.
--
-- Silme sirasi onemli: LOG tablosu PROCESS'e FK ile bagli (FK_SBM_LOG_PROCESS),
-- once cocuk tablo bosaltilir.
-- =============================================================================

-- 0) NEREDEYIM?  Beklenen: OPSSCUAT / OPSSCTST (PROD ise DUR)
SELECT sys_context('USERENV','DB_NAME')      AS db_name,
       sys_context('USERENV','SESSION_USER') AS session_user,
       sys_context('USERENV','SERVICE_NAME') AS service_name
FROM dual;

-- 1) SILMEDEN ONCE: ne kadar veri var?
SELECT 'DECL_PROCESS' AS tablo, COUNT(*) AS satir FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
UNION ALL
SELECT 'DECL_LOG',              COUNT(*)         FROM CUSTOMER.ALZ_SBM_DECL_LOG;

-- Durum dagilimi (neyi sildiginizi bilin)
SELECT STATUS, COUNT(*) AS satir
FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
GROUP BY STATUS
ORDER BY STATUS;


-- =============================================================================
-- SECENEK A - HEPSINI SIL (bos tablolarla sifirdan baslamak icin)
-- =============================================================================
DELETE FROM CUSTOMER.ALZ_SBM_DECL_LOG;
DELETE FROM CUSTOMER.ALZ_SBM_DECL_PROCESS;
COMMIT;

-- Sequence'leri 1'den baslat (Oracle 12.2+ / 18c+). ID'ler 1'den devam etsin diye.
ALTER SEQUENCE CUSTOMER.ALZ_SBM_DECL_PROCESS_SEQ RESTART START WITH 1;
ALTER SEQUENCE CUSTOMER.ALZ_SBM_DECL_LOG_SEQ     RESTART START WITH 1;


-- =============================================================================
-- SECENEK B - SADECE BELIRLI DONEMI SIL (digerlerine dokunmadan)
-- =============================================================================
-- DELETE FROM CUSTOMER.ALZ_SBM_DECL_LOG
--  WHERE PROCESS_ID IN (SELECT ID FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
--                        WHERE DECLARATION_YEAR = 2026 AND DECLARATION_MONTH = 7);
-- DELETE FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
--  WHERE DECLARATION_YEAR = 2026 AND DECLARATION_MONTH = 7;
-- COMMIT;


-- =============================================================================
-- SECENEK C - SADECE PEN TEST / ORNEK DOSYA VERISINI SIL
-- =============================================================================
-- Yuklenen dosya adina gore (SOURCE_FILE_NAME) veya dosya no onekine gore.
-- DELETE FROM CUSTOMER.ALZ_SBM_DECL_LOG
--  WHERE PROCESS_ID IN (SELECT ID FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
--                        WHERE SOURCE_FILE_NAME LIKE 'pentest-%'
--                           OR SBM_FILE_NO     LIKE 'PENTEST%');
-- DELETE FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
--  WHERE SOURCE_FILE_NAME LIKE 'pentest-%'
--     OR SBM_FILE_NO     LIKE 'PENTEST%';
-- COMMIT;


-- =============================================================================
-- SECENEK D - TRUNCATE (cok satir varsa hizli; FK yuzunden ekstra adim gerekir)
-- =============================================================================
-- Oracle, ustune FK bakan tablo varken parent'i TRUNCATE ettirmez; once
-- kisitlama devre disi birakilir, sonra geri acilir.
-- ALTER TABLE CUSTOMER.ALZ_SBM_DECL_LOG DISABLE CONSTRAINT FK_SBM_LOG_PROCESS;
-- TRUNCATE TABLE CUSTOMER.ALZ_SBM_DECL_LOG;
-- TRUNCATE TABLE CUSTOMER.ALZ_SBM_DECL_PROCESS;
-- ALTER TABLE CUSTOMER.ALZ_SBM_DECL_LOG ENABLE CONSTRAINT FK_SBM_LOG_PROCESS;
-- ALTER SEQUENCE CUSTOMER.ALZ_SBM_DECL_PROCESS_SEQ RESTART START WITH 1;
-- ALTER SEQUENCE CUSTOMER.ALZ_SBM_DECL_LOG_SEQ     RESTART START WITH 1;
-- NOT: TRUNCATE DDL'dir, geri alinamaz (rollback yok).


-- =============================================================================
-- 2) SILDIKTEN SONRA: dogrulama
-- =============================================================================
SELECT 'DECL_PROCESS' AS tablo, COUNT(*) AS satir FROM CUSTOMER.ALZ_SBM_DECL_PROCESS
UNION ALL
SELECT 'DECL_LOG',              COUNT(*)         FROM CUSTOMER.ALZ_SBM_DECL_LOG
UNION ALL
SELECT 'MUNICIPALITY (silinmemeli)', COUNT(*)    FROM CUSTOMER.ALZ_SBM_MUNICIPALITY;

-- Sequence'in sonraki degeri (bos tabloda 1 bekleniyor)
SELECT sequence_name, last_number
FROM all_sequences
WHERE sequence_owner = 'CUSTOMER'
  AND sequence_name IN ('ALZ_SBM_DECL_PROCESS_SEQ','ALZ_SBM_DECL_LOG_SEQ');
