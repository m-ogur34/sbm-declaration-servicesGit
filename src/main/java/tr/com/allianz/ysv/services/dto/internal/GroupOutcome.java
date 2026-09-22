package tr.com.allianz.ysv.services.dto.internal;

/**
 * Bir beyannamenin (tek SBM isteği) sonucu.
 *
 * @param httpStatus    SBM cevap verdiyse SBM'nin HTTP kodu; SBM'ye gidilmeden reddedildiyse
 *                      bizim kodumuz (409 durum çakışması, 422 ön doğrulama, 503 token);
 *                      SBM yerine başka bir cevap geldiyse (ESB HTML, bağlantı hatası) 502
 * @param sbmResponse   SBM'nin ham JSON cevabı; SBM cevap vermediyse {@code null}
 */
public record GroupOutcome(String ysvDosyaNo,
                           boolean success,
                           int httpStatus,
                           String errorCode,
                           String message,
                           String sbmResponse) {

    public static final int BAD_GATEWAY = 502;

    /** SBM çağrısının sonucundan. */
    public static GroupOutcome fromCall(String ysvDosyaNo, SbmCallResult result) {
        boolean answered = result.isSbmAnswered();
        return new GroupOutcome(ysvDosyaNo, result.isSuccess(),
                answered ? result.getHttpStatus() : BAD_GATEWAY,
                result.getErrorCode(), result.getErrorMessage(),
                answered ? result.getResponsePayload() : null);
    }

    /** SBM'ye gitmeden reddedildi. */
    public static GroupOutcome rejected(String ysvDosyaNo, int httpStatus, String errorCode, String message) {
        return new GroupOutcome(ysvDosyaNo, false, httpStatus, errorCode, message, null);
    }
}
