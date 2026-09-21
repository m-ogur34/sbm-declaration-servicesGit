---------------------------------------------------------------------
-- SBM YSV BEYANNAME OTOMASYON SİSTEMİ
-- rollback_db.sql
-- Açıklama: setup_db.sql'in oluşturduğu tüm objeleri geri alır.
--   Sıra: (public synonym) -> (tablolar, CASCADE CONSTRAINTS) -> (sequence)
--   Index'ler ve constraint'ler tablo ile birlikte düşer.
--   Grant ve comment'ler obje ile birlikte kaybolur.
--
-- Bu script TÜM ORTAMLARA deploy edilebilecek şekilde TOLERANSLIDIR:
--   obje yoksa hata vermez (ORA-00942 / ORA-02289 / ORA-01432 / ORA-04043 yutulur).
--   Beklenmeyen bir hata olursa RAISE eder ve durur.
--
-- KULLANIM: CUSTOMER şemasına yetkili bir kullanıcı ile bir kez çalıştırılır.
---------------------------------------------------------------------

SET SERVEROUTPUT ON

DECLARE
    PROCEDURE exec_ignore(p_sql IN VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
        DBMS_OUTPUT.PUT_LINE('OK   : ' || p_sql);
    EXCEPTION
        WHEN OTHERS THEN
            -- -00942: table or view does not exist
            -- -02289: sequence does not exist
            -- -01432: public synonym to be dropped does not exist
            -- -04043: object does not exist
            -- -01434: private synonym to be dropped does not exist
            IF SQLCODE IN (-942, -2289, -1432, -4043, -1434) THEN
                DBMS_OUTPUT.PUT_LINE('SKIP : ' || p_sql || '  (' || SQLCODE || ')');
            ELSE
                DBMS_OUTPUT.PUT_LINE('FAIL : ' || p_sql || '  -> ' || SQLERRM);
                RAISE;
            END IF;
    END;
BEGIN
    -- 1. PUBLIC SYNONYMS
    exec_ignore('DROP PUBLIC SYNONYM ALZ_SBM_DECL_LOG');
    exec_ignore('DROP PUBLIC SYNONYM ALZ_SBM_DECL_PROCESS');
    exec_ignore('DROP PUBLIC SYNONYM ALZ_SBM_MUNICIPALITY');
    exec_ignore('DROP PUBLIC SYNONYM ALZ_SBM_DECL_LOG_SEQ');
    exec_ignore('DROP PUBLIC SYNONYM ALZ_SBM_DECL_PROCESS_SEQ');
    exec_ignore('DROP PUBLIC SYNONYM ALZ_SBM_MUNICIPALITY_SEQ');

    -- 2. TABLES  (FK'li ALZ_SBM_DECL_LOG önce; CASCADE CONSTRAINTS + PURGE)
    exec_ignore('DROP TABLE CUSTOMER.ALZ_SBM_DECL_LOG CASCADE CONSTRAINTS PURGE');
    exec_ignore('DROP TABLE CUSTOMER.ALZ_SBM_DECL_PROCESS CASCADE CONSTRAINTS PURGE');
    exec_ignore('DROP TABLE CUSTOMER.ALZ_SBM_MUNICIPALITY CASCADE CONSTRAINTS PURGE');

    -- 3. SEQUENCES
    exec_ignore('DROP SEQUENCE CUSTOMER.ALZ_SBM_DECL_LOG_SEQ');
    exec_ignore('DROP SEQUENCE CUSTOMER.ALZ_SBM_DECL_PROCESS_SEQ');
    exec_ignore('DROP SEQUENCE CUSTOMER.ALZ_SBM_MUNICIPALITY_SEQ');

    DBMS_OUTPUT.PUT_LINE('---------------------------------------------------------------------');
    DBMS_OUTPUT.PUT_LINE('rollback_db.sql tamamlandi.');
END;
/

---------------------------------------------------------------------
-- Doğrulama (hiç satır dönmemeli):
--   SELECT object_name, object_type FROM all_objects
--    WHERE owner = 'CUSTOMER' AND object_name LIKE 'ALZ_SBM%';
--   SELECT synonym_name FROM all_synonyms
--    WHERE synonym_name LIKE 'ALZ_SBM%';
---------------------------------------------------------------------
