package tr.com.allianz.ysv.services.enums;

/**
 * SBM'ye karşı yapılan işlem tipi. Adı ({@code name()}) {@code ALZ_SBM_DECL_LOG.OPERATION_TYPE}
 * kolonuna yazılır ve loglarda hangi işlemin token istediğini göstermek için kullanılır.
 *
 * <p>Not: token isteğindeki {@code functionName} artık bu enum'dan değil, ortam bazlı
 * {@code token-management.function-name} ayarından okunur.</p>
 */
public enum OperationType {

    /** Yeni beyanname — HTTP POST. */
    POST,

    /** Güncelleme / iptal (tutar sıfırlama) — HTTP PUT. */
    PUT,

    /** Sorgu — HTTP GET. */
    GET,

    /** SBM'ye gitmeyen yerel düzeltme: satır tutarlarının DB'de güncellenmesi. */
    LOCAL_UPDATE
}
