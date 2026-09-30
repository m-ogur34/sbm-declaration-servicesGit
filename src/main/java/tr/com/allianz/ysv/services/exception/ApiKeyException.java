package tr.com.allianz.ysv.services.exception;

/** {@code X-ApiKey} eksik ya da geçersiz: 401. Eksik/yanlış ayrımı istemciye verilmez. */
public class ApiKeyException extends RuntimeException {

    public ApiKeyException() {
        super("API anahtarı eksik veya geçersiz.");
    }
}
