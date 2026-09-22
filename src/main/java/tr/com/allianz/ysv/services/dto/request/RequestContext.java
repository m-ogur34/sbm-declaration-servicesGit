package tr.com.allianz.ysv.services.dto.request;

import java.util.Set;

/**
 * İsteği başlatanın bilgileri; her uç bunu header'lardan alır.
 *
 * <ul>
 *   <li>{@code X-User-Name} — DB'deki "kim yaptı" kolonlarına yazılır; yoksa {@code SYSTEM}.</li>
 *   <li>{@code X-Requester-Id-Type} + {@code X-Requester-Id-No} — işlemi yapan kişinin kimliği.
 *       Token isteğinde {@code clientIdentityType/No} olarak gider, token servisi aynen geri
 *       döner ve SBM'ye {@code Requester-ID-Type/No} olarak iletilir (SBM Entegrasyon Dokümanı
 *       §5.1). İkisi de yoksa token servisi şirketin VKN'sini döner — toplu işlemler için
 *       dokümanın tarif ettiği yol.</li>
 * </ul>
 *
 * <p>Uygulamada kimlik doğrulama yoktur; bu değerlerin güvenilir bir kaynaktan (gateway / UI
 * backend) gelmesi beklenir. Burada yalnızca biçim doğrulanır ki hatalı değer token servisine
 * ve SBM'ye hiç gitmesin.</p>
 */
public record RequestContext(String userName, String requesterIdType, String requesterIdNo) {

    public static final String USER_HEADER = "X-User-Name";
    public static final String REQUESTER_ID_TYPE_HEADER = "X-Requester-Id-Type";
    public static final String REQUESTER_ID_NO_HEADER = "X-Requester-Id-No";
    public static final String SYSTEM_USER = "SYSTEM";

    /** DB'deki *_BY_USER kolonlarının uzunluğu. */
    static final int USER_NAME_MAX_LENGTH = 100;

    /** 1 = T.C. Kimlik No, 2 = VKN, 4 = Yabancı Kimlik No. */
    private static final Set<String> IDENTITY_TYPES = Set.of("1", "2", "4");
    private static final String TAX_NUMBER_TYPE = "2";

    public static RequestContext system() {
        return new RequestContext(SYSTEM_USER, null, null);
    }

    /**
     * @throws IllegalArgumentException değerlerden biri biçim olarak geçersizse (HTTP 400)
     */
    public static RequestContext of(String userName, String requesterIdType, String requesterIdNo) {
        String user = blankToNull(userName);
        if (user == null) {
            user = SYSTEM_USER;
        } else if (user.length() > USER_NAME_MAX_LENGTH || user.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(USER_HEADER + " en fazla " + USER_NAME_MAX_LENGTH
                    + " karakter olabilir ve kontrol karakteri içeremez.");
        }

        String type = blankToNull(requesterIdType);
        String number = blankToNull(requesterIdNo);
        if (type == null && number == null) {
            return new RequestContext(user, null, null);
        }
        if (type == null || number == null) {
            throw new IllegalArgumentException(REQUESTER_ID_TYPE_HEADER + " ve " + REQUESTER_ID_NO_HEADER
                    + " birlikte gönderilmelidir.");
        }
        if (!IDENTITY_TYPES.contains(type)) {
            throw new IllegalArgumentException(REQUESTER_ID_TYPE_HEADER
                    + " 1 (T.C. Kimlik No), 2 (VKN) veya 4 (Yabancı Kimlik No) olmalıdır.");
        }
        int expectedLength = TAX_NUMBER_TYPE.equals(type) ? 10 : 11;
        if (number.length() != expectedLength || !number.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(REQUESTER_ID_NO_HEADER + " kimlik tipi " + type
                    + " için " + expectedLength + " haneli sayı olmalıdır.");
        }
        return new RequestContext(user, type, number);
    }

    /** @return işlemi yapanın kimliği verildiyse {@code true}; yoksa şirket VKN'si kullanılır */
    public boolean hasRequester() {
        return requesterIdType != null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
