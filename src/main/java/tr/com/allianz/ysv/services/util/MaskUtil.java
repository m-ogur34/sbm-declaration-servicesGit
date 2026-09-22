package tr.com.allianz.ysv.services.util;


public final class MaskUtil {

    private static final int VISIBLE_PREFIX_LENGTH = 10;
    private static final String SUFFIX = "***";

    private MaskUtil() {
    }


    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= VISIBLE_PREFIX_LENGTH) {
            return SUFFIX;
        }
        return value.substring(0, VISIBLE_PREFIX_LENGTH) + SUFFIX;
    }

    /**
     * Kimlik numarasını log için maskeler. VKN (tip 2) kişisel veri olmadığı için olduğu gibi
     * bırakılır; TCKN / Yabancı Kimlik No'nun yalnızca ilk ve son iki hanesi görünür.
     *
     * @return ör. {@code 12*******01}; değer yoksa {@code null}
     */
    public static String maskIdentity(String type, String number) {
        if (number == null) {
            return null;
        }
        if ("2".equals(type) || number.length() <= 4) {
            return "2".equals(type) ? number : SUFFIX;
        }
        return number.substring(0, 2) + "*".repeat(number.length() - 4) + number.substring(number.length() - 2);
    }
}
